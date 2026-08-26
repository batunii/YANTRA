package ie.napkin.supertasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.format.PageCodec
import ie.napkin.supertasks.data.format.PageDoc
import ie.napkin.supertasks.data.format.Prose
import ie.napkin.supertasks.data.format.TaskRef
import ie.napkin.supertasks.data.format.TaskStatus
import ie.napkin.supertasks.data.sync.GitRepo
import ie.napkin.supertasks.data.sync.SyncEngine
import ie.napkin.supertasks.data.workspace.Indexer
import ie.napkin.supertasks.data.workspace.WorkspaceReconciler
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * The conflict matrix — GIT_WORKSPACES_PLAN.md §9, where the plan says the app lives or dies.
 *
 * Two clones of one bare repo, no network, editing on purpose into each other. Every case asserts
 * the same two things: that the devices **converge**, and that convergence did not quietly cost
 * somebody their work. A sync that agrees by throwing half the content away agrees about nothing
 * worth having.
 */
@RunWith(AndroidJUnit4::class)
class SyncMatrixTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var origin: File

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val branch = "yantra-tasks"

    /** One device: its own directory, its own clone, its own engine. */
    private inner class Device(val name: String) {
        val dir = File(root, name)
        val store = WorkspaceStore(dir, name)
        val repo = GitRepo(dir, branch)
        val engine = SyncEngine(store, Indexer(db), repo, name)

        fun clone() {
            Git.cloneRepository().setURI(origin.toURI().toString())
                .setDirectory(dir).setBranch(branch).call().close()
        }

        fun page(id: String): PageDoc? =
            store.pageFile(id).takeIf { it.exists() }?.let { PageCodec.decode(it.readText()) }

        fun writePage(doc: PageDoc, at: Long) =
            store.writePage(doc.copy(modifiedAt = Instant.ofEpochMilli(at), device = name))

        fun task(pageId: String, taskId: String): TaskRef? =
            page(pageId)?.blocks?.filterIsInstance<TaskRef>()?.firstOrNull { it.id == taskId }
    }

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "sync-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        origin = File(root, "origin.git")
        Git.init().setDirectory(origin).setBare(true).setInitialBranch(branch).call().close()

        // Seed the remote the way a first device would: init locally, push, because JGit refuses
        // to clone a repo with no HEAD.
        val seed = File(root, "seed")
        val store = WorkspaceStore(seed, "seed").also { it.scaffold("Shared", 1_000L) }
        store.writePage(
            PageDoc(
                id = "list", type = NodeType.LIST, parent = null, title = "Shared list",
                modifiedAt = Instant.ofEpochMilli(1_000L), device = "seed",
                blocks = listOf(
                    TaskRef(id = "t1", title = "Original"),
                    TaskRef(id = "t2", title = "Untouched"),
                ),
            )
        )
        val g = GitRepo(seed, branch)
        g.init().use {
            g.commitAll(it, "seed", "Yantra", "y@napkin.ie")
            g.addRemote(it, origin.toURI().toString())
            g.push(it, null)
        }
    }

    @After
    fun tearDown() = db.close()

    // ---- the easy half: git can merge it ----

    @Test
    fun editsToDifferentFilesBothSurvive() = runBlocking {
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        a.writePage(a.page("list")!!.copy(blocks = a.page("list")!!.blocks + TaskRef("a1", "From A")), 2_000L)
        assertTrue(a.engine.sync().ok)

        b.store.writePage(
            PageDoc("other", NodeType.LIST, null, "B's list", null, Instant.ofEpochMilli(2_100L), "b", emptyList())
        )
        assertTrue(b.engine.sync().ok)
        assertTrue(a.engine.sync().ok)

        assertTrue("A lost B's page", a.store.pageFile("other").exists())
        assertTrue("B lost A's task", b.task("list", "a1") != null)
    }

    @Test
    fun editsToDifferentLinesOfOnePageMergeWithoutArbitration() = runBlocking {
        // The case the whole format exists for: two people on one page, no conflict at all.
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        a.writePage(
            a.page("list")!!.let { p ->
                p.copy(blocks = p.blocks.map { if (it is TaskRef && it.id == "t1") it.copy(status = TaskStatus.DONE) else it })
            },
            2_000L,
        )
        assertTrue(a.engine.sync().ok)

        b.writePage(
            b.page("list")!!.let { p ->
                p.copy(blocks = p.blocks.map { if (it is TaskRef && it.id == "t2") it.copy(title = "Renamed by B") else it })
            },
            2_100L,
        )
        val res = b.engine.sync()
        assertTrue(res.ok)
        // git cannot merge this on its own, and never will: modified_at sits in the frontmatter and
        // both devices rewrite it on every save, so any two concurrent edits to one page collide on
        // that line whether or not they disagree about anything. What matters is not that
        // arbitration was skipped but that it *merged* rather than picking a winner.
        assertTrue(
            "a page edit was decided by last-writer-wins: ${res.conflicts.map { it.reason }}",
            res.conflicts.all { it.reason.contains("merged") },
        )

        a.engine.sync()
        assertEquals(TaskStatus.DONE, a.task("list", "t1")?.status)
        assertEquals("Renamed by B", a.task("list", "t2")?.title)
        // The point of all of it: neither device lost the other's edit.
        assertEquals(TaskStatus.DONE, b.task("list", "t1")?.status)
        assertEquals("Renamed by B", b.task("list", "t2")?.title)
    }

    // ---- the hard half: git gave up ----

    @Test
    fun theSameLineEditedBothWays_newerWins_andBothDevicesAgree() = runBlocking {
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        a.writePage(retitle(a.page("list")!!, "t1", "A's title"), 2_000L)
        assertTrue(a.engine.sync().ok)

        b.writePage(retitle(b.page("list")!!, "t1", "B's title"), 9_000L)   // later
        val res = b.engine.sync()
        assertTrue(res.ok)
        assertTrue("nothing was arbitrated", res.conflicts.isNotEmpty())

        a.engine.sync()
        assertEquals("B's title", b.task("list", "t1")?.title)
        assertEquals("the two devices disagree", "B's title", a.task("list", "t1")?.title)
    }

    @Test
    fun theOlderSideLosesEvenWhenItIsTheOneSyncing() = runBlocking {
        // The uncomfortable direction: the device doing the work is the one that gets overruled.
        // If this did not hold, whoever synced last would always win and modified_at would be
        // decoration.
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        a.writePage(retitle(a.page("list")!!, "t1", "A wrote this later"), 9_000L)
        assertTrue(a.engine.sync().ok)

        b.writePage(retitle(b.page("list")!!, "t1", "B wrote this earlier"), 2_000L)
        assertTrue(b.engine.sync().ok)

        assertEquals("A wrote this later", b.task("list", "t1")?.title)
    }

    @Test
    fun anEditBeatsADelete() = runBlocking {
        // An extra task is a nuisance; a task deleted out from under someone's notes is data loss.
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        a.store.writePage(
            PageDoc("doomed", NodeType.TASK, "list", null, null, Instant.ofEpochMilli(2_000L), "a",
                listOf(Prose("A wrote something here")))
        )
        assertTrue(a.engine.sync().ok)
        b.engine.sync()

        // A deletes the page; B, offline, writes into it.
        a.store.deletePage("doomed")
        b.store.writePage(
            PageDoc("doomed", NodeType.TASK, "list", null, null, Instant.ofEpochMilli(3_000L), "b",
                listOf(Prose("B added a second line")))
        )
        assertTrue(a.engine.sync().ok)
        assertTrue(b.engine.sync().ok)
        a.engine.sync()

        assertTrue("the delete won and took content with it", b.store.pageFile("doomed").exists())
        assertTrue(a.store.pageFile("doomed").exists())
    }

    @Test
    fun twoOfflineFocusSessionsBothSurvive() = runBlocking {
        // Append-only: these devices are not disagreeing, they each did something.
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        a.store.appendFocus("s-a\tt1\t1000\t2000\t1500\t1500\t1", "2026-08")
        assertTrue(a.engine.sync().ok)

        b.store.appendFocus("s-b\tt1\t1100\t2100\t1500\t1500\t1", "2026-08")
        assertTrue(b.engine.sync().ok)
        a.engine.sync()

        listOf(a, b).forEach { d ->
            val ids = d.store.readFocus().map { it.substringBefore('\t') }.toSet()
            assertTrue("${d.name} lost a session: $ids", ids.containsAll(setOf("s-a", "s-b")))
        }
    }

    @Test
    fun clockSkewIsBrokenTheSameWayOnBothDevices() = runBlocking {
        // Identical timestamps, so the tiebreak decides. It must decide *the same way* on both
        // machines without them talking, or they diverge permanently.
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        a.writePage(retitle(a.page("list")!!, "t1", "A"), 5_000L)
        assertTrue(a.engine.sync().ok)
        b.writePage(retitle(b.page("list")!!, "t1", "B"), 5_000L)
        assertTrue(b.engine.sync().ok)
        a.engine.sync()

        assertEquals(a.task("list", "t1")?.title, b.task("list", "t1")?.title)
    }

    // ---- convergence overall ----

    @Test
    fun aRoundOfEditsFromBothSidesConverges() = runBlocking {
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        repeat(4) { i ->
            a.writePage(retitle(a.page("list")!!, "t1", "A$i"), 2_000L + i * 10)
            a.engine.sync()
            b.engine.sync()
            b.writePage(retitle(b.page("list")!!, "t2", "B$i"), 3_000L + i * 10)
            b.engine.sync()
            a.engine.sync()
        }
        a.engine.sync(); b.engine.sync(); a.engine.sync()

        assertEquals(
            PageCodec.encode(a.page("list")!!.copy(device = null)),
            PageCodec.encode(b.page("list")!!.copy(device = null)),
        )
        assertEquals("A3", a.task("list", "t1")?.title)
        assertEquals("B3", a.task("list", "t2")?.title)
    }

    @Test
    fun theIndexFollowsWhatCameDownTheWire() = runBlocking {
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        a.writePage(retitle(a.page("list")!!, "t1", "Arrived from A"), 2_000L)
        assertTrue(a.engine.sync().ok)
        assertTrue(b.engine.sync().ok)

        val onB = WorkspaceReconciler.read(b.store, 0L)
        assertEquals("Arrived from A", onB.nodes.single { it.id == "t1" }.title)
    }

    @Test
    fun offlineIsNotAnError() = runBlocking {
        // No remote at all. Commits accumulate; that is git's normal state, not a failure.
        val solo = File(root, "solo")
        val store = WorkspaceStore(solo, "solo").also { it.scaffold("Solo", 1L) }
        val repo = GitRepo(solo, branch)
        repo.init().close()
        val engine = SyncEngine(store, Indexer(db), repo, "solo")

        store.writePage(
            PageDoc("p", NodeType.LIST, null, "Local only", null, Instant.ofEpochMilli(1L), "solo", emptyList())
        )
        val res = engine.sync()
        assertTrue("a workspace with no remote reported an error: ${res.error}", res.ok)
        assertTrue(res.committed)
    }

    @Test
    fun aRefusedPushIsNotReportedAsASuccessfulSync() = runBlocking {
        // The bug: JGit does not throw when a remote refuses a push. call() returns a status per
        // ref, and the old code discarded it — `runCatching { push(); true }` — so a refusal was
        // indistinguishable from a push that worked. Sync reported clean while local commits piled
        // up behind a remote that had never heard of them: the one failure mode where the signal
        // that something is wrong is the signal being suppressed.
        //
        // The refusal is injected rather than provoked. A real one needs a server that refuses —
        // a protected branch, a token without write access — and JGit's own ReceivePack, which is
        // what a local remote runs, implements none of those (denyCurrentBranch is a C-git
        // feature). What is being tested is the engine's reading of the answer, so the answer is
        // what is substituted.
        val dev = File(root, "refused")
        val store = WorkspaceStore(dev, "refused").also { it.scaffold("Refused", 1L) }
        val refusing = object : GitRepo(dev, branch) {
            override fun push(git: Git, creds: org.eclipse.jgit.transport.CredentialsProvider?) =
                PushOutcome(accepted = false, retryable = false, reason = "protected branch")
        }
        refusing.init().use { g ->
            refusing.commitAll(g, "first", "Yantra", "y@napkin.ie")
            refusing.addRemote(g, origin.toURI().toString())
        }
        val engine = SyncEngine(store, Indexer(db), refusing, "refused")

        store.writePage(
            PageDoc("p", NodeType.LIST, null, "Never lands", null, Instant.ofEpochMilli(2L), "refused", emptyList())
        )
        val res = engine.sync()

        assertTrue("a refused push reported a clean sync", !res.ok)
        assertTrue("the reason was dropped: ${res.error}", res.error!!.contains("protected branch"))
        // Local work is untouched — the whole reason a refusal is reported rather than thrown.
        assertTrue(res.committed)
        assertTrue(store.pageFile("p").exists())
    }

    private fun retitle(page: PageDoc, taskId: String, title: String) = page.copy(
        blocks = page.blocks.map { if (it is TaskRef && it.id == taskId) it.copy(title = title) else it }
    )
}
