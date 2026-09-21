package ie.shoonya.yantra.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The client id of the OAuth app this build signs in through.
 *
 * **This is not a secret.** The device flow exists precisely so that an app with no server can
 * authenticate without holding one: the client id is public, the user proves their own identity on
 * github.com, and the token comes back to the device that asked. Shipping it in the APK is the
 * design, not a compromise of it.
 *
 * It is empty here because only the person who owns the OAuth app can create it — register one at
 * github.com/settings/developers, tick **Enable Device Flow**, untick **Expire user access tokens**,
 * and paste the client id below. Until then the sign-in path is offline and the app says so instead
 * of failing at the network.
 *
 * **Why an OAuth app and not a GitHub App**, which is what this was until the two-device bug was
 * finally pinned down: a GitHub App holds at most *two* user access tokens per user, and issuing a
 * third silently revokes the oldest. Two phones filled both slots, so signing in anywhere a third
 * time — including signing in again on a phone that already had a token — killed whichever device
 * had been quiet longest. It presents as "sign in again" on a device nobody touched, days later,
 * with nothing in any log to connect it to the sign-in that caused it. That cap is not documented
 * anywhere; it was found by minting tokens until one died. An OAuth app's limit is ten per
 * user/application/scope, which is documented, and which two phones do not come close to.
 *
 * The price is honest and worth stating: [SCOPE] is `repo`, which is read and write to every
 * repository the user owns. A GitHub App could be installed on one repository and reach no further.
 * There is no fine-grained middle ground for an OAuth app, and no way to create a repository at all
 * without it.
 */
object GitHubAuth {
    const val CLIENT_ID = "Ov23liWz2CApMbchpQOg"

    /**
     * What the token may do, shown to the user on GitHub's consent screen before they agree.
     *
     * `repo` is the only scope that can create a repository, which is the whole reason this app
     * stopped sending people to github.com/new to press a button themselves. It is coarse — it
     * cannot be narrowed to one repository, and it carries delete and visibility rights this app
     * never uses — and GitHub offers nothing finer for an OAuth app.
     *
     * Kept as one constant because it appears in the consent the user gives and in the token limit
     * GitHub enforces: ten tokens per user, per application, *per scope*. Changing this string
     * starts a fresh set of ten and strands every token already issued under the old one.
     */
    const val SCOPE = "repo"

    val configured: Boolean get() = CLIENT_ID.isNotBlank()

    /** Where someone invites people to a repository. The real browser, never a WebView. */
    fun accessSettingsUrl(slug: String): String = "https://github.com/$slug/settings/access"
}

/** What GitHub gave us to show the user: a code, and where to type it. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSecs: Int,
    val expiresInSecs: Int,
)

/** Asking GitHub for a code to show. */
sealed interface DeviceStart {
    data class Ok(val code: DeviceCode) : DeviceStart
    data class Failed(val reason: String) : DeviceStart
}

/** One poll of the token endpoint. */
sealed interface DevicePoll {
    /**
     * [refreshToken] and [expiresInSecs] are present only when the App issues expiring user tokens.
     * Both null means the token does not lapse, which is a different thing from not knowing.
     */
    data class Token(
        val token: String,
        val refreshToken: String? = null,
        val expiresInSecs: Int? = null,
    ) : DevicePoll
    /** Nobody has typed the code yet. Keep waiting — this is the normal answer, many times over. */
    data object Pending : DevicePoll
    /** We polled too fast and GitHub has told us the new floor. */
    data class SlowDown(val intervalSecs: Int) : DevicePoll
    /**
     * We could not ask. **Not the same as a refusal** — GitHub has said nothing.
     *
     * Kept apart from [Failed] because the flow runs for up to fifteen minutes while the user walks
     * to another device, and a mobile connection drops in that window as a matter of course. Treating
     * one missed request as a refusal ends a sign-in that was going perfectly and makes the user
     * start again with a fresh code.
     */
    data class Offline(val reason: String) : DevicePoll
    /** GitHub answered, and the answer was no. */
    data class Failed(val reason: String) : DevicePoll
}

/**
 * GitHub's OAuth device flow — the sign-in that works on a phone.
 *
 * The alternative flows are both wrong here. The web flow needs a client *secret*, which cannot
 * live in an APK: anyone can unzip it, and a secret shared with every install is not one. The PKCE
 * flow needs a redirect back into the app, which means a custom scheme any other app can claim.
 * The device flow needs neither — it shows a short code, the user types it into github.com on
 * whatever device they trust, and we poll until they have.
 *
 * It is also the only flow that reads honestly out loud: the screen can say *what* is being asked
 * for and *where* to approve it, and the user is authorising on GitHub's own page rather than on
 * ours. Nothing here ever sees a password.
 */
class GitHubDeviceAuth(
    private val clientId: String = GitHubAuth.CLIENT_ID,
    private val base: String = "https://github.com",
) {

    @Serializable
    private data class CodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String = "https://github.com/login/device",
        @SerialName("expires_in") val expiresIn: Int = 900,
        val interval: Int = 5,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
        val interval: Int? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Asks for a code to show, and says what the token will be allowed to do.
     *
     * **The scope is sent here, not at the token exchange.** GitHub reads it when the code is
     * created, because the consent screen the user is about to read is built from it — asking later
     * would mean asking after they had already agreed to something else. A device flow started with
     * no scope yields a token that can read public data and nothing more, which authenticates
     * perfectly and then fails at the first private repository.
     */
    fun start(): DeviceStart {
        if (clientId.isBlank()) return DeviceStart.Failed("This build has no GitHub app registered")
        return when (
            val body = post(
                "$base/login/device/code",
                mapOf("client_id" to clientId, "scope" to GitHubAuth.SCOPE),
            )
        ) {
            is Post.Broken -> DeviceStart.Failed(body.why)
            is Post.Body ->
                runCatching { json.decodeFromString(CodeResponse.serializer(), body.text) }
                    .getOrNull()
                    ?.let {
                        DeviceStart.Ok(
                            DeviceCode(
                                deviceCode = it.deviceCode,
                                userCode = it.userCode,
                                verificationUri = it.verificationUri,
                                // Never poll faster than GitHub asked, even if it says 0 — a tight
                                // loop against the token endpoint is how an OAuth app gets
                                // rate-limited for everyone using it.
                                intervalSecs = it.interval.coerceAtLeast(1),
                                expiresInSecs = it.expiresIn,
                            )
                        )
                    }
                    ?: DeviceStart.Failed("GitHub sent something we could not read")
        }
    }

    /**
     * One attempt to exchange the device code for a token.
     *
     * GitHub answers `authorization_pending` for as long as the user has not finished, and does so
     * with an HTTP status that has varied over the years — so the body is parsed the same way
     * whatever the status line said, and the *error field* is what decides.
     */
    fun poll(code: DeviceCode): DevicePoll {
        val body = when (
            val result = post(
                "$base/login/oauth/access_token",
                mapOf(
                    "client_id" to clientId,
                    "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
                readErrorBody = true,
            )
        ) {
            is Post.Broken -> return DevicePoll.Offline(result.why)
            is Post.Body -> result.text
        }

        val parsed = runCatching { json.decodeFromString(TokenResponse.serializer(), body) }.getOrNull()
            ?: return DevicePoll.Failed("GitHub sent something we could not read")

        // Whatever GitHub sends is kept, rather than assumed.
        //
        // This used to state that the App had user-token expiry switched off, so no refresh token
        // would arrive and the access token would not lapse — and to throw both fields away on that
        // basis. The assumption was wrong, and wrong in the way an assumption in a comment usually
        // is: silently, and only visible eight hours later, when sync began failing with "not
        // authorized" and the only remedy was to sign in again. Then again eight hours after that.
        //
        // Reading the fields costs nothing and works either way. If the App really does issue
        // permanent tokens, both are null and nothing below ever runs.
        parsed.accessToken?.let {
            return DevicePoll.Token(it, parsed.refreshToken, parsed.expiresIn)
        }

        return when (parsed.error) {
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown((parsed.interval ?: code.intervalSecs + 5).coerceAtLeast(1))
            "expired_token" -> DevicePoll.Failed("The code expired. Start again for a fresh one")
            "access_denied" -> DevicePoll.Failed("Sign-in was cancelled on GitHub")
            // Worth its own sentence: the app is registered but the box is unticked, which no amount
            // of retrying will fix and which reads as a mysterious hang otherwise.
            "device_flow_disabled" ->
                DevicePoll.Failed("This build's GitHub app does not have device flow enabled")
            "unsupported_grant_type", "incorrect_client_credentials" ->
                DevicePoll.Failed("This build's GitHub app is misconfigured")
            else -> DevicePoll.Failed(parsed.errorDescription ?: parsed.error ?: "Sign-in failed")
        }
    }

    /**
     * Trades a refresh token for a fresh access token.
     *
     * The same endpoint as [poll] with a different grant, and the same parsing, because GitHub
     * answers in the same shape. GitHub rotates the refresh token on every use, so the one that
     * comes back has to be stored in place of the one that was sent — keeping the old one means the
     * next refresh fails and the failure looks exactly like the one this exists to prevent.
     */
    fun refresh(refreshToken: String): DevicePoll {
        if (clientId.isBlank()) return DevicePoll.Failed("This build has no GitHub app registered")
        val body = when (
            val result = post(
                "$base/login/oauth/access_token",
                mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                ),
                readErrorBody = true,
            )
        ) {
            // Offline is not a refusal. A refresh that could not be attempted must leave the stored
            // credentials alone, or a tunnel would sign the user out.
            is Post.Broken -> return DevicePoll.Offline(result.why)
            is Post.Body -> result.text
        }

        val parsed = runCatching { json.decodeFromString(TokenResponse.serializer(), body) }.getOrNull()
            ?: return DevicePoll.Failed("GitHub sent something we could not read")

        parsed.accessToken?.let {
            return DevicePoll.Token(it, parsed.refreshToken, parsed.expiresIn)
        }
        return DevicePoll.Failed(parsed.errorDescription ?: parsed.error ?: "Could not refresh the sign-in")
    }

    /**
     * The body, or why there is not one.
     *
     * Carrying the reason rather than returning null is not tidiness. "Could not reach GitHub" with
     * nothing after it is unactionable for the user and undiagnosable for us — the exception was
     * caught and thrown away at exactly the moment it was the only thing worth knowing.
     */
    private sealed interface Post {
        data class Body(val text: String) : Post
        data class Broken(val why: String) : Post
    }

    /**
     * Posts, and tries a second time if the connection broke before GitHub said anything.
     *
     * **Why a retry is safe here, when it usually is not.** A POST may not be replayed in general,
     * because the first one may have taken effect before the connection died. Neither request this
     * class makes has an effect to repeat: asking for a device code twice yields a second code that
     * is simply unused, and polling the token endpoint is a question about state, not a change to
     * it. Both are idempotent in practice, which is what makes this allowed rather than merely
     * convenient.
     *
     * **Why it is needed.** Polling reuses a pooled keep-alive connection every few seconds. When
     * the far end closes an idle one at the same moment it is picked up, the write fails with
     * `unexpected end of stream` or a reset — and Android will not retry a POST by itself, since it
     * cannot know the request is repeatable. The result is one failed poll in an otherwise healthy
     * sign-in, which recovers on its own a few seconds later and is exactly the "having trouble
     * connecting and then it worked" this is chasing. A fresh connection is opened for the second
     * attempt, so a stale socket cannot fail it twice.
     *
     * A retry only for a broken *connection*. Anything GitHub actually answers — including a
     * refusal — is returned untouched, because a second identical question has the same answer and
     * asking it again is just noise.
     */
    private fun post(url: String, form: Map<String, String>, readErrorBody: Boolean = false): Post {
        val first = postOnce(url, form, readErrorBody)
        if (first is Post.Body) return first
        return postOnce(url, form, readErrorBody)
    }

    private fun postOnce(url: String, form: Map<String, String>, readErrorBody: Boolean = false): Post {
        val encoded = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        return try {
            conn.outputStream.use { it.write(encoded.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream
            else if (readErrorBody) conn.errorStream else null
            stream?.bufferedReader()?.readText()
                ?.let { Post.Body(it) }
                ?: Post.Broken("GitHub returned $code with no body")
        } catch (e: IOException) {
            Post.Broken(e.message?.take(120) ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}
