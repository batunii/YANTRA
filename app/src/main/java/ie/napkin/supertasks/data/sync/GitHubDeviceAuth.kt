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

    /** The App's URL slug, for sending someone to its install page. */
    const val APP_SLUG = "yantra"

    val configured: Boolean get() = CLIENT_ID.isNotBlank()

    /**
     * Where to install the App.
     *
     * A user token with no installation is not broken — it authenticates fine and can see nothing at
     * all, which is the most confusing possible state to leave someone in. So the sign-in screen
     * checks for an installation and sends them here when there is none.
     */
    val installUrl: String get() = "https://github.com/apps/$APP_SLUG/installations/new"

    /**
     * GitHub's new-repository form, with the name and visibility already filled in.
     *
     * This is why the app never asks anyone to create an access token. Repository *creation* has no
     * GitHub App permission for a personal account — there is no fine-grained equivalent of the old
     * `repo` scope — so rather than demand a token broad enough to create one, the app opens the form
     * GitHub already has and lets the user press the button. They type nothing.
     *
     * Deliberately the real browser and never a WebView: in an embedded WebView there is no URL bar
     * to check, and the session is not shared, so the user would be asked for their GitHub password
     * inside our app — which is indistinguishable from how credential phishing works.
     */
    fun newRepoUrl(name: String, description: String = "Tasks, kept by Yantra"): String {
        fun esc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "https://github.com/new?name=${esc(name)}&visibility=private&description=${esc(description)}"
    }

    /** Where someone invites people to a repository. Also the browser, and for the same reason. */
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

    /**
     * Asks for a code to show. Null if we could not even get that far.
     *
     * **No scope is sent, and that is not an omission.** A GitHub App's user token does not use
     * scopes at all: its reach is the App's configured permissions intersected with what the user
     * themselves can access. So there is nothing to ask for here — the consent screen shows what the
     * App was registered to want, and sending a scope would be describing the wrong permission model.
     */
    fun start(): DeviceCode? {
        if (clientId.isBlank()) return null
        val body = post("$base/login/device/code", mapOf("client_id" to clientId))
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

        // No refresh handling, because the App is registered with user-token expiry switched off, so
        // GitHub issues no refresh token and the access token does not lapse. That removes the
        // *scheduled* failure, not every failure: a revoked authorisation, an uninstalled App, or a
        // Keystore key invalidated by a device restore all still end in one place — a token that no
        // longer works and a screen that asks you to sign in again.
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
