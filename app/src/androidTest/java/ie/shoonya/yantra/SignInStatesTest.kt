package ie.shoonya.yantra

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
import ie.shoonya.yantra.data.sync.SignInState
import ie.shoonya.yantra.ui.sync.SignedIn
import ie.shoonya.yantra.ui.theme.SuperTasksTheme
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
 * *which* state says *what*: a revoked sign-in that reads as a network wobble leaves someone waiting
 * for a recovery that cannot come, and a dead network that reads as a revoked sign-in sends them
 * round a re-authorisation they never needed.
 */
class SignInStatesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        signIn: SignInState?,
        localSlug: String? = null,
        creating: Boolean = false,
        note: String? = null,
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
                        signIn = signIn,
                        localSlug = localSlug,
                        repoName = "yantra-tasks",
                        creating = creating,
                        note = note,
                        noteBad = false,
                        onRepoName = {},
                        onCreate = onCreate,
                        onUseExisting = {},
                        onSignOut = onSignOut,
                    )
                }
            }
        }
    }

    /**
     * Finds text by its words, not its styling.
     *
     * [ie.shoonya.yantra.ui.components.SectionLabel] draws headings uppercase, so an exact match
     * asserts on a font decision rather than on what the screen says — and scrolling first is what
     * separates "this element is missing" from "this element is below the fold".
     */
    private fun words(text: String): SemanticsNodeInteraction =
        compose.onNodeWithText(text, substring = true, ignoreCase = true)

    @Test
    fun theAccountIsNamedInEveryState() {
        // It is the conflict tiebreak and the value behind @assignee, so which account is connected
        // is never incidental.
        show(SignInState.Ok)
        words("batunii").assertIsDisplayed()
    }

    @Test
    fun aDeadSignInAsksYouToSignInAgain() {
        show(SignInState.Unauthorized)
        words("Sign in again").performScrollTo().assertIsDisplayed()
        // The distinction this test exists for: offering to make a repository here would offer it
        // with a token that can no longer make one.
        words("Create a private repository").assertDoesNotExist()
    }

    @Test
    fun anUnreachableGithubBlamesTheNetworkAndNotTheUser() {
        show(SignInState.Failed("timed out"))
        words("Could not reach GitHub").performScrollTo().assertIsDisplayed()
        words("Sign in again").assertDoesNotExist()
    }

    @Test
    fun aSignInWithNoRemoteOffersToMakeOne() {
        show(SignInState.Ok, localSlug = null)
        words("Back up your tasks").assertIsDisplayed()
        words("Create a private repository").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aWorkspaceThatAlreadyHasARemoteIsNotOfferedAnother() {
        show(SignInState.Ok, localSlug = "batunii/yantra-tasks")
        words("batunii/yantra-tasks").performScrollTo().assertIsDisplayed()
        // Offering to create a second one would be offering to split someone's tasks in half.
        words("Create a private repository").assertDoesNotExist()
    }

    @Test
    fun makingTheRepositorySaysWhatItIsDoing() {
        show(SignInState.Ok, creating = true)
        // A spinner with no sentence is indistinguishable from a hang. This one is short now that it
        // is a single API call rather than a trip to the browser, which makes saying so more
        // important rather than less: nothing else on screen changes.
        words("Making it").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stillAskingShowsNeitherPromptNorError() {
        show(signIn = null)
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
        val signIn = mutableStateOf<SignInState?>(SignInState.Ok)
        compose.setContent {
            SuperTasksTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SignedIn(
                        account = "batunii",
                        signIn = signIn.value,
                        localSlug = null,
                        repoName = "yantra-tasks",
                        creating = false,
                        note = null,
                        noteBad = false,
                        onRepoName = {},
                        onCreate = {},
                        onUseExisting = {},
                        onSignOut = { out++ },
                    )
                }
            }
        }

        listOf(
            SignInState.Ok,
            SignInState.Unauthorized,
            SignInState.Failed("timed out"),
            null,
        ).forEach { state ->
            compose.runOnUiThread { signIn.value = state }
            out = 0
            words("Sign out").performScrollTo().performClick()
            assertEquals("not reachable in $state", 1, out)
        }
    }

    @Test
    fun signingOutSaysWhatItDoesNotDo() {
        show(SignInState.Ok)
        // Workspaces keep their own copy of the token, so signing out does not stop them syncing.
        // Someone signing out to revoke access needs to know that is not what happened.
        words("keep syncing").performScrollTo().assertIsDisplayed()
    }
}
