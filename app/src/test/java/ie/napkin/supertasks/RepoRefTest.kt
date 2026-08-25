package ie.napkin.supertasks

import ie.napkin.supertasks.data.sync.RepoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What people actually paste.
 *
 * Worth being thorough about rather than clever: pointing a workspace at the wrong repository is
 * not a mistake that announces itself, so anything ambiguous is refused instead of guessed.
 */
class RepoRefTest {

    private fun ref(s: String) = RepoRef.parse(s)

    @Test
    fun `the shapes people paste all work`() {
        val expected = RepoRef("batunii", "YANTRA")
        listOf(
            "https://github.com/batunii/YANTRA",          // copied from the address bar
            "https://github.com/batunii/YANTRA/",         // with the trailing slash
            "https://github.com/batunii/YANTRA.git",      // the clone button
            "git@github.com:batunii/YANTRA.git",          // the SSH remote
            "git@github.com:batunii/YANTRA",
            "batunii/YANTRA",                             // typed from memory
            "  batunii/YANTRA  ",                         // pasted with whitespace
        ).forEach { assertEquals("failed on: $it", expected, ref(it)) }
    }

    @Test
    fun `anything ambiguous is refused rather than guessed`() {
        listOf(
            "",
            "YANTRA",                                     // no owner
            "https://github.com/batunii",                 // a user, not a repo
            "https://github.com/batunii/YANTRA/issues/4",  // a page inside the repo
            "not a url at all",
        ).forEach { assertNull("should not have parsed: $it", ref(it)) }
    }

    @Test
    fun `a deep link is refused rather than truncated to its repo`() {
        // Tempting to take the first two path segments, but then a pasted issue link would silently
        // link the workspace and the user would never know which of the two they had asked for.
        assertNull(ref("https://github.com/batunii/YANTRA/tree/main/app"))
    }

    @Test
    fun `the clone url is rebuilt canonically`() {
        assertEquals(
            "https://github.com/batunii/YANTRA.git",
            ref("git@github.com:batunii/YANTRA.git")!!.httpsUrl,
        )
        assertEquals("batunii/YANTRA", ref("batunii/YANTRA")!!.slug)
    }

    @Test
    fun `a non-github host keeps its host`() {
        // Nothing here is GitHub-specific except the API check. Self-hosted git over HTTPS is a
        // reasonable thing to want, and the parser should not be the reason it cannot work.
        val parsed = ref("https://git.example.com/team/tasks.git")!!
        assertEquals(RepoRef("team", "tasks", "git.example.com"), parsed)
        // The host has to survive into the clone URL. Assuming github.com here would point the
        // workspace at a github.com repository of the same name — which either fails confusingly or,
        // far worse, succeeds somewhere the user did not mean.
        assertEquals("https://git.example.com/team/tasks.git", parsed.httpsUrl)
    }

    @Test
    fun `an ssh remote keeps its host too`() {
        assertEquals("git.example.com", ref("git@git.example.com:team/tasks.git")!!.host)
        assertEquals("github.com", ref("git@github.com:batunii/YANTRA.git")!!.host)
    }

    @Test
    fun `a bare slug means github`() {
        // There is no host to read, so the default is the only sensible reading of it.
        assertEquals("https://github.com/batunii/YANTRA.git", ref("batunii/YANTRA")!!.httpsUrl)
    }
}
