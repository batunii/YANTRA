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
import ie.shoonya.yantra.data.sync.GitHubAuth
import ie.shoonya.yantra.data.sync.InstallState
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
        method: GitHubAuth.Method? = GitHubAuth.Method.Full,
        install: InstallState? = null,
        dependents: Int = 0,
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
                        method = method,
                        install = install,
                        dependents = dependents,
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

    /**
     * Signing out says what it costs, in workspaces.
     *
     * This line used to promise the opposite — that workspaces keep syncing — which was true because
     * each one held a copy of the account's token. The copies are gone on purpose, since a copy is
     * exactly the credential that can outlive the sign-in it came from and fail in the dark. What
     * replaces the promise is a number, because "some things will stop" is not something anyone can
     * weigh.
     */
    @Test
    fun signingOutSaysHowMuchStops() {
        show(SignInState.Ok, dependents = 2)
        words("2 workspaces sync through this sign-in").performScrollTo().assertIsDisplayed()
        words("will stop").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun signingOutWithNothingDependingOnItSaysSo() {
        show(SignInState.Ok, dependents = 0)
        words("Nothing on this device is syncing through this sign-in")
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * Signed in, and able to see nothing: the state the restricted method can be in and the other
     * cannot.
     *
     * It is the most confusing state this app produces — `/user` answers, the account is named, and
     * every repository request comes back empty — so the screen says the one thing that fixes it and
     * does not offer a repository that would be made into a void.
     */
    @Test
    fun anAppInstalledNowhereAsksForRepositoriesRatherThanOfferingOne() {
        show(SignInState.Ok, method = GitHubAuth.Method.Restricted, install = InstallState.Absent)
        words("One more step").performScrollTo().assertIsDisplayed()
        words("Choose repositories on GitHub").performScrollTo().assertIsDisplayed()
        words("Create a private repository").assertDoesNotExist()
    }

    /**
     * The restricted method cannot create a repository at all, and says so on the button.
     *
     * There is no GitHub App permission for making one in a personal account. A button labelled
     * "Create a private repository" that opened a browser would be the app describing a trip out as
     * though it were one tap — which is the kind of small lie that makes everything else on the
     * screen less believable.
     */
    @Test
    fun theRestrictedMethodOffersGithubsFormRatherThanAButtonThatCannotWork() {
        show(
            SignInState.Ok,
            method = GitHubAuth.Method.Restricted,
            install = InstallState.Installed,
        )
        words("Make it on GitHub").performScrollTo().assertIsDisplayed()
        words("Create a private repository").assertDoesNotExist()
        // The second half of making one under this method, and the reason it is worth a sentence.
        words("Use a repository I already have").performScrollTo().assertIsDisplayed()
    }

    /** Which bargain is in force, said where the account is named. */
    @Test
    fun theKindOfAccessIsNamedBesideTheAccount() {
        show(SignInState.Ok, method = GitHubAuth.Method.Restricted, install = InstallState.Installed)
        words("Only the ones I pick").performScrollTo().assertIsDisplayed()
    }
}
