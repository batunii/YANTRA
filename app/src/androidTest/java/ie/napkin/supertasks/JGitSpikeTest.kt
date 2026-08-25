package ie.napkin.supertasks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 0 of GIT_WORKSPACES_PLAN.md: does JGit actually work on Android?
 *
 * The whole plan rests on this and the original document asserted it ("pure JVM, sufficient")
 * without evidence. JGit is built for desktop JVMs — it leans on `java.nio.file`, probes the
 * filesystem for capabilities, and looks for a system git config in places Android does not have.
 * minSdk here is 26, which is the first API level with `java.nio.file` at all.
 *
 * This proves the local object/pack layer and the rebase machinery. Transport to GitHub over HTTPS
 * is a separate question and gets its own test below, unauthenticated, so it can run in CI without
 * a token.
 */
@RunWith(AndroidJUnit4::class)
class JGitSpikeTest {

    private lateinit var root: File

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "jgit-spike-${System.nanoTime()}").apply { mkdirs() }
    }

    /**
     * A bare repo with one commit on `main`.
     *
     * Seeding matters: a brand-new bare repo has no HEAD, and JGit refuses to clone that outright
     * ("Remote branch 'HEAD' not found"). Real git only warns. So scaffolding a fresh workspace
     * cannot be "clone then commit" — it has to be init-locally, add remote, push, which is what
     * this does and what the app will have to do too.
     */
    private fun bareRepo(name: String): File {
        val bare = File(root, name)
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(File(root, "$name-seed")).setInitialBranch("main").call().use { g ->
            g.commitFile(".yantra/manifest.json", "{}", "scaffold")
            g.remoteAdd().setName("origin").setUri(URIish(bare.toURI().toString())).call()
            g.push().setRemote("origin").add("main").call()
        }
        return bare
    }

    private fun clone(from: File, into: String): Git =
        Git.cloneRepository()
            .setURI(from.toURI().toString())
            .setDirectory(File(root, into))
            .call()

    private fun Git.commitFile(name: String, text: String, msg: String) {
        File(repository.workTree, name).apply { parentFile?.mkdirs() }.writeText(text)
        add().addFilepattern(name).call()
        commit()
            .setMessage(msg)
            .setAuthor("Yantra", "yantra@napkin.ie")
            .setCommitter("Yantra", "yantra@napkin.ie")
            .call()
    }

    private fun Repository.fileText(name: String) = File(workTree, name).readText()

    // ---- the local half: everything except the network ----

    @Test
    fun initCloneCommitPush_worksOnDevice() {
        val bare = bareRepo("origin.git")
        clone(bare, "a").use { a ->
            a.commitFile("hello.md", "one", "first")
            a.push().call()
        }
        // A second clone sees it — which means the pack was written and read back correctly.
        clone(bare, "b").use { b ->
            assertEquals("one", b.repository.fileText("hello.md"))
        }
    }

    @Test
    fun rebaseOntoRemote_convergesWithoutConflict() {
        // The plan's sync loop in one test: two clones edit different files, the second rebases
        // its local commit onto the first's and pushes. Nothing should be lost.
        val bare = bareRepo("origin.git")
        val a = clone(bare, "a")
        val b = clone(bare, "b")

        a.commitFile("from-a.md", "a", "a edits")
        a.push().call()

        b.commitFile("from-b.md", "b", "b edits")
        b.fetch().call()
        val res = b.rebase().setUpstream("origin/main").call()
        assertTrue("rebase status was ${res.status}", res.status.isSuccessful)
        b.push().call()

        clone(bare, "verify").use { v ->
            assertEquals("a", v.repository.fileText("from-a.md"))
            assertEquals("b", v.repository.fileText("from-b.md"))
        }
        a.close(); b.close()
    }

    @Test
    fun sameFileConflict_isDetectableAndResolvable() {
        // The case LWW exists for. We only need JGit to *report* the conflict and let us pick a
        // side and continue — the policy itself is ours (newer modified_at wins).
        val bare = bareRepo("origin.git")
        clone(bare, "seed").use { it.commitFile("task.md", "original", "base"); it.push().call() }

        val a = clone(bare, "a")
        val b = clone(bare, "b")

        a.commitFile("task.md", "from A", "a edits")
        a.push().call()

        b.commitFile("task.md", "from B", "b edits")
        b.fetch().call()
        val res = b.rebase().setUpstream("origin/main").call()
        assertTrue("expected a conflict, got ${res.status}", !res.status.isSuccessful)
        // RebaseResult.getConflicts() comes back empty here even though the rebase stopped on one.
        // The reliable source is the working-tree status, which is what the LWW resolver should
        // read when it needs to know which files to arbitrate.
        val conflicting = b.status().call().conflicting
        assertTrue("no conflicting paths in status: $conflicting", conflicting.contains("task.md"))

        // Take our side wholesale, which is what the LWW resolver will do.
        File(b.repository.workTree, "task.md").writeText("from B")
        b.add().addFilepattern("task.md").call()
        val cont = b.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.CONTINUE).call()
        assertTrue("continue failed: ${cont.status}", cont.status.isSuccessful)
        b.push().call()

        clone(bare, "verify").use { v ->
            assertEquals("from B", v.repository.fileText("task.md"))
        }
        a.close(); b.close()
    }

    @Test
    fun orphanBranch_isDisjointFromMain() {
        // §0: tasks live on an orphan branch so they never appear in a code diff and never trigger
        // a branch-filtered CI. Prove JGit can make one and that it shares no history.
        val bare = bareRepo("origin.git")
        clone(bare, "repo").use { g ->
            g.commitFile("README.md", "the code", "code")
            g.push().call()

            g.checkout().setOrphan(true).setName("yantra-tasks").call()
            // An orphan checkout carries the index and work tree over. Clear both, or the code
            // lands in the first task commit — which is the whole thing this branch exists to avoid.
            val tracked = g.repository.readDirCache()
                .let { dc -> (0 until dc.entryCount).map { dc.getEntry(it).pathString } }
            if (tracked.isNotEmpty()) {
                g.rm().setCached(true).apply { tracked.forEach { addFilepattern(it) } }.call()
            }
            g.repository.workTree.listFiles()
                ?.filter { it.name != ".git" }
                ?.forEach { it.deleteRecursively() }
            g.commitFile("pages/first.md", "a task", "scaffold")
            g.push().setRemote("origin").add("yantra-tasks").call()

            val tasksHead = g.repository.resolve("refs/heads/yantra-tasks")
            val mainHead = g.repository.resolve("refs/heads/main")
            assertTrue(tasksHead != null && mainHead != null)

            // Disjoint: no merge base between the two branches.
            org.eclipse.jgit.revwalk.RevWalk(g.repository).use { walk ->
                walk.revFilter = org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE
                walk.markStart(walk.parseCommit(tasksHead))
                walk.markStart(walk.parseCommit(mainHead))
                assertNull("branches share history", walk.next())
            }
        }
        // And a fresh clone of the task branch never sees the code.
        Git.cloneRepository()
            .setURI(bare.toURI().toString())
            .setDirectory(File(root, "tasks-only"))
            .setBranch("yantra-tasks")
            .call().use { t ->
                assertTrue(File(t.repository.workTree, "pages/first.md").exists())
                assertTrue("code leaked onto the task branch",
                    !File(t.repository.workTree, "README.md").exists())
            }
    }

    // ---- the network half ----

    @Test
    fun httpsTransportToGitHub_works() {
        // Unauthenticated ls-remote against a public repo. Proves JGit's HTTP transport and TLS
        // work on Android without needing a token in CI. Auth is Phase 4.
        val refs = Git.lsRemoteRepository()
            .setRemote(URIish("https://github.com/batunii/YANTRA.git").toString())
            .setHeads(true)
            .call()
        assertTrue("no refs returned from github", refs.isNotEmpty())
    }
}
