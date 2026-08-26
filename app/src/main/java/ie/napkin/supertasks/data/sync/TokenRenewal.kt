package ie.napkin.supertasks.data.sync

import android.util.Log

/**
 * Keeps a GitHub sign-in usable without asking the user again.
 *
 * A GitHub App issues user tokens that lapse after a few hours unless the App is registered with
 * expiry switched off. This app assumed the latter and threw away both the refresh token and the
 * expiry that came with every sign-in — so sync worked for an afternoon, started failing with "not
 * authorized", and the only remedy was to sign in again. Then again the next day. Nothing said why,
 * because the fetch failure was the first symptom and it named none of this.
 *
 * **The account is the identity; a workspace's token is a copy of it.** Linking a workspace snapshots
 * whatever the account held at the time, which means a refresh has to be done in one place and then
 * pushed down — refreshing per workspace would be worse than not refreshing at all, because GitHub
 * rotates the refresh token on every use and the second workspace would present one that had already
 * been spent.
 *
 * Doing nothing is the common case and the correct one: a pasted personal token and a non-expiring
 * App token both store no refresh token, and this returns immediately without a request.
 */
class TokenRenewal(
    private val credentials: Credentials,
    private val auth: GitHubDeviceAuth,
) {
    /**
     * Renews the account token if it is close enough to lapsing to matter.
     *
     * Called before every sync pass, so it has to be cheap when there is nothing to do and quiet
     * when it cannot work. Offline leaves everything alone: a refresh that was never attempted must
     * not be mistaken for one that was refused, or a tunnel would sign someone out.
     */
    fun renewIfNeeded(now: Long = System.currentTimeMillis()): Outcome {
        val refresh = credentials.refreshToken(Credentials.ACCOUNT) ?: return Outcome.NothingToDo
        val expiresAt = credentials.expiresAt(Credentials.ACCOUNT)

        // A refresh token with no recorded expiry beside it means we do not know how long the access
        // token has left. Renewing is the safe reading: the cost is one request, and the cost of
        // guessing the other way is a failed sync.
        if (expiresAt != null && now < expiresAt - MARGIN_MS) return Outcome.StillGood

        val login = credentials.login(Credentials.ACCOUNT) ?: return Outcome.NothingToDo
        return when (val result = auth.refresh(refresh)) {
            is DevicePoll.Token -> {
                credentials.store(
                    Credentials.ACCOUNT, result.token, login, viaApp = true,
                    refreshToken = result.refreshToken,
                    expiresAt = result.expiresInSecs?.let { now + it * 1000L },
                )
                // Down to every workspace that was linked from this account. Their copies carry no
                // refresh token of their own — deliberately, so only one of them can spend it.
                credentials.storedIds()
                    .filter { it != Credentials.ACCOUNT && credentials.login(it) == login }
                    .forEach { credentials.store(it, result.token, login, viaApp = true) }
                Log.i(TAG, "renewed the GitHub sign-in")
                Outcome.Renewed
            }
            // Nothing was said, so nothing is changed. The pass carries on with the token it has;
            // if that one still works, this was never needed.
            is DevicePoll.Offline -> Outcome.CouldNotAsk
            else -> {
                Log.w(TAG, "could not renew the sign-in: $result")
                Outcome.NeedsSignIn
            }
        }
    }

    /** What happened, for a caller that wants to say so. */
    enum class Outcome { NothingToDo, StillGood, Renewed, CouldNotAsk, NeedsSignIn }

    private companion object {
        /**
         * Renew this long before the token actually lapses.
         *
         * A sync pass takes seconds, but the phone's clock and GitHub's need not agree to the
         * second, and a token that expires mid-rebase fails in a far more confusing way than one
         * renewed a few minutes early.
         */
        const val MARGIN_MS = 5 * 60 * 1000L

        const val TAG = "YantraSync"
    }
}
