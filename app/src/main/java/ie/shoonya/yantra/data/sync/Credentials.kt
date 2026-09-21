package ie.shoonya.yantra.data.sync

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
 * Where a stored credential came from, which is the same question as who owns it.
 *
 * This replaced a boolean called `viaApp`, and the boolean is worth a sentence because its failure
 * was expensive. It meant "signed in rather than pasted", it was written from the screen that knew
 * whether a token had been typed, and it was left at its default by the one path that mattered — so
 * the workspaces that took their token from the account were marked as though the user had chosen
 * it themselves. Everything that filtered on it repaired the wrong set.
 */
enum class Source {
    /** Signed in through [GitHubAuth.Method.Full]. Only ever on [Credentials.ACCOUNT]. */
    Full,

    /** Signed in through [GitHubAuth.Method.Restricted]. Only ever on [Credentials.ACCOUNT]. */
    Restricted,

    /**
     * This workspace syncs with the account's token, and stores none of its own.
     *
     * **The whole point of this value is the token that is not there.** A workspace used to keep a
     * copy, snapshotted when it was linked, and a copy is a thing that can go stale while nothing on
     * screen can tell. Signing in again rewrote the account and left every copy behind: the GitHub
     * screen read "signed in", every push failed with "not authorized", and the only remedy anyone
     * could think to offer — sign in again — was the very thing that did not work.
     */
    Account,

    /**
     * A token the user pasted for this one workspace.
     *
     * Never replaced by anything. It was chosen for that repository, usually because it is narrower
     * than the account's, and handing it a broader token nobody asked for would undo a deliberate
     * decision invisibly.
     */
    Pasted,
    ;

    /** Which registration minted this, when it was minted by one at all. */
    val method: GitHubAuth.Method?
        get() = when (this) {
            Full -> GitHubAuth.Method.Full
            Restricted -> GitHubAuth.Method.Restricted
            Account, Pasted -> null
        }

    companion object {
        fun of(method: GitHubAuth.Method): Source = when (method) {
            GitHubAuth.Method.Full -> Full
            GitHubAuth.Method.Restricted -> Restricted
        }
    }
}

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
 *
 * **There is one token per sign-in, not one per workspace.** That is the rule this file exists to
 * enforce, and it was learned the hard way — see [Source.Account]. A workspace that syncs with the
 * account's sign-in stores a marker and no token at all, so there is exactly one string on disk to
 * go stale, and replacing it repairs everything at once. A workspace with a pasted token of its own
 * keeps it, because it is not a copy of anything.
 */
class Credentials(context: Context) {

    private val prefs = context.getSharedPreferences("yantra_credentials", Context.MODE_PRIVATE)

    init {
        adoptOldCopies()
    }

    companion object {
        /**
         * The signed-in GitHub account, kept under a reserved workspace id.
         *
         * A workspace's access is scoped to that workspace, but signing in happens *before* any
         * workspace exists — the token is what creates the repository the workspace will point at.
         * Reserving an id rather than adding a second store means one Keystore key, one encryption
         * path, and one place to look when asking whether anyone is signed in. The `@` cannot collide
         * with a real id, which is a UUID.
         */
        const val ACCOUNT = "@account"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "yantra.credentials"
        private const val GCM_TAG_BITS = 128

        /** Bumped when the shape on disk changes, so [adoptOldCopies] runs once and then never. */
        private const val SCHEMA_KEY = "schema"
        private const val SCHEMA_NOW = 2

        private fun tokenKey(ws: String) = "token:$ws"
        private fun ivKey(ws: String) = "iv:$ws"
        private fun loginKey(ws: String) = "login:$ws"
        private fun sourceKey(ws: String) = "src:$ws"

        /** GitHub's numeric id for the account. Public information, and stored as such. */
        private fun accountIdKey(ws: String) = "ghid:$ws"

        /** The boolean [Source] replaced. Read only by the migration, never written again. */
        private fun viaAppKey(ws: String) = "viaapp:$ws"

        /**
         * A refresh token, and when the access token beside it stops working.
         *
         * Both are absent for a pasted personal token and for a registration with user-token expiry
         * switched off — in either case the access token simply does not lapse, and there is nothing
         * here to read.
         */
        private fun refreshKey(ws: String) = "refresh:$ws"
        private fun refreshIvKey(ws: String) = "refreshiv:$ws"
        private fun expiryKey(ws: String) = "expires:$ws"

        /**
         * The prefixes GitHub puts on a token it minted for an app, as opposed to one a person made.
         *
         * `gho_` is an OAuth app's user token and `ghu_` a GitHub App's. Neither can arrive here by
         * any route except this app's own device flow — a person pastes `github_pat_` or `ghp_`,
         * which is what they can copy out of GitHub's settings. So a token with one of these
         * prefixes sitting under a workspace id is, without ambiguity, a copy of an account token,
         * whatever any flag beside it claims.
         */
        private val MINTED = listOf("gho_", "ghu_")

        private fun isMinted(token: String) = MINTED.any { token.startsWith(it) }
    }

    /**
     * The Keystore-held AES key, created on first use.
     *
     * Deliberately *not* requiring user authentication. A sync that fires from WorkManager while the
     * phone is in a pocket has nobody to authenticate, and a token that could only be used while the
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
     * Records a fresh sign-in, and takes every stale copy of the old one out of existence.
     *
     * The adoption is the half that matters and it is deliberately *local*: a workspace still
     * holding a token this app minted is a copy by definition ([MINTED]), so it can be turned into a
     * reference without asking GitHub anything. An earlier version of this asked — one request per
     * workspace, at sign-in — which worked and was the wrong shape: it repaired the copies it could
     * reach at the moment it ran, and left the mechanism that creates them in place.
     */
    fun signIn(
        token: String,
        login: String,
        method: GitHubAuth.Method,
        /**
         * GitHub's refresh token, when it issued one.
         *
         * Null *clears* any stored one, rather than leaving it. Keeping it would be the more
         * cautious-looking choice and is the wrong one: a sign-in that returns no refresh token is
         * GitHub saying this token does not lapse, and a leftover refresh token from an earlier
         * sign-in is one that has already been spent. [TokenRenewal] would find it, see no expiry
         * beside it, refresh on that basis and be refused — reporting "sign in again" for a token
         * that was working perfectly.
         */
        refreshToken: String? = null,
        /** When [token] stops working, or null when it does not. */
        expiresAt: Long? = null,
        /** GitHub's numeric id for [login]. Null leaves any stored one alone. */
        accountId: Long? = null,
    ) {
        write(ACCOUNT, token, login, Source.of(method), refreshToken, expiresAt, accountId)
        adoptCopies(login)
    }

    /** Records a token the user pasted, for the account or for one workspace. */
    fun paste(workspaceId: String, token: String, login: String) {
        write(workspaceId, token, login, Source.Pasted)
    }

    /**
     * Points a workspace at the account's sign-in, storing no token of its own.
     *
     * [login] is kept even though the account has it, because it is read on every conflict
     * arbitration and going through the account for it would mean the tiebreak changing under a
     * workspace when somebody signs out.
     */
    fun useAccount(workspaceId: String, login: String) {
        prefs.edit()
            .remove(tokenKey(workspaceId))
            .remove(ivKey(workspaceId))
            .remove(refreshKey(workspaceId))
            .remove(refreshIvKey(workspaceId))
            .remove(expiryKey(workspaceId))
            .remove(viaAppKey(workspaceId))
            .putString(loginKey(workspaceId), login)
            .putString(sourceKey(workspaceId), Source.Account.name)
            .commit()
    }

    private fun write(
        workspaceId: String,
        token: String,
        login: String,
        source: Source,
        refreshToken: String? = null,
        expiresAt: Long? = null,
        accountId: Long? = null,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(token.toByteArray())
        prefs.edit()
            .putString(tokenKey(workspaceId), Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(ivKey(workspaceId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(loginKey(workspaceId), login)
            .putString(sourceKey(workspaceId), source.name)
            .remove(viaAppKey(workspaceId))
            .apply {
                if (refreshToken != null) {
                    val rc = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                    putString(refreshKey(workspaceId), Base64.encodeToString(rc.doFinal(refreshToken.toByteArray()), Base64.NO_WRAP))
                    putString(refreshIvKey(workspaceId), Base64.encodeToString(rc.iv, Base64.NO_WRAP))
                } else {
                    remove(refreshKey(workspaceId))
                    remove(refreshIvKey(workspaceId))
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
     * Turns every copy of an account token into a reference to it.
     *
     * Runs on every sign-in, so a copy cannot outlive the token it was taken from by more than the
     * moment between them. Only workspaces belonging to [login] and only [MINTED] tokens: a pasted
     * one is left exactly where it is.
     */
    private fun adoptCopies(login: String) {
        storedIds()
            .filter { it != ACCOUNT && login(it) == login && source(it) != Source.Account }
            .filter { token(it)?.let(::isMinted) == true }
            .forEach { useAccount(it, login) }
    }

    /**
     * The one-time repair of an install made before there was a [Source].
     *
     * Runs in the constructor, guarded by a schema number, so nothing downstream has to wonder
     * whether it has happened. What it is undoing is a disk full of copies: every workspace linked
     * from an account holds that account's token, and on this device at this moment some of those
     * strings are two sign-ins out of date.
     *
     * The test is the token's own prefix rather than the `viaapp` flag beside it, because the flag
     * is exactly what could not be trusted — it was never written by the path that linked most
     * workspaces. A token that GitHub minted for an app cannot have been pasted by a person, so its
     * prefix settles the question the flag only guessed at.
     *
     * An undecryptable token is left alone rather than adopted. It is unrecoverable either way, but
     * classifying it would mean deciding, with no evidence, whether the workspace had a token of its
     * own — and guessing "no" would quietly hand it the account's broader one later.
     */
    private fun adoptOldCopies() {
        if (prefs.getInt(SCHEMA_KEY, 0) >= SCHEMA_NOW) return

        val accountLogin = prefs.getString(loginKey(ACCOUNT), null)
        val accountToken = token(ACCOUNT)

        prefs.all.keys
            .filter { it.startsWith("token:") }
            .map { it.removePrefix("token:") }
            .filter { it != ACCOUNT && !prefs.contains(sourceKey(it)) }
            .forEach { id ->
                val held = token(id) ?: return@forEach
                val mine = accountLogin != null && prefs.getString(loginKey(id), null) == accountLogin
                if (mine && (isMinted(held) || held == accountToken)) useAccount(id, accountLogin!!)
                else prefs.edit().putString(sourceKey(id), Source.Pasted.name).commit()
            }

        // The account's own row. An old install recorded only *that* it was signed in, never
        // through which registration — and getting that wrong is not cosmetic: a refresh token
        // presented to the other client id is refused in a way that reads exactly like an expired
        // sign-in, so the user would be told to sign in again by a bug rather than by GitHub.
        //
        // The token's own prefix answers it exactly, where the old flag could only say "not
        // pasted": `ghu_` is a GitHub App's user token and `gho_` an OAuth app's. Undecryptable is
        // left unclassified rather than guessed, for the same reason as the workspaces above.
        if (!prefs.contains(sourceKey(ACCOUNT))) {
            when {
                token(ACCOUNT)?.startsWith("ghu_") == true -> Source.Restricted
                token(ACCOUNT)?.startsWith("gho_") == true -> Source.Full
                token(ACCOUNT) != null -> Source.Pasted
                else -> null
            }?.let { prefs.edit().putString(sourceKey(ACCOUNT), it.name).commit() }
        }

        prefs.edit().putInt(SCHEMA_KEY, SCHEMA_NOW).commit()
    }

    /**
     * The token stored *here*, or null if there is none — or if it can no longer be decrypted.
     *
     * Not the token a workspace syncs with; that is [tokenFor]. A workspace pointed at the account
     * stores nothing, so this answers null for it, which is the honest answer to the question asked.
     *
     * The Keystore key can genuinely disappear: a device restore, or the user adding a lock screen
     * where there was none, can invalidate it. That is a re-authentication prompt, not a crash, so
     * an undecryptable token reads as absent.
     */
    fun token(workspaceId: String): String? =
        unseal(prefs.getString(tokenKey(workspaceId), null), prefs.getString(ivKey(workspaceId), null))

    /**
     * The token this workspace actually authenticates with.
     *
     * One indirection, and it is the fix: a workspace whose [Source] is [Source.Account] reads the
     * account's token *now* rather than whatever it was handed when it was linked. There is nothing
     * to keep in step, because there is only ever one string.
     */
    fun tokenFor(workspaceId: String): String? {
        if (source(workspaceId) != Source.Account) return token(workspaceId)
        // Only while it is still the same person's account.
        //
        // Signing out and signing in as somebody else leaves these markers pointing at a sign-in
        // that is not theirs. Following it would push to somebody's repository under the wrong
        // identity — which is not an authentication failure but a correctness one: the login is the
        // conflict tiebreak and the value behind `@assignee`, so the commits would arbitrate as the
        // wrong person. Nothing is the right answer, and it surfaces as "sign in again".
        val who = login(workspaceId)
        if (who != null && who != login(ACCOUNT)) return null
        return token(ACCOUNT)
    }

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

    /** Where this credential came from, or null when there is nothing stored under that id. */
    fun source(workspaceId: String): Source? =
        prefs.getString(sourceKey(workspaceId), null)
            ?.let { name -> Source.entries.firstOrNull { it.name == name } }

    /** Which registration the current sign-in went through, for the screens and for refreshing. */
    fun method(workspaceId: String = ACCOUNT): GitHubAuth.Method? = source(workspaceId)?.method

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

    /** Whether this workspace can authenticate at all — through the account or on its own. */
    fun has(workspaceId: String): Boolean = tokenFor(workspaceId) != null

    /**
     * Every id with something stored under it, [ACCOUNT] included.
     *
     * Keyed on the login rather than on the token, because a workspace that syncs through the
     * account has no token of its own and would otherwise be invisible to every caller that asks
     * what exists.
     */
    fun storedIds(): List<String> =
        prefs.all.keys.filter { it.startsWith("login:") }.map { it.removePrefix("login:") }

    /**
     * The workspaces that would stop syncing if the account signed out.
     *
     * Said on the sign-out button rather than discovered afterwards. The screen used to promise the
     * opposite — "your workspaces keep syncing" — which was true of the copies and is deliberately
     * no longer true of anything: a credential that outlives the sign-in it came from is exactly the
     * stale token this file was rewritten to make impossible.
     */
    fun dependents(): List<String> = storedIds().filter { source(it) == Source.Account }

    /** Forgets a workspace's credentials. The remote is untouched; revoking is done on GitHub. */
    fun clear(workspaceId: String) {
        prefs.edit()
            .remove(tokenKey(workspaceId))
            .remove(ivKey(workspaceId))
            .remove(loginKey(workspaceId))
            .remove(sourceKey(workspaceId))
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
        val token = tokenFor(workspaceId) ?: return null
        return org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
            login(workspaceId) ?: "x-access-token",
            token,
        )
    }
}
