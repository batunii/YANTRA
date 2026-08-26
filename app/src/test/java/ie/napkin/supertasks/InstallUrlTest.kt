package ie.napkin.supertasks

import ie.napkin.supertasks.data.sync.GitHubAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The install link, which is the last thing standing between signing in and a working workspace.
 *
 * A GitHub App user token authenticates perfectly and can see nothing at all until the App is
 * installed, so this link is not a convenience — it is the step that makes the sign-in mean
 * anything. Every tap it costs is one someone can abandon on.
 */
class InstallUrlTest {

    @Test
    fun `an account id aims the link, skipping the chooser`() {
        val url = GitHubAuth.installUrl(1234L)
        // `installations/new` alone lands on "which account?", whose only real answer is the account
        // that just signed in. The permissions page is the one with the Install button on it.
        assertTrue("not the targeted form: $url", url.endsWith("/installations/new/permissions?suggested_target_id=1234"))
        assertTrue(url.contains("/apps/${GitHubAuth.APP_SLUG}/"))
    }

    @Test
    fun `no id falls back to the chooser rather than to a broken link`() {
        // Someone signed in before the id was recorded still has to be able to install. A guessed
        // id would send them to another account's installation page, which is worse than a chooser.
        assertEquals(
            "https://github.com/apps/${GitHubAuth.APP_SLUG}/installations/new",
            GitHubAuth.installUrl(null),
        )
    }
}
