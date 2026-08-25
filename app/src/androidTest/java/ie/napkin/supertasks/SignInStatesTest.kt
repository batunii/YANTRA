package ie.napkin.supertasks

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ie.napkin.supertasks.data.sync.InstallState
import ie.napkin.supertasks.ui.sync.SignedIn
import ie.napkin.supertasks.ui.theme.SuperTasksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The signed-in half of the sign-in screen, in every state it can be in.
 *
 * These cannot be reached by hand. Getting a device to "signed in, but the App is not installed
 * anywhere" needs a registered GitHub App, a real account and a deliberately incomplete install — so
 * without this the states ship on reasoning alone, and the one that matters most is the one a brand
 * new user hits first.
 *
 * Asserting on the words rather than on layout, deliberately. What could actually be wrong here is
 * *which* state says *what*: an install prompt that appears when the token is dead sends someone to
 * fix the wrong thing, and a revoked sign-in that reads as "one more step" sends them round a loop
 * that cannot end.
 */
class SignInStatesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        install: InstallState?,
        localSlug: String? = null,
        awaiting: String? = null,
        note: String? = null,
        onInstall: () -> Unit = {},
        onCreate: () -> Unit = {},
        onSignOut: () -> Unit = {},
    ) {
        compose.setContent {
            SuperTasksTheme {
                // Scrollable, like the real screen. Without it everything below the fold counts as
                // not displayed, which reads as a missing element rather than as a viewport.
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SignedIn(
                        account = "batunii",
                        install = install,
                        localSlug = localSlug,
                        repoName = "yantra-tasks",
                        awaiting = awaiting,
                        note = note,
                        noteBad = false,
                        onRepoName = {},
                        onInstall = onInstall,
                        onCreate = onCreate,
                        onSignOut = onSignOut,
                    )
                }
            }
        }
    }

    /**
     * Finds text by its words, not its styling.
     *
     * [ie.napkin.supertasks.ui.components.SectionLabel] draws headings uppercase, so an exact match
     * asserts on a font decision rather than on what the screen says — and scrolling first is what
     * separates "this element is missing" from "this element is below the fold".
     */
    private fun words(text: String): SemanticsNodeInteraction =
        compose.onNodeWithText(text, substring = true, ignoreCase = true)

    @Test
    fun theAccountIsNamedInEveryState() {
        // It is the conflict tiebreak and the value behind @assignee, so which account is connected
        // is never incidental.
        show(InstallState.Installed)
        words("batunii").assertIsDisplayed()
    }

    @Test
    fun aMissingInstallationAsksForOneMoreStepAndNotAnError() {
        show(InstallState.Absent)
        words("One more step").assertIsDisplayed()
        words("Grant access on GitHub").assertIsDisplayed()
        // "All repositories" is named on the screen because choosing the other option is what makes a
        // repo created later invisible, and nothing would explain why.
        words("All repositories").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun grantingAccessLeavesForTheBrowser() {
        var went = 0
        show(InstallState.Absent, onInstall = { went++ })
        words("Grant access on GitHub").performScrollTo().performClick()
        assertEquals(1, went)
    }

    @Test
    fun aDeadSignInAsksYouToSignInAgainRatherThanToInstall() {
        show(InstallState.Unauthorized)
        words("Sign in again").performScrollTo().assertIsDisplayed()
        // The distinction this test exists for: an install prompt here would send someone to grant
        // access with a token that can no longer be used to grant anything.
        words("Grant access on GitHub").assertDoesNotExist()
    }

    @Test
    fun anUnreachableGithubBlamesTheNetworkAndNotTheUser() {
        show(InstallState.Failed("timed out"))
        words("Could not reach GitHub").performScrollTo().assertIsDisplayed()
        words("Grant access on GitHub").assertDoesNotExist()
        words("Sign in again").assertDoesNotExist()
    }

    @Test
    fun anInstalledAppWithNoRemoteOffersToMakeOne() {
        show(InstallState.Installed, localSlug = null)
        words("Back up your tasks").assertIsDisplayed()
        words("Create a private repository").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aWorkspaceThatAlreadyHasARemoteIsNotOfferedAnother() {
        show(InstallState.Installed, localSlug = "batunii/yantra-tasks")
        words("batunii/yantra-tasks").performScrollTo().assertIsDisplayed()
        // Offering to create a second one would be offering to split someone's tasks in half.
        words("Create a private repository").assertDoesNotExist()
    }

    @Test
    fun waitingForTheBrowserSaysWhatItIsWaitingFor() {
        show(InstallState.Installed, awaiting = "yantra-tasks")
        // A spinner with no sentence is indistinguishable from a hang, and this one waits on the user
        // doing something in another app.
        words("Press Create repository on GitHub").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stillAskingShowsNeitherPromptNorError() {
        show(install = null)
        words("One more step").assertDoesNotExist()
        words("Back up your tasks").assertDoesNotExist()
        words("Sign in again").assertDoesNotExist()
        // Signing out has to work even while we are still asking GitHub anything.
        words("Sign out").performScrollTo().assertIsDisplayed()
    }

    /**
     * Signing out has to work in every state, including the broken ones — it is the only way out of
     * a revoked token.
     *
     * Driven from a state holder rather than by calling [show] in a loop, because `setContent` may be
     * called only once per test. Recomposing is also the more honest test: it is exactly what the
     * real screen does when the install check comes back.
     */
    @Test
    fun signingOutIsAlwaysReachable() {
        var out = 0
        val install = mutableStateOf<InstallState?>(InstallState.Installed)
        compose.setContent {
            SuperTasksTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SignedIn(
                        account = "batunii",
                        install = install.value,
                        localSlug = null,
                        repoName = "yantra-tasks",
                        awaiting = null,
                        note = null,
                        noteBad = false,
                        onRepoName = {},
                        onInstall = {},
                        onCreate = {},
                        onSignOut = { out++ },
                    )
                }
            }
        }

        listOf(
            InstallState.Installed,
            InstallState.Absent,
            InstallState.Unauthorized,
            InstallState.Failed("timed out"),
            null,
        ).forEach { state ->
            compose.runOnUiThread { install.value = state }
            out = 0
            words("Sign out").performScrollTo().performClick()
            assertEquals("not reachable in $state", 1, out)
        }
    }

    @Test
    fun signingOutSaysWhatItDoesNotDo() {
        show(InstallState.Installed)
        // Workspaces keep their own copy of the token, so signing out does not stop them syncing.
        // Someone signing out to revoke access needs to know that is not what happened.
        words("keep syncing").performScrollTo().assertIsDisplayed()
    }
}
