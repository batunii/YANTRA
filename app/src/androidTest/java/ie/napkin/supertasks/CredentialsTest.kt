package ie.napkin.supertasks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.sync.Credentials
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
 * The property that matters is not "can it round-trip" but **is the token absent from disk in the
 * clear**. A push token is the most dangerous thing this app holds, and the failure mode is silent:
 * everything works perfectly whether or not the encryption is doing anything at all.
 */
@RunWith(AndroidJUnit4::class)
class CredentialsTest {

    private lateinit var creds: Credentials
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        creds = Credentials(ctx)
        creds.clear("ws-a")
        creds.clear("ws-b")
    }

    @After
    fun tearDown() {
        creds.clear("ws-a")
        creds.clear("ws-b")
    }

    @Test
    fun aTokenComesBackOutAgain() {
        creds.store("ws-a", "ghp_secretvalue123", "batunii")
        assertEquals("ghp_secretvalue123", creds.token("ws-a"))
        assertEquals("batunii", creds.login("ws-a"))
        assertTrue(creds.has("ws-a"))
    }

    @Test
    fun theTokenIsNotOnDiskInTheClear() {
        val secret = "ghp_thisMustNotAppearAnywhere"
        creds.store("ws-a", secret, "batunii")

        val prefsFile = java.io.File(ctx.filesDir.parentFile, "shared_prefs/yantra_credentials.xml")
        assertTrue("the prefs file was never written", prefsFile.exists())
        val onDisk = prefsFile.readText()

        assertTrue("the token is sitting in plaintext on disk", !onDisk.contains(secret))
        // The login is deliberately plain — it is not a secret, and it is read on every conflict.
        assertTrue("the login should be readable", onDisk.contains("batunii"))
    }

    @Test
    fun twoWorkspacesKeepSeparateTokens() {
        creds.store("ws-a", "token-for-a", "alice")
        creds.store("ws-b", "token-for-b", "bob")

        assertEquals("token-for-a", creds.token("ws-a"))
        assertEquals("token-for-b", creds.token("ws-b"))
        assertEquals("alice", creds.login("ws-a"))
        assertEquals("bob", creds.login("ws-b"))
    }

    @Test
    fun eachTokenGetsItsOwnIv() {
        // Reusing an IV with GCM is a genuine break, not a style point: two ciphertexts under the
        // same key and IV leak the XOR of their plaintexts.
        creds.store("ws-a", "identical", "x")
        creds.store("ws-b", "identical", "x")

        val prefs = ctx.getSharedPreferences("yantra_credentials", 0)
        assertNotEquals(prefs.getString("iv:ws-a", null), prefs.getString("iv:ws-b", null))
        assertNotEquals(prefs.getString("token:ws-a", null), prefs.getString("token:ws-b", null))
    }

    @Test
    fun clearingLeavesNothingBehind() {
        creds.store("ws-a", "gone", "batunii")
        creds.clear("ws-a")

        assertNull(creds.token("ws-a"))
        assertNull(creds.login("ws-a"))
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
        creds.store("ws-a", "first", "batunii")
        creds.store("ws-a", "second", "batunii")
        assertEquals("second", creds.token("ws-a"))
    }

    @Test
    fun aStoredTokenBecomesAJGitCredentialProvider() {
        creds.store("ws-a", "ghp_x", "batunii")
        // Only that it exists — the contents are JGit's to read, and asserting on them would be
        // asserting on JGit rather than on us.
        assertTrue(creds.providerFor("ws-a") != null)
    }
}
