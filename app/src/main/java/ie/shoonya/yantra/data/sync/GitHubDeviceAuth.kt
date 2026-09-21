package ie.shoonya.yantra.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * How this build asks GitHub for a token, and what that token is allowed to see.
 *
 * **The client ids here are not secrets.** The device flow exists precisely so that an app with no
 * server can authenticate without holding one: the id is public, the user proves their own identity
 * on github.com, and the token comes back to the device that asked. Shipping them in the APK is the
 * design, not a compromise of it.
 *
 * **There are two of them because neither one is right for everybody.** This app signed in through a
 * GitHub App, moved to an OAuth app when a two-device bug was finally pinned down, and the move cost
 * something real — so both are kept and the choice is the user's. What separates them is written out
 * on [Method], because it is the only thing anyone needs to read to choose.
 */
object GitHubAuth {

    /**
     * The two ways in.
     *
     * **[Full] is the default because it is the one that survives a second device.** A GitHub App
     * holds at most *two* user access tokens per user, and issuing a third silently revokes the
     * oldest. Two phones fill both slots, so signing in anywhere a third time — including signing in
     * again on a phone that already had a token — kills whichever device had been quiet longest. It
     * presents as "sign in again" on a device nobody touched, days later, with nothing in any log to
     * connect it to the sign-in that caused it. That cap is not documented anywhere; it was found by
     * minting tokens against the real App until one died. An OAuth app's limit is ten per
     * user/application/scope, which is documented, and which two phones do not come close to.
     *
     * **[Restricted] is kept because that cap is the only thing wrong with it.** A GitHub App is
     * installed on the repositories you choose and can reach nothing else — not your employer's
     * code, not the private repository you would rather no phone could read. [Full] cannot be
     * narrowed that way: `repo` is read and write to every repository you own, and OAuth has no
     * fine-grained middle ground. On a single device, [Restricted] is the better bargain, and
     * pretending otherwise would be choosing for the user.
     *
     * What [Restricted] costs beyond the cap: a GitHub App cannot create a repository in a personal
     * account at all, so making one means a trip to GitHub's own form and a tap there. Every screen
     * that offers to create one says so rather than showing a button that cannot work.
     */
    enum class Method(
        /** Public, and different per method: these are two separate registrations on GitHub. */
        val clientId: String,
        /**
         * What the token may do, sent with the device-code request.
         *
         * Empty for [Restricted]: a GitHub App's permissions are fixed at registration and chosen
         * again at each installation, so there is nothing to ask for here. `repo` for [Full] is the
         * only scope that can create a repository, and it appears in the limit GitHub enforces —
         * ten tokens per user, per application, *per scope*. Changing the string starts a fresh set
         * of ten and strands every token already issued under the old one.
         */
        val scope: String,
    ) {
        Full(clientId = "Ov23liWz2CApMbchpQOg", scope = "repo"),
        Restricted(clientId = "Iv23lijaR2qLqzo9ALWw", scope = ""),
        ;

        /** False when this half was never registered, in which case it is not offered at all. */
        val configured: Boolean get() = clientId.isNotBlank()

        /**
         * Whether a fresh token can see anything yet.
         *
         * A GitHub App reaches nothing until it is installed somewhere, and a user token with no
         * installation is not broken — it authenticates perfectly and can see nothing at all, which
         * is the most confusing state to leave someone in. An OAuth token has no such step.
         */
        val needsInstall: Boolean get() = this == Restricted

        /** Only `repo` can `POST /user/repos`. The other has to send the user to GitHub's form. */
        val makesRepos: Boolean get() = this == Full

        /** The name of the bargain, for the place where it is chosen. */
        val title: String get() = when (this) {
            Full -> "All my repositories"
            Restricted -> "Only the ones I pick"
        }

        /** The rest of the bargain, in the two sentences that actually decide it. */
        val summary: String get() = when (this) {
            Full -> "Works on as many devices as you like, and makes repositories without leaving " +
                "the app. Yantra can read and write every repository you own."
            Restricted -> "Yantra sees only the repositories you install it on. Two devices at " +
                "most — signing in on a third ends the oldest — and new repositories are made on " +
                "GitHub."
        }
    }

    /** What the sign-in button uses when nobody has said otherwise. */
    val DEFAULT = Method.Full

    val configured: Boolean get() = Method.entries.any { it.configured }

    /** The methods this build can actually offer, which on a build with one id is one of them. */
    fun offered(): List<Method> = Method.entries.filter { it.configured }

    /**
     * The App's URL slug — the last segment of github.com/apps/<slug>, not its display name.
     *
     * Must match the registration exactly or the install link 404s, which is a dead end with no
     * error: the browser opens, says the page does not exist, and the app goes on waiting for an
     * installation that can never arrive.
     */
    const val APP_SLUG = "yantra-tasks"

    /**
     * Where someone installs the App on the repositories they want Yantra to see.
     *
     * Only ever reached from [Method.Restricted]. Aimed at the account that just signed in, because
     * without the id GitHub shows a chooser first — one page whose only real answer is the account
     * already in the address bar — and installing costs two taps in a browser instead of one.
     */
    fun installUrl(targetId: Long? = null): String {
        val base = "https://github.com/apps/$APP_SLUG/installations"
        return if (targetId != null) "$base/new/permissions?suggested_target_id=$targetId"
        else "$base/new"
    }

    /**
     * GitHub's new-repository form, with the name and visibility already filled in.
     *
     * The [Method.Restricted] answer to "make me a repository", because there is no GitHub App
     * permission for creating one in a personal account — no fine-grained equivalent of `repo`
     * exists. Rather than demand a token broad enough, the app opens the form GitHub already has.
     * The user types nothing.
     *
     * Deliberately the real browser and never a WebView: in an embedded WebView there is no URL bar
     * to check and the session is not shared, so the user would be asked for their GitHub password
     * inside our app — which is indistinguishable from how credential phishing works.
     */
    fun newRepoUrl(name: String, description: String = "Tasks, kept by Yantra"): String {
        fun esc(s: String) = URLEncoder.encode(s, "UTF-8")
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
    /**
     * Which registration this code belongs to.
     *
     * Carried on the code rather than passed to [GitHubDeviceAuth.poll] separately, so the two
     * cannot drift. A device code is only meaningful to the client id that asked for it, and polling
     * with the other one answers `incorrect_client_credentials` — a refusal that reads like a
     * misconfigured build rather than like the mix-up it is.
     */
    val method: GitHubAuth.Method = GitHubAuth.DEFAULT,
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
    private val base: String = "https://github.com",
    /**
     * Which id to use for a method. A function rather than a value because there are now two, and
     * because a test needs to point both at a server it controls.
     */
    private val clientId: (GitHubAuth.Method) -> String = { it.clientId },
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
    fun start(method: GitHubAuth.Method = GitHubAuth.DEFAULT): DeviceStart {
        val id = clientId(method)
        if (id.isBlank()) return DeviceStart.Failed("This build has no GitHub app registered")
        return when (
            val body = post(
                "$base/login/device/code",
                // No `scope` key at all for a GitHub App, rather than an empty one. Its permissions
                // were fixed when it was registered and are chosen again at each installation, so
                // there is nothing to ask for — and an empty scope sent to the OAuth endpoint is a
                // different request from one that omits it.
                buildMap {
                    put("client_id", id)
                    if (method.scope.isNotBlank()) put("scope", method.scope)
                },
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
                                method = method,
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
                    "client_id" to clientId(code.method),
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
    fun refresh(refreshToken: String, method: GitHubAuth.Method = GitHubAuth.DEFAULT): DevicePoll {
        val id = clientId(method)
        if (id.isBlank()) return DevicePoll.Failed("This build has no GitHub app registered")
        val body = when (
            val result = post(
                "$base/login/oauth/access_token",
                mapOf(
                    "client_id" to id,
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
