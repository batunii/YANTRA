package ie.napkin.supertasks.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The client id of the GitHub OAuth app this build signs in through.
 *
 * **This is not a secret.** The device flow exists precisely so that an app with no server can
 * authenticate without holding one: the client id is public, the user proves their own identity on
 * github.com, and the token comes back to the device that asked. Shipping it in the APK is the
 * design, not a compromise of it.
 *
 * It is empty here because only the person who owns the OAuth app can create it — register one at
 * github.com/settings/developers, tick **Enable Device Flow**, and paste the client id below. Until
 * then the sign-in path is offline and the app says so instead of failing at the network.
 */
object GitHubAuth {
    const val CLIENT_ID = ""

    /**
     * `repo` is the narrowest scope that can still do what signing in is *for*: create a private
     * repository and push to it. It is a classic OAuth scope, so it reaches every repository the
     * account can touch — which is why the paste-a-token path exists beside it, and why the sign-in
     * screen says out loud what it is asking for. Someone who wants one repo and nothing else should
     * use a fine-grained token and never come through here.
     */
    const val SCOPE = "repo"

    val configured: Boolean get() = CLIENT_ID.isNotBlank()
}

/** What GitHub gave us to show the user: a code, and where to type it. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSecs: Int,
    val expiresInSecs: Int,
)

/** One poll of the token endpoint. */
sealed interface DevicePoll {
    data class Token(val token: String) : DevicePoll
    /** Nobody has typed the code yet. Keep waiting — this is the normal answer, many times over. */
    data object Pending : DevicePoll
    /** We polled too fast and GitHub has told us the new floor. */
    data class SlowDown(val intervalSecs: Int) : DevicePoll
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
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
        val interval: Int? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Asks for a code to show. Null if we could not even get that far. */
    fun start(scope: String = GitHubAuth.SCOPE): DeviceCode? {
        if (clientId.isBlank()) return null
        val body = post("$base/login/device/code", mapOf("client_id" to clientId, "scope" to scope))
            ?: return null
        return runCatching { json.decodeFromString(CodeResponse.serializer(), body) }.getOrNull()
            ?.let {
                DeviceCode(
                    deviceCode = it.deviceCode,
                    userCode = it.userCode,
                    verificationUri = it.verificationUri,
                    // Never poll faster than GitHub asked, even if it says 0 — a tight loop against
                    // the token endpoint is how an OAuth app gets rate-limited for everyone using it.
                    intervalSecs = it.interval.coerceAtLeast(1),
                    expiresInSecs = it.expiresIn,
                )
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
        val body = post(
            "$base/login/oauth/access_token",
            mapOf(
                "client_id" to clientId,
                "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
            readErrorBody = true,
        ) ?: return DevicePoll.Failed("Could not reach GitHub")

        val parsed = runCatching { json.decodeFromString(TokenResponse.serializer(), body) }.getOrNull()
            ?: return DevicePoll.Failed("GitHub sent something we could not read")

        parsed.accessToken?.let { return DevicePoll.Token(it) }

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

    private fun post(url: String, form: Map<String, String>, readErrorBody: Boolean = false): String? {
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
            val stream = if (conn.responseCode in 200..299) conn.inputStream
            else if (readErrorBody) conn.errorStream else null
            stream?.bufferedReader()?.readText()
        } catch (_: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
