package ie.napkin.supertasks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.format.InkRef
import ie.napkin.supertasks.data.format.PageDoc
import ie.napkin.supertasks.data.format.Prose
import ie.napkin.supertasks.data.format.TaskRef
import ie.napkin.supertasks.data.format.TaskStatus
import ie.napkin.supertasks.data.workspace.WorkspaceIndex
import ie.napkin.supertasks.data.workspace.WorkspaceReconciler
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Phase 2's exit criterion, through real git rather than a mock of it.
 *
 * The claim the whole design rests on is that **files are the truth and the index is disposable**.
 * The way to test that is not to inspect either one, but to send a workspace round a git repo to a
 * different clone and check the index it rebuilds there is the same index — because if it is, then
 * nothing lives only in a database, and deleting one costs nothing.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceGitTest {

    private lateinit var root: File
    private val zone: ZoneId = ZoneId.of("Europe/Dublin")
    private val now = 1_787_000_000_000L

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "ws-${System.nanoTime()}").apply { mkdirs() }
    }

    private fun git(dir: File): Git =
        Git.init().setDirectory(dir).setInitialBranch("yantra-tasks").call()

    private fun Git.commitAll(msg: String) {
        add().addFilepattern(".").call()
        // Deletions are not picked up by addFilepattern; without this a removed page would stay in
        // the tree and reappear on the next clone.
        add().addFilepattern(".").setUpdate(true).call()
        commit().setMessage(msg)
            .setAuthor("Yantra", "yantra@napkin.ie")
            .setCommitter("Yantra", "yantra@napkin.ie")
            .call()
    }

    /** A workspace with one of everything the format can express. */
    private fun buildWorkspace(dir: File): WorkspaceStore {
        val store = WorkspaceStore(dir)
        store.scaffold("Test workspace", now)

        store.writePage(
            PageDoc(
                id = "list-1", type = NodeType.LIST, parent = null, title = "Getting started",
                systemKey = "inbox", modifiedAt = Instant.ofEpochMilli(now), device = "sm-s921b",
                blocks = listOf(
                    TaskRef(
                        id = "task-1", title = "Wire up the sync worker",
                        status = TaskStatus.IN_PROGRESS,
                        priority = "high", labels = listOf("sync"), assignee = "batunii",
                    ),
                    TaskRef(id = "task-2", title = "A plain one", status = TaskStatus.DONE),
                ),
            )
        )
        store.writePage(
            PageDoc(
                id = "task-1", type = NodeType.TASK, parent = "list-1", title = null,
                modifiedAt = Instant.ofEpochMilli(now), device = "sm-s921b",
                blocks = listOf(
                    Prose("Notes about the worker."),
                    Prose("Indented under it.", indent = 1),
                    InkRef("ink-1"),
                ),
            )
        )
        // Two strokes, as opaque as the real ones — the point is that bytes survive git.
        store.writeInk("ink-1", listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(9, 8, 7)))
        return store
    }

    /** Everything except [WorkspaceIndex.problems], which is diagnostics rather than content. */
    private fun content(i: WorkspaceIndex) = listOf(
        i.nodes.sortedBy { it.id }.map { "${it.id}|${it.type}|${it.title}|${it.parentId}|${it.done}|${it.inProgress}|${it.indent}|${it.systemKey}" },
        i.values.sortedWith(compareBy({ it.nodeId }, { it.defId })).map { "${it.nodeId}|${it.defId}|${it.vText}|${it.vDate}|${it.vBool}|${it.vNumber}" },
        i.labels.map { "${it.id}|${it.name}|${it.color}" },
        i.nodeLabels.sortedWith(compareBy({ it.nodeId }, { it.labelId })).map { "${it.nodeId}|${it.labelId}" },
        i.defs.sortedBy { it.id }.map { "${it.id}|${it.name}|${it.kind}" },
        i.ink.sortedBy { it.id }.map { "${it.nodeId}|${it.data.joinToString(",")}" },
    )

    // ---- the exit criterion ----

    @Test
    fun aWorkspaceSurvivesGitAndRebuildsTheSameIndex() {
        val origin = File(root, "origin.git")
        Git.init().setDirectory(origin).setBare(true).setInitialBranch("yantra-tasks").call().close()

        val here = File(root, "here")
        val store = buildWorkspace(here)
        val before = WorkspaceReconciler.read(store, now, zone)
        assertTrue("workspace had problems: ${before.problems}", before.problems.isEmpty())

        git(here).use { g ->
            g.commitAll("scaffold")
            g.remoteAdd().setName("origin").setUri(URIish(origin.toURI().toString())).call()
            g.push().setRemote("origin").add("yantra-tasks").call()
        }

        // A different device, which has never seen any of this.
        val there = File(root, "there")
        Git.cloneRepository()
            .setURI(origin.toURI().toString())
            .setDirectory(there)
            .setBranch("yantra-tasks")
            .call().close()

        val after = WorkspaceReconciler.read(WorkspaceStore(there), now, zone)
        assertTrue("clone had problems: ${after.problems}", after.problems.isEmpty())
        assertEquals(content(before), content(after))
    }

    @Test
    fun theIndexIsDisposable() {
        // Nothing here touches Room, but the shape of the claim is the same: reading twice from the
        // same files produces the same rows, so throwing the rows away costs nothing.
        val store = buildWorkspace(File(root, "w"))
        assertEquals(
            content(WorkspaceReconciler.read(store, now, zone)),
            content(WorkspaceReconciler.read(store, now, zone)),
        )
    }

    @Test
    fun anEditOnOneCloneReachesTheOther() {
        val origin = File(root, "origin.git")
        Git.init().setDirectory(origin).setBare(true).setInitialBranch("yantra-tasks").call().close()

        val a = File(root, "a")
        buildWorkspace(a)
        git(a).use { g ->
            g.commitAll("scaffold")
            g.remoteAdd().setName("origin").setUri(URIish(origin.toURI().toString())).call()
            g.push().setRemote("origin").add("yantra-tasks").call()
        }

        val b = File(root, "b")
        Git.cloneRepository().setURI(origin.toURI().toString()).setDirectory(b)
            .setBranch("yantra-tasks").call().close()

        // A ticks a task off and pushes.
        val storeA = WorkspaceStore(a)
        val page = storeA.readPages().single { it.id == "list-1" }
        storeA.writePage(
            page.copy(
                blocks = page.blocks.map {
                    if (it is TaskRef && it.id == "task-1") it.copy(status = TaskStatus.DONE) else it
                }
            )
        )
        Git.open(a).use { g -> g.commitAll("done"); g.push().call() }

        // B pulls and reindexes.
        Git.open(b).use { g -> g.pull().setRemote("origin").setRemoteBranchName("yantra-tasks").call() }
        val onB = WorkspaceReconciler.read(WorkspaceStore(b), now, zone)
        assertTrue("the completion did not arrive", onB.nodes.single { it.id == "task-1" }.done)
    }

    @Test
    fun aDeletedPageIsGoneFromTheCloneToo() {
        // Git is the tombstone; there is no deleted_at to carry.
        val origin = File(root, "origin.git")
        Git.init().setDirectory(origin).setBare(true).setInitialBranch("yantra-tasks").call().close()

        val a = File(root, "a")
        val storeA = buildWorkspace(a)
        git(a).use { g ->
            g.commitAll("scaffold")
            g.remoteAdd().setName("origin").setUri(URIish(origin.toURI().toString())).call()
            g.push().setRemote("origin").add("yantra-tasks").call()
        }

        storeA.deletePage("task-1")
        val list = storeA.readPages().single { it.id == "list-1" }
        storeA.writePage(list.copy(blocks = list.blocks.filterNot { it is TaskRef && it.id == "task-1" }))
        Git.open(a).use { g -> g.commitAll("delete"); g.push().call() }

        val b = File(root, "b")
        Git.cloneRepository().setURI(origin.toURI().toString()).setDirectory(b)
            .setBranch("yantra-tasks").call().close()

        val onB = WorkspaceReconciler.read(WorkspaceStore(b), now, zone)
        assertTrue(onB.nodes.none { it.id == "task-1" })
        assertTrue("the ink sidecar outlived its page", !WorkspaceStore(b).inkFile("ink-1").exists())
    }

    // ---- forgiveness ----

    @Test
    fun anOrphanedPageIsReportedAndKept() {
        val store = WorkspaceStore(File(root, "w")).also { it.scaffold("w", now) }
        store.writePage(
            PageDoc(
                id = "lost", type = NodeType.TASK, parent = "nobody", title = null,
                modifiedAt = Instant.ofEpochMilli(now), device = null,
                blocks = listOf(Prose("content nobody can reach")),
            )
        )
        val i = WorkspaceReconciler.read(store, now, zone)
        assertTrue(i.problems.any { it.contains("nobody") })
        assertTrue("an orphan was dropped rather than reported", i.nodes.any { it.id == "lost" })
    }

    @Test
    fun aMissingInkSidecarIsReportedNotFatal() {
        val store = WorkspaceStore(File(root, "w")).also { it.scaffold("w", now) }
        store.writePage(
            PageDoc(
                id = "p", type = NodeType.LIST, parent = null, title = "p",
                modifiedAt = Instant.ofEpochMilli(now), device = null,
                blocks = listOf(InkRef("missing")),
            )
        )
        val i = WorkspaceReconciler.read(store, now, zone)
        assertTrue(i.problems.any { it.contains("missing") })
        assertTrue(i.nodes.any { it.type == NodeType.INK })
    }

    @Test
    fun inkBytesSurviveTheRoundTripExactly() {
        val store = buildWorkspace(File(root, "w"))
        val strokes = store.readInk("ink-1")
        assertEquals(2, strokes.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), strokes[0].toList())
        assertEquals(listOf<Byte>(9, 8, 7), strokes[1].toList())
    }

    @Test
    fun aLockLeftByAKilledProcessDoesNotBreakSyncForever() {
        // What actually happened: the app was force-stopped mid-commit, `.git/index.lock` survived,
        // and every sync afterwards failed against it. Nothing in git removes such a file, so without
        // recovery a single badly-timed death breaks sync permanently — with no remedy inside the app
        // and nothing on screen the user could act on.
        val dir = File(root, "locked").apply { mkdirs() }
        val repo = ie.napkin.supertasks.data.sync.GitRepo(dir, "yantra-tasks")
        repo.init().use { git ->
            buildWorkspace(dir)
            repo.commitAll(git, "first", "Yantra", "yantra@napkin.ie")
        }

        val lock = File(File(dir, ".git"), "index.lock")
        lock.createNewFile()
        lock.setLastModified(System.currentTimeMillis() - 5 * 60_000)

        val cleared = repo.clearStaleLock()

        assertTrue("the lock was not reported", cleared != null)
        assertTrue("the lock is still there", !lock.exists())
    }

    @Test
    fun aLockThatMightStillBeInUseIsLeftAlone() {
        // A fresh lock could belong to a write happening right now. Deleting one of those would
        // corrupt the very thing the lock protects, which is far worse than a failed sync.
        val dir = File(root, "busy").apply { mkdirs() }
        val repo = ie.napkin.supertasks.data.sync.GitRepo(dir, "yantra-tasks")
        repo.init().use { git ->
            buildWorkspace(dir)
            repo.commitAll(git, "first", "Yantra", "yantra@napkin.ie")
        }

        val lock = File(File(dir, ".git"), "index.lock")
        lock.createNewFile()

        assertEquals(null, repo.clearStaleLock())
        assertTrue("a live lock was removed", lock.exists())
    }
}
