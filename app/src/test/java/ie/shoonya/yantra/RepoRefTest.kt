package ie.shoonya.yantra

import ie.shoonya.yantra.data.sync.RepoRef
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
            // What the address bar actually holds on the repository's own page. github.com adds
            // this, so it is not an unusual paste — it is the ordinary one.
            "https://github.com/batunii/YANTRA?tab=readme-ov-file",
            "https://github.com/batunii/YANTRA#readme",
        ).forEach { assertEquals("failed on: $it", expected, ref(it)) }
    }

    /**
     * A link from anywhere inside the repository names that repository.
     *
     * This used to be refused, on the reasoning that truncating an issue link would silently link
     * something nobody asked for. But `/batunii/YANTRA/issues/4` cannot mean any repository other
     * than `batunii/YANTRA` — there is no ambiguity to protect against, and the refusal simply made
     * linking fail for anyone who copied the address while looking at the thing they wanted to
     * link, which is where you are when you decide to link it.
     *
     * What protects intent is showing the `owner/name` this resolved to before acting on it, which
     * the screen now does.
     */
    @Test
    fun `a link from inside the repository still names the repository`() {
        val expected = RepoRef("batunii", "YANTRA")
        listOf(
            "https://github.com/batunii/YANTRA/issues/4",
            "https://github.com/batunii/YANTRA/pull/11",
            "https://github.com/batunii/YANTRA/tree/main/app",
            "https://github.com/batunii/YANTRA/blob/main/README.md#L10",
            "https://github.com/batunii/YANTRA/releases/tag/v0.4.1",
            "https://github.com/batunii/YANTRA/settings/access",
        ).forEach { assertEquals("failed on: $it", expected, ref(it)) }
    }

    @Test
    fun `anything ambiguous is refused rather than guessed`() {
        listOf(
            "",
            "YANTRA",                                     // no owner
            "https://github.com/batunii",                 // a user, not a repo
            "not a url at all",
        ).forEach { assertNull("should not have parsed: $it", ref(it)) }
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
