package ie.napkin.supertasks.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where a workspace's access token lives.
 *
 * A token that can push to someone's repository is the most dangerous thing this app will ever
 * hold, so it is never written in the clear. The key that encrypts it is generated inside the
 * Android Keystore and cannot be read out of it — not by this app, not by a backup, not by anyone
 * reading the prefs file off a rooted device. What lands on disk is ciphertext and an IV.
 *
 * The login is stored plainly beside it, deliberately. It is not a secret, it is needed for the
 * conflict tiebreak and for `@assignee`, and encrypting it would mean a Keystore round-trip on
 * every arbitration.
 */
class Credentials(context: Context) {

    private val prefs = context.getSharedPreferences("yantra_credentials", Context.MODE_PRIVATE)

    companion object {
        /**
         * The signed-in GitHub account, kept under a reserved workspace id.
         *
         * A workspace's token is scoped to that workspace, but signing in happens *before* any
         * workspace exists — the token is what creates the repository the workspace will point at.
         * Reserving an id rather than adding a second store means one Keystore key, one encryption
         * path, and one place to look when asking whether anyone is signed in. The `@` cannot collide
         * with a real id, which is a UUID.
         */
        const val ACCOUNT = "@account"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "yantra.credentials"
        private const val GCM_TAG_BITS = 128

        private fun tokenKey(ws: String) = "token:$ws"
        private fun ivKey(ws: String) = "iv:$ws"
        private fun loginKey(ws: String) = "login:$ws"
    }

    /**
     * The Keystore-held AES key, created on first use.
     *
     * Deliberately *not* requiring user authentication. A sync that fires from WorkManager while the
     * phone is in a pocket has nobody to authenticate, and a token that can only be used while the
     * screen is unlocked would mean sync only ever happening when you are already looking at it.
     */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun store(workspaceId: String, token: String, login: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(token.toByteArray())
        prefs.edit()
            .putString(tokenKey(workspaceId), Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(ivKey(workspaceId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(loginKey(workspaceId), login)
            // commit, not apply. Setup reports success to the user once this returns, and an
            // asynchronous write means a crash in between could leave a workspace whose git remote
            // is configured and whose token is gone — authentication failing for no visible reason.
            .commit()
    }

    /**
     * The token, or null if there is none — or if it can no longer be decrypted.
     *
     * The Keystore key can genuinely disappear: a device restore, or the user adding a lock screen
     * where there was none, can invalidate it. That is a re-authentication prompt, not a crash, so
     * an undecryptable token reads as absent.
     */
    fun token(workspaceId: String): String? {
        val sealed = prefs.getString(tokenKey(workspaceId), null) ?: return null
        val iv = prefs.getString(ivKey(workspaceId), null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
                )
            }
            cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)).decodeToString()
        }.getOrNull()
    }

    fun login(workspaceId: String): String? = prefs.getString(loginKey(workspaceId), null)

    fun has(workspaceId: String): Boolean = token(workspaceId) != null

    /** Forgets a workspace's credentials. The remote is untouched; revoking is done on GitHub. */
    fun clear(workspaceId: String) {
        prefs.edit()
            .remove(tokenKey(workspaceId))
            .remove(ivKey(workspaceId))
            .remove(loginKey(workspaceId))
            .commit()
    }

    /**
     * JGit's view of the same thing.
     *
     * GitHub accepts a personal access token as the HTTP password with any username, so the login
     * goes in the username slot — which makes the request legible in a proxy log without putting
     * the secret there.
     */
    fun providerFor(workspaceId: String): org.eclipse.jgit.transport.CredentialsProvider? {
        val token = token(workspaceId) ?: return null
        val login = login(workspaceId) ?: "x-access-token"
        return org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(login, token)
    }
}
