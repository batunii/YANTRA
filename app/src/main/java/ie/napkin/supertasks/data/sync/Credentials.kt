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

        /** GitHub's numeric id for the account. Public information, and stored as such. */
        private fun accountIdKey(ws: String) = "ghid:$ws"
        private fun viaAppKey(ws: String) = "viaapp:$ws"

        /**
         * A refresh token, and when the access token beside it stops working.
         *
         * Both are absent for a pasted personal token and for a GitHub App registered with user-token
         * expiry switched off — in either case the access token simply does not lapse, and there is
         * nothing here to read.
         */
        private fun refreshKey(ws: String) = "refresh:$ws"
        private fun refreshIvKey(ws: String) = "refreshiv:$ws"
        private fun expiryKey(ws: String) = "expires:$ws"
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

    /**
     * [viaApp] records that this came from signing in rather than from a pasted token.
     *
     * The two behave differently in one place that matters: a signed-in account needs the GitHub App
     * installed before it can see any repository, and a pasted token does not. Without knowing which
     * is which, the app would tell someone who pasted a fine-grained token to go and install an App
     * they have no use for.
     */
    fun store(
        workspaceId: String,
        token: String,
        login: String,
        viaApp: Boolean = false,
        /** GitHub's refresh token, when it issued one. Null leaves any stored one untouched. */
        refreshToken: String? = null,
        /** When [token] stops working, or null when it does not. */
        expiresAt: Long? = null,
        /** GitHub's numeric id for [login]. Null leaves any stored one alone. */
        accountId: Long? = null,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(token.toByteArray())
        prefs.edit()
            .putString(tokenKey(workspaceId), Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(ivKey(workspaceId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(loginKey(workspaceId), login)
            .putBoolean(viaAppKey(workspaceId), viaApp)
            .apply {
                if (refreshToken != null) {
                    val rc = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                    putString(refreshKey(workspaceId), Base64.encodeToString(rc.doFinal(refreshToken.toByteArray()), Base64.NO_WRAP))
                    putString(refreshIvKey(workspaceId), Base64.encodeToString(rc.iv, Base64.NO_WRAP))
                }
                if (expiresAt != null) putLong(expiryKey(workspaceId), expiresAt) else remove(expiryKey(workspaceId))
                if (accountId != null) putLong(accountIdKey(workspaceId), accountId)
            }
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
    fun token(workspaceId: String): String? =
        unseal(prefs.getString(tokenKey(workspaceId), null), prefs.getString(ivKey(workspaceId), null))

    private fun unseal(sealed: String?, iv: String?): String? {
        if (sealed == null || iv == null) return null
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

    /** GitHub's numeric id for the stored account, when it is known. */
    fun accountId(workspaceId: String): Long? =
        prefs.getLong(accountIdKey(workspaceId), 0L).takeIf { it > 0L }

    /** Remembers the id for an account signed in before it was being recorded. */
    fun rememberAccountId(workspaceId: String, id: Long) {
        prefs.edit().putLong(accountIdKey(workspaceId), id).apply()
    }

    /** The refresh token, if GitHub issued one and it still decrypts. */
    fun refreshToken(workspaceId: String): String? =
        unseal(prefs.getString(refreshKey(workspaceId), null), prefs.getString(refreshIvKey(workspaceId), null))

    /**
     * When the access token lapses, or null when it does not lapse at all.
     *
     * Zero is "not stored" rather than "the epoch": every token this app has ever held predates
     * being asked the question, and treating an absent value as long expired would send someone who
     * pasted a personal token into a refresh that cannot work.
     */
    fun expiresAt(workspaceId: String): Long? = prefs.getLong(expiryKey(workspaceId), 0L).takeIf { it > 0L }

    /** True when this account was signed in through the GitHub App rather than pasted as a token. */
    fun viaApp(workspaceId: String): Boolean = prefs.getBoolean(viaAppKey(workspaceId), false)

    fun has(workspaceId: String): Boolean = token(workspaceId) != null

    /**
     * Every id that has a token stored, [ACCOUNT] included.
     *
     * Needed because a workspace's token is a *copy* of the account's, taken when the workspace was
     * linked. Refreshing the account therefore has to push the new value down to the copies, or the
     * account would work and every workspace would go on failing with the token it snapshotted.
     */
    fun storedIds(): List<String> =
        prefs.all.keys.filter { it.startsWith("token:") }.map { it.removePrefix("token:") }

    /** Forgets a workspace's credentials. The remote is untouched; revoking is done on GitHub. */
    fun clear(workspaceId: String) {
        prefs.edit()
            .remove(tokenKey(workspaceId))
            .remove(ivKey(workspaceId))
            .remove(loginKey(workspaceId))
            .remove(viaAppKey(workspaceId))
            .remove(refreshKey(workspaceId))
            .remove(refreshIvKey(workspaceId))
            .remove(expiryKey(workspaceId))
            .remove(accountIdKey(workspaceId))
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
