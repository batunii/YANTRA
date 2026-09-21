package ie.shoonya.yantra

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.sync.Credentials
import ie.shoonya.yantra.data.sync.GitHubAuth
import ie.shoonya.yantra.data.sync.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The token store, against the real Android Keystore.
 *
 * Two properties, and neither is "can it round-trip". The first is that **the token is absent from
 * disk in the clear**, whose failure mode is silent: everything works perfectly whether or not the
 * encryption is doing anything at all.
 *
 * The second is newer and was learned from a live bug. **A workspace on the signed-in account must
 * not hold a copy of its token.** It used to, snapshotted at link time, and a copy is a thing that
 * can go stale while nothing on screen can tell — the GitHub screen read "signed in", every push
 * failed with "not authorized", and signing in again, the one remedy anyone could suggest, rewrote
 * the account and left every copy exactly where it was.
 */
@RunWith(AndroidJUnit4::class)
class CredentialsTest {

    private lateinit var creds: Credentials
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = ctx.getSharedPreferences("yantra_credentials", 0)

    @Before
    fun setUp() {
        creds = Credentials(ctx)
        wipe()
    }

    @After
    fun tearDown() = wipe()

    private fun wipe() {
        creds.clear("ws-a")
        creds.clear("ws-b")
        creds.clear(Credentials.ACCOUNT)
    }

    @Test
    fun aTokenComesBackOutAgain() {
        creds.paste("ws-a", "ghp_secretvalue123", "batunii")
        assertEquals("ghp_secretvalue123", creds.token("ws-a"))
        assertEquals("batunii", creds.login("ws-a"))
        assertTrue(creds.has("ws-a"))
    }

    @Test
    fun theTokenIsNotOnDiskInTheClear() {
        val secret = "ghp_thisMustNotAppearAnywhere"
        creds.paste("ws-a", secret, "batunii")

        val prefsFile = java.io.File(ctx.filesDir.parentFile, "shared_prefs/yantra_credentials.xml")
        assertTrue("the prefs file was never written", prefsFile.exists())
        val onDisk = prefsFile.readText()

        assertTrue("the token is sitting in plaintext on disk", !onDisk.contains(secret))
        // The login is deliberately plain — it is not a secret, and it is read on every conflict.
        assertTrue("the login should be readable", onDisk.contains("batunii"))
    }

    @Test
    fun twoWorkspacesKeepSeparateTokens() {
        creds.paste("ws-a", "token-for-a", "alice")
        creds.paste("ws-b", "token-for-b", "bob")

        assertEquals("token-for-a", creds.token("ws-a"))
        assertEquals("token-for-b", creds.token("ws-b"))
        assertEquals("alice", creds.login("ws-a"))
        assertEquals("bob", creds.login("ws-b"))
    }

    @Test
    fun eachTokenGetsItsOwnIv() {
        // Reusing an IV with GCM is a genuine break, not a style point: two ciphertexts under the
        // same key and IV leak the XOR of their plaintexts.
        creds.paste("ws-a", "identical", "x")
        creds.paste("ws-b", "identical", "x")

        assertNotEquals(prefs.getString("iv:ws-a", null), prefs.getString("iv:ws-b", null))
        assertNotEquals(prefs.getString("token:ws-a", null), prefs.getString("token:ws-b", null))
    }

    @Test
    fun clearingLeavesNothingBehind() {
        creds.paste("ws-a", "gone", "batunii")
        creds.clear("ws-a")

        assertNull(creds.token("ws-a"))
        assertNull(creds.login("ws-a"))
        assertNull(creds.source("ws-a"))
        assertTrue(!creds.has("ws-a"))
        val prefsFile = java.io.File(ctx.filesDir.parentFile, "shared_prefs/yantra_credentials.xml")
        assertTrue(!prefsFile.readText().contains("gone"))
    }

    @Test
    fun anUnknownWorkspaceHasNoCredentials() {
        assertNull(creds.token("never-seen"))
        assertNull(creds.providerFor("never-seen"))
        assertTrue(!creds.has("never-seen"))
    }

    @Test
    fun storingTwiceReplacesRatherThanAccumulates() {
        creds.paste("ws-a", "first", "batunii")
        creds.paste("ws-a", "second", "batunii")
        assertEquals("second", creds.token("ws-a"))
    }

    @Test
    fun aStoredTokenBecomesAJGitCredentialProvider() {
        creds.paste("ws-a", "ghp_x", "batunii")
        // Only that it exists — the contents are JGit's to read, and asserting on them would be
        // asserting on JGit rather than on us.
        assertTrue(creds.providerFor("ws-a") != null)
    }

    /** The whole of the fix, in one assertion: there is no second copy to go stale. */
    @Test
    fun aWorkspaceOnTheAccountStoresNoTokenOfItsOwn() {
        creds.signIn("gho_one", "batunii", GitHubAuth.Method.Full)
        creds.useAccount("ws-a", "batunii")

        assertNull("a copy was stored", prefs.getString("token:ws-a", null))
        assertEquals(Source.Account, creds.source("ws-a"))
        assertEquals("gho_one", creds.tokenFor("ws-a"))
        assertTrue(creds.has("ws-a"))
        assertTrue(creds.providerFor("ws-a") != null)
    }

    /**
     * Signing in again repairs every workspace at once, with nothing pushed anywhere.
     *
     * This is the case that was broken in the field: a new token reached the account and nothing
     * else, so the screen said "signed in" while every push kept failing.
     */
    @Test
    fun aFreshSignInReachesEveryWorkspaceThatUsesIt() {
        creds.signIn("gho_one", "batunii", GitHubAuth.Method.Full)
        creds.useAccount("ws-a", "batunii")
        creds.useAccount("ws-b", "batunii")

        creds.signIn("gho_two", "batunii", GitHubAuth.Method.Full)

        assertEquals("gho_two", creds.tokenFor("ws-a"))
        assertEquals("gho_two", creds.tokenFor("ws-b"))
    }

    /**
     * A token somebody chose for one repository is never swapped for a broader one.
     *
     * The temptation is real — handing every workspace the account's token would repair anything —
     * and it would quietly undo a deliberate decision, usually the decision to give this app less.
     */
    @Test
    fun aPastedTokenIsNeverReplacedByASignIn() {
        creds.paste("ws-a", "github_pat_mine", "batunii")
        creds.signIn("gho_one", "batunii", GitHubAuth.Method.Full)

        assertEquals("github_pat_mine", creds.tokenFor("ws-a"))
        assertEquals(Source.Pasted, creds.source("ws-a"))
    }

    /**
     * A copy left by an older install is adopted, without asking GitHub anything.
     *
     * `gho_` is a token this app minted through a device flow — there is no other way for one to
     * reach a workspace — so its prefix settles a question the old `viaApp` flag only guessed at,
     * and guessed wrong for every workspace added through Add a workspace.
     */
    @Test
    fun aCopyLeftByAnOlderSignInIsAdopted() {
        creds.paste("ws-a", "gho_stale", "batunii")
        creds.signIn("gho_fresh", "batunii", GitHubAuth.Method.Full)

        assertEquals(Source.Account, creds.source("ws-a"))
        assertEquals("gho_fresh", creds.tokenFor("ws-a"))
        assertNull(prefs.getString("token:ws-a", null))
    }

    /** Another account's workspace is not this account's to repair. */
    @Test
    fun aWorkspaceBelongingToSomebodyElseIsLeftAlone() {
        creds.paste("ws-b", "gho_theirs", "someone-else")
        creds.signIn("gho_mine", "batunii", GitHubAuth.Method.Full)

        assertEquals("gho_theirs", creds.tokenFor("ws-b"))
    }

    /**
     * The upgrade path: a disk full of copies, written before there was a [Source] at all.
     *
     * Reconstructed rather than mocked — the prefs are put back into the shape the old build left,
     * `src:` removed and the schema number reset, and a fresh [Credentials] is constructed so its
     * one-time repair runs for real.
     */
    @Test
    fun anOldInstallFullOfCopiesIsRepairedOnFirstConstruction() {
        creds.signIn("gho_current", "batunii", GitHubAuth.Method.Full)
        creds.paste("ws-a", "gho_twoSignInsAgo", "batunii")   // a copy, hopelessly stale
        creds.paste("ws-b", "github_pat_chosen", "batunii")   // deliberately narrow, must survive
        prefs.edit()
            .remove("src:ws-a")
            .remove("src:ws-b")
            .putInt("schema", 0)
            .commit()

        val upgraded = Credentials(ctx)

        assertEquals(Source.Account, upgraded.source("ws-a"))
        assertEquals("gho_current", upgraded.tokenFor("ws-a"))
        assertEquals(Source.Pasted, upgraded.source("ws-b"))
        assertEquals("github_pat_chosen", upgraded.tokenFor("ws-b"))
    }

    /** Which registration signed in, because a refresh token is only good at the one that made it. */
    @Test
    fun theMethodIsRememberedWithTheSignIn() {
        creds.signIn("ghu_x", "batunii", GitHubAuth.Method.Restricted, refreshToken = "r1")
        assertEquals(GitHubAuth.Method.Restricted, creds.method())
        assertEquals("r1", creds.refreshToken(Credentials.ACCOUNT))

        creds.signIn("gho_y", "batunii", GitHubAuth.Method.Full)
        assertEquals(GitHubAuth.Method.Full, creds.method())
        // A sign-in that brings no refresh token clears the one before it: keeping it means
        // TokenRenewal spending a token that has already been rotated away, and reporting the
        // refusal as "sign in again" for a token that was working perfectly.
        assertNull(creds.refreshToken(Credentials.ACCOUNT))
    }

    /** What signing out has to leave behind, which is nothing that can still reach GitHub. */
    @Test
    fun signingOutLeavesNoWorkingCredentialAnywhere() {
        creds.signIn("gho_one", "batunii", GitHubAuth.Method.Full, refreshToken = "r1")
        creds.useAccount("ws-a", "batunii")
        creds.paste("ws-b", "github_pat_own", "batunii")

        assertEquals(listOf("ws-a"), creds.dependents())

        creds.clear(Credentials.ACCOUNT)

        assertNull(creds.tokenFor("ws-a"))
        assertNull(creds.providerFor("ws-a"))
        assertNull(creds.refreshToken(Credentials.ACCOUNT))
        // A token of its own is not the account's to revoke, and the button says so.
        assertEquals("github_pat_own", creds.tokenFor("ws-b"))
    }

    /**
     * Which registration an old sign-in came through, read off the token rather than guessed.
     *
     * The install being upgraded recorded only *that* it was signed in. Getting this wrong is not
     * cosmetic: a refresh token presented to the other client id is refused in a way that reads
     * exactly like an expired sign-in, so the user would be told to sign in again by a bug.
     */
    @Test
    fun anOldSignInIsIdentifiedByTheTokenItLeftBehind() {
        creds.paste(Credentials.ACCOUNT, "ghu_fromTheGitHubApp", "batunii")
        prefs.edit().remove("src:${Credentials.ACCOUNT}").putInt("schema", 0).commit()
        assertEquals(GitHubAuth.Method.Restricted, Credentials(ctx).method())

        creds.paste(Credentials.ACCOUNT, "gho_fromTheOauthApp", "batunii")
        prefs.edit().remove("src:${Credentials.ACCOUNT}").putInt("schema", 0).commit()
        assertEquals(GitHubAuth.Method.Full, Credentials(ctx).method())

        // A pasted token came from no registration at all, and there is nothing to refresh it with.
        creds.paste(Credentials.ACCOUNT, "github_pat_typedByHand", "batunii")
        prefs.edit().remove("src:${Credentials.ACCOUNT}").putInt("schema", 0).commit()
        assertNull(Credentials(ctx).method())
    }

    /**
     * A workspace does not follow the account into somebody else's sign-in.
     *
     * Signing out and back in as a different person leaves these markers pointing at an account that
     * is not theirs. Following it would push under the wrong identity — not an authentication
     * failure but a correctness one, since the login is the conflict tiebreak and the value behind
     * `@assignee`.
     */
    @Test
    fun aWorkspaceDoesNotBorrowADifferentAccountsSignIn() {
        creds.signIn("gho_mine", "batunii", GitHubAuth.Method.Full)
        creds.useAccount("ws-a", "batunii")
        creds.clear(Credentials.ACCOUNT)

        creds.signIn("gho_theirs", "someone-else", GitHubAuth.Method.Full)

        assertNull(creds.tokenFor("ws-a"))
        assertNull(creds.providerFor("ws-a"))
    }
}
