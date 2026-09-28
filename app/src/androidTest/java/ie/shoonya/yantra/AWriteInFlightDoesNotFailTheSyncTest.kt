package ie.shoonya.yantra

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.sync.GitRepo
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A sync does not fail because somebody was typing while it ran.
 *
 * Every file in a workspace is written the same way — bytes to `<name>.tmp`, then a rename onto the
 * real name — so a reader sees the old file or the new one and never half of one. The rename works.
 * What it does not survive is somebody **walking the directory** in the moment between the two, and
 * that is exactly what `git add .` does:
 *
 *     java.io.FileNotFoundException: …/pages/fe8741a9-….md.tmp: ENOENT
 *         at org.eclipse.jgit.api.AddCommand.call(AddCommand.java:271)
 *
 * The whole sync died on that, caught in the dogfood log on a real phone. There is no lock to take:
 * the writer and the sync engine hold different mutexes deliberately, because making a keystroke
 * wait for a push is the worse trade. So the transient name is declared transient, to git, in the
 * repository.
 *
 * The race itself cannot be tested without being flaky, so these test the mechanism it turns on: a
 * `.tmp` left in the tree is invisible to git, and every workspace grows the rule — including the
 * ones made before it existed, which is all of them.
 */
@RunWith(AndroidJUnit4::class)
class AWriteInFlightDoesNotFailTheSyncTest {

    private lateinit var root: File
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "inflight-${System.nanoTime()}").apply { mkdirs() }
    }

    private fun repo(dir: File): Git = Git.init().setDirectory(dir).setInitialBranch("yantra-tasks").call()

    @Test
    fun `a half-written file is not something git sees`() {
        WorkspaceStore(root).scaffold("Test", 1L)
        val git = repo(root)
        GitRepo(root, "yantra-tasks").commitAll(git, "first", "Yantra", "yantra@shoonya.ie")

        // The state the walk found: a page mid-rename. Whether it survives to be opened is the
        // race; whether git tries at all is what we control.
        File(File(root, "pages"), "fe8741a9-421c-4c86-8abf-0ac347601396.md.tmp").writeText("half a page")

        val wrote = GitRepo(root, "yantra-tasks").commitAll(git, "while typing", "Yantra", "yantra@shoonya.ie")

        assertFalse("a transient file must not be a commit", wrote)
        assertTrue("a transient file must not dirty the tree", git.status().call().isClean)
    }

    /** And it never reaches a commit even if the rename failed and left it behind for good. */
    @Test
    fun `a stranded temp file is never committed`() {
        WorkspaceStore(root).scaffold("Test", 1L)
        val git = repo(root)
        File(File(root, "pages"), "stranded.md.tmp").writeText("x")
        File(File(root, "pages"), "real.md").writeText("y")

        GitRepo(root, "yantra-tasks").commitAll(git, "first", "Yantra", "yantra@shoonya.ie")

        val tracked = git.repository.readDirCache().let { c ->
            (0 until c.entryCount).map { c.getEntry(it).pathString }
        }
        assertTrue("the real page should be in: $tracked", tracked.any { it.endsWith("real.md") })
        assertTrue("a .tmp reached the repository: $tracked", tracked.none { it.endsWith(".tmp") })
    }

    /**
     * Every workspace on every device was made before this rule existed, so scaffolding it is not
     * enough — an existing directory has to grow it on open.
     */
    @Test
    fun `an existing workspace grows the rule and keeps what is already there`() {
        val store = WorkspaceStore(root).also { it.scaffold("Test", 1L) }
        File(root, ".gitignore").writeText("# mine\nnotes-to-self/\n")

        assertTrue("it should have written", store.ensureGitignore())
        val lines = File(root, ".gitignore").readLines()
        assertTrue("the user's own rules must survive: $lines", "notes-to-self/" in lines)
        assertTrue("*.tmp" in lines)

        // And it is idempotent — open happens on every launch.
        assertFalse("nothing left to do", store.ensureGitignore())
        assertEquals(lines, File(root, ".gitignore").readLines())
    }
}
