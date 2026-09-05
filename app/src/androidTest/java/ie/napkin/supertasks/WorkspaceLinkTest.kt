package ie.napkin.supertasks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.format.PageDoc
import ie.napkin.supertasks.data.format.TaskRef
import ie.napkin.supertasks.data.sync.GitHubApi
import ie.napkin.supertasks.data.sync.GitRepo
import ie.napkin.supertasks.data.sync.LinkResult
import ie.napkin.supertasks.data.sync.RepoCheck
import ie.napkin.supertasks.data.sync.RepoRef
import ie.napkin.supertasks.data.sync.WorkspaceLinker
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Attaching a workspace to a repository, against real git.
 *
 * Two of these guard against destroying somebody's tasks, which is why they exist at all. Running
 * the start-a-branch path over a directory that already holds tasks would clear the working tree to
 * make room for an empty workspace, and rebasing two populated task histories with no common
 * ancestor would interleave them into something nobody wrote. Both are silent: the app would look
 * like it had worked.
 *
 * GitHub itself is stubbed. Everything below the two API questions is real git against real
 * repositories on disk — a fake remote is a directory, and that is the part worth testing.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceLinkTest {

    private lateinit var root: File
    private val now = 1_787_000_000_000L
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Answers the two questions the linker asks before it touches git. */
    private class StubApi(
        private val login: String? = "batunii",
        private val canPush: Boolean = true,
        private val found: Boolean = true,
    ) : GitHubApi() {
        override fun viewer(token: String): String? = login
        override fun check(ref: RepoRef, token: String): RepoCheck =
            if (!found) RepoCheck.NotFound else RepoCheck.Ok(ref, canPush, "main")
    }

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "link-${System.nanoTime()}").apply { mkdirs() }
    }

    private fun dir(name: String) = File(root, name).apply { mkdirs() }

    /** A bare repo standing in for GitHub. */
    private fun remote(): File =
        dir("remote.git").also { Git.init().setBare(true).setDirectory(it).call().close() }

    /**
     * A local repo pointed at [at], with the remote added through [GitRepo] so the narrowed refspec
     * is the one under test rather than one the test wrote itself.
     */
    private fun local(name: String, at: File): File {
        val d = dir(name)
        val repo = GitRepo(d, "yantra-tasks")
        repo.init().use { git -> repo.addRemote(git, at.absolutePath) }
        return d
    }

    private fun workspace(d: File, title: String, task: String): WorkspaceStore =
        WorkspaceStore(d, "ws").also { store ->
            if (!store.exists) store.scaffold(title, now)
            store.writePage(
                PageDoc(
                    id = "list-$task", type = NodeType.LIST, parent = null, title = title,
                    modifiedAt = Instant.ofEpochMilli(now), device = "test",
                    blocks = listOf(TaskRef(id = "task-$task", title = task)),
                )
            )
        }

    /** Puts a populated workspace on the remote's task branch, as another device would have. */
    private fun seedRemote(at: File, title: String, task: String) {
        val d = local("seeder-$task", at)
        workspace(d, title, task)
        val repo = GitRepo(d, "yantra-tasks")
        repo.open().use { git ->
            repo.commitAll(git, "from another device", "Yantra", "yantra@napkin.ie")
            repo.push(git, null)
        }
    }

    private fun titlesOn(at: File): List<String> {
        val clone = dir("read-${System.nanoTime()}")
        Git.cloneRepository().setURI(at.absolutePath).setDirectory(clone)
            .setBranch("yantra-tasks").call().close()
        return clone.resolve("pages").listFiles().orEmpty().map { it.readText() }
    }

    @Test
    fun theRemoteIsNarrowedToTheTaskBranch() {
        val d = local("narrow", remote())
        Git.open(d).use { git ->
            // Without this, every later fetch would drag down the whole repository — all the code,
            // all its history — to reach a branch that shares none of it. The linker is careful to
            // fetch one refspec by hand; the first background sync afterwards would undo that.
            assertEquals(
                "+refs/heads/yantra-tasks:refs/remotes/origin/yantra-tasks",
                git.repository.config.getString("remote", "origin", "fetch"),
            )
        }
    }

    @Test
    fun attachingSendsTheTasksYouAlreadyHave() {
        val at = remote()
        val d = local("mine", at)
        val store = workspace(d, "Personal", "feed the cat")

        val result = WorkspaceLinker(StubApi()).attach(store, "batunii/tasks", "tok")

        assertTrue("refused: $result", result is LinkResult.Ok)
        assertEquals("batunii", (result as LinkResult.Ok).login)
        assertTrue(titlesOn(at).any { it.contains("feed the cat") })
    }

    @Test
    fun attachingTwiceIsNotAnError() {
        // Found the hard way, on a real phone: closing the browser and the app being brought forward
        // are two resumes, so attach ran twice. The second one saw its own tasks on the remote and
        // told the user to add their own repository as a separate workspace — a success and a failure
        // on screen at once, and an instruction that would have split their tasks in half.
        val at = remote()
        val d = local("mine", at)
        val store = workspace(d, "Personal", "feed the cat")
        val linker = WorkspaceLinker(StubApi())

        assertTrue(linker.attach(store, "batunii/tasks", "tok") is LinkResult.Ok)
        val again = linker.attach(store, "batunii/tasks", "tok")

        assertTrue("second attach refused: $again", again is LinkResult.Ok)
        assertTrue(titlesOn(at).any { it.contains("feed the cat") })
    }

    @Test
    fun attachingAsksAboutARemoteThatAlreadyHasTasks() {
        val at = remote()
        seedRemote(at, "Theirs", "their task")

        val d = local("mine", at)
        val store = workspace(d, "Personal", "my task")
        val result = WorkspaceLinker(StubApi()).attach(store, "batunii/tasks", "tok")

        // Two task histories with no common ancestor. They may be two real sets of work, or one
        // set and a phone that has been reinstalled since it last saw it — identical from here and
        // opposite in what they want. So this reports the situation rather than deciding it.
        //
        // It used to be a refusal telling the user to add their own repository as a second
        // workspace, which is how someone ends up with two workspaces both called Personal.
        assertTrue("should have asked: $result", result is LinkResult.HasTasks)

        // The half that matters: nothing of the user's was touched on the way to saying no.
        assertTrue(titlesOn(at).none { it.contains("my task") })
        assertTrue(
            "the local tasks were disturbed",
            d.resolve("pages").listFiles().orEmpty().any { it.readText().contains("my task") },
        )
    }

    @Test
    fun linkingAnEmptyRepoStartsTheBranchAndSeedsItOnce() {
        val at = remote()
        val d = local("fresh", at)
        var seeded = 0

        val result = WorkspaceLinker(StubApi())
            .link(d, "ws", "Project", "batunii/project", "tok") { store ->
                seeded++
                store.writePage(
                    PageDoc(
                        id = "seed", type = NodeType.LIST, parent = null, title = "Project",
                        modifiedAt = Instant.ofEpochMilli(now), device = null, blocks = emptyList(),
                    )
                )
            }

        assertTrue("refused: $result", result is LinkResult.Ok)
        assertFalse((result as LinkResult.Ok).adopted)
        assertEquals(1, seeded)
        // The name reaches the manifest, which is what the derived workspace label reads. Every new
        // workspace was called "Workspace" before this was passed through.
        assertEquals("Project", WorkspaceStore(d, "ws").readManifest()?.name)
        assertNotNull(Git.open(at).use { it.repository.resolve("refs/heads/yantra-tasks") })
    }

    @Test
    fun joiningAnExistingWorkspaceDoesNotSeedIt() {
        val at = remote()
        seedRemote(at, "Shared", "already here")

        val d = local("joiner", at)
        var seeded = 0
        val result = WorkspaceLinker(StubApi()).link(d, "ws", "Ignored", "batunii/p", "tok") { seeded++ }

        assertTrue("refused: $result", result is LinkResult.Ok)
        assertTrue((result as LinkResult.Ok).adopted)
        // Seeding on a device that is joining is what would give every machine its own second Inbox.
        assertEquals(0, seeded)
        // And the name is theirs, not the one this device would have chosen.
        assertEquals("Shared", WorkspaceStore(d, "ws").readManifest()?.name)
        assertTrue(d.resolve("pages").listFiles().orEmpty().any { it.readText().contains("already here") })
    }

    @Test
    fun linkingNeverClearsADirectoryThatIsAlreadyAWorkspace() {
        // The orphan-branch dance clears the working tree, which is exactly right for a repo full of
        // code and exactly wrong when the tree *is* the tasks. Reaching it here would mean deleting
        // a task list to make room for an empty one.
        val at = remote()
        val d = local("populated", at)
        workspace(d, "Personal", "do not delete me")
        GitRepo(d, "yantra-tasks").let { repo ->
            repo.open().use { repo.commitAll(it, "local history", "Yantra", "yantra@napkin.ie") }
        }

        WorkspaceLinker(StubApi()).link(d, "ws", "Project", "batunii/project", "tok")

        assertTrue(
            "the existing tasks were cleared",
            d.resolve("pages").listFiles().orEmpty().any { it.readText().contains("do not delete me") },
        )
    }

    @Test
    fun adoptingTakesTheRemoteAndNothingIsInterleaved() {
        // The reinstall: the repository is yours, and this device has nothing on it worth keeping.
        // Answering the ask with adopt takes the branch as it stands — the same checkout-and-reset
        // that joining a repository has always done, reached from the other door.
        val at = remote()
        seedRemote(at, "Mine", "the task I already had")

        val d = local("mine", at)
        val store = workspace(d, "Personal", "starter content")
        val linker = WorkspaceLinker(StubApi())

        assertTrue(linker.attach(store, "batunii/tasks", "tok") is LinkResult.HasTasks)
        val adopted = linker.attach(store, "batunii/tasks", "tok", adopt = true)

        assertTrue("should have adopted: $adopted", adopted is LinkResult.Ok)
        assertTrue((adopted as LinkResult.Ok).adopted)

        // What was on the remote is now here, and the local content did not survive to be
        // interleaved with it — which is the whole difference between adopting and rebasing.
        val pages = d.resolve("pages").listFiles().orEmpty().map { it.readText() }
        assertTrue("the remote's tasks did not arrive", pages.any { it.contains("the task I already had") })
        assertTrue("local content was merged in rather than replaced", pages.none { it.contains("starter content") })
    }

    @Test
    fun askingChangesNothingSoTheAnswerCanStillBeNo() {
        // The ask has to be free. Someone who is shown the question and backs out must find their
        // tasks exactly as they left them, on both sides.
        val at = remote()
        seedRemote(at, "Theirs", "their task")

        val d = local("mine", at)
        val store = workspace(d, "Personal", "my task")
        val linker = WorkspaceLinker(StubApi())

        repeat(3) { assertTrue(linker.attach(store, "batunii/tasks", "tok") is LinkResult.HasTasks) }

        assertTrue(titlesOn(at).none { it.contains("my task") })
        assertTrue(titlesOn(at).any { it.contains("their task") })
        assertTrue(
            "the local tasks were disturbed by being asked about",
            d.resolve("pages").listFiles().orEmpty().any { it.readText().contains("my task") },
        )
    }

    @Test
    fun readAccessIsRefusedBeforeAnythingIsWritten() {
        val at = remote()
        val d = local("readonly", at)

        val result = WorkspaceLinker(StubApi(canPush = false))
            .link(d, "ws", "Project", "batunii/project", "tok")

        // Finding this out now is worth a great deal: the alternative is a week of local commits
        // that can never leave the device.
        assertTrue(result is LinkResult.Refused)
        assertTrue((result as LinkResult.Refused).reason.contains("cannot push"))
        assertNull(Git.open(at).use { it.repository.resolve("refs/heads/yantra-tasks") })
    }

    @Test
    fun aRejectedTokenAndAMissingRepoReadDifferently() {
        val d = local("bad", remote())

        val noToken = WorkspaceLinker(StubApi(login = null)).link(d, "ws", "P", "a/b", "tok")
        assertTrue((noToken as LinkResult.Refused).reason.contains("token was rejected"))

        val noRepo = WorkspaceLinker(StubApi(found = false)).link(d, "ws", "P", "a/b", "tok")
        assertTrue((noRepo as LinkResult.Refused).reason.contains("does not exist"))

        val nonsense = WorkspaceLinker(StubApi()).link(d, "ws", "P", "not a repo", "tok")
        assertTrue((nonsense as LinkResult.Refused).reason.contains("does not look like"))
    }
}
