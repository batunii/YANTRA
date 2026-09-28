package ie.shoonya.yantra.data.sync

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
 * **The account is the identity, and now it is also the only copy.** A workspace that syncs through
 * the account stores a marker rather than a token, so a refresh has one string to replace and
 * nothing to push down. That used to be the hard part of this file: the new token had to be spread
 * to every workspace, and a workspace missed by that spreading went on presenting a token that had
 * already been rotated away. Refreshing per workspace was never an option either — GitHub rotates
 * the refresh token on every use, so the second workspace would spend one that was already gone.
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
        // Refreshed against the registration that issued it. The two registrations are different
        // OAuth clients as far as GitHub is concerned, and a refresh token presented to the wrong
        // one is refused in a way that reads exactly like an expired sign-in.
        val method = credentials.method() ?: return Outcome.NothingToDo
        return when (val result = auth.refresh(refresh, method)) {
            is DevicePoll.Token -> {
                credentials.signIn(
                    result.token, login, method,
                    refreshToken = result.refreshToken,
                    expiresAt = result.expiresInSecs?.let { now + it * 1000L },
                )
                Log.i(TAG, "renewed the GitHub sign-in")
                Outcome.Renewed
            }
            // Nothing was said, so nothing is changed. The pass carries on with the token it has;
            // if that one still works, this was never needed.
            is DevicePoll.Offline -> Outcome.CouldNotAsk
            else -> {
                ie.shoonya.yantra.Trace.error("token", "could not renew the sign-in: $result")
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
