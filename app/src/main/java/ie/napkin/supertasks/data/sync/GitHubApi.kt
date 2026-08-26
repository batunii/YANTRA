package ie.napkin.supertasks.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where a workspace points, parsed from whatever the user pasted.
 *
 * [host] is kept rather than assumed. Nothing about the file format or the sync engine is
 * GitHub-specific — only the API check is — and a self-hosted git server is a reasonable thing to
 * want. Defaulting the clone URL to github.com would mean someone pasting their company's git
 * address gets a workspace that pushes to a github.com repository of the same name, which either
 * fails confusingly or succeeds somewhere they did not mean.
 */
data class RepoRef(val owner: String, val name: String, val host: String = "github.com") {
    val slug: String get() = "$owner/$name"
    val httpsUrl: String get() = "https://$host/$owner/$name.git"

    companion object {
        /**
         * Accepts the shapes people actually paste: a browser URL, a clone URL, an SSH remote, or
         * just `owner/repo`. Anything else is null rather than a guess — pointing a workspace at the
         * wrong repository is not a mistake worth being clever about.
         */
        fun parse(input: String): RepoRef? {
            val cleaned = input.trim().removeSuffix("/").removeSuffix(".git")
            var host = "github.com"
            val path = when {
                cleaned.startsWith("git@") -> {
                    host = cleaned.removePrefix("git@").substringBefore(':')
                    cleaned.substringAfter(':', "")
                }
                cleaned.contains("://") -> runCatching {
                    val url = URL(cleaned)
                    host = url.host + if (url.port > 0) ":${url.port}" else ""
                    url.path
                }.getOrNull()?.trimStart('/')
                // Bare `owner/repo` carries no host, so the only sensible reading is the default.
                else -> cleaned
            } ?: return null
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.size != 2) return null
            return RepoRef(parts[0], parts[1], host.ifBlank { "github.com" })
        }
    }
}

/** What the API said about a repository, or why it could not say. */
sealed interface RepoCheck {
    data class Ok(val ref: RepoRef, val canPush: Boolean, val defaultBranch: String) : RepoCheck
    data object NotFound : RepoCheck
    data object Unauthorized : RepoCheck
    data class Failed(val message: String) : RepoCheck
}

/**
 * Whether this build's GitHub App is installed for the signed-in user.
 *
 * Worth a type of its own rather than a boolean, because the three ways of not being installed need
 * three different things said. [Absent] is a browser trip. [Unauthorized] is a sign-in. [Failed] is
 * a network that will probably work in a minute and should not be dressed up as either.
 */
sealed interface InstallState {
    data object Installed : InstallState
    data object Absent : InstallState
    /** The token no longer works: revoked, uninstalled, or undecryptable on this device. */
    data object Unauthorized : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * The smallest useful slice of the GitHub API: who you are, and whether you can push there.
 *
 * Both answers are needed before a workspace is created rather than after. The login is not a
 * nicety — it is the conflict tiebreak and the value behind `@assignee`, so a workspace without one
 * cannot arbitrate deterministically. And discovering you have no push access *after* a week of
 * local commits is a much worse conversation than discovering it while pasting the URL.
 *
 * There is deliberately nothing here that writes. Creating a repository and inviting people both
 * happen in the browser on GitHub's own pages — creation because a GitHub App cannot do it for a
 * personal account at all, invites because they need a permission far heavier than the one the App
 * asks for. So this file only ever asks questions, which is also why every call is a GET.
 *
 * Uses `HttpURLConnection` on purpose. It is enough for three GET requests, and an HTTP client is a
 * large dependency to add to an app whose whole transport is otherwise JGit's.
 */
open class GitHubApi(private val base: String = "https://api.github.com") {

    @Serializable
    private data class User(val login: String, val id: Long = 0)

    @Serializable
    private data class Repo(
        @SerialName("default_branch") val defaultBranch: String = "main",
        val permissions: Permissions? = null,
    )

    @Serializable
    private data class Installations(val installations: List<Installation> = emptyList())

    @Serializable
    private data class Installation(val id: Long, @SerialName("app_slug") val appSlug: String = "")

    @Serializable
    private data class Permissions(val push: Boolean = false, val admin: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The token's owner, which becomes this device's identity in the workspace.
     *
     * Open, along with [check], so a test can answer these without the network. Everything below
     * them is real git against a real repository; these two are the only calls that need GitHub to
     * exist, and stubbing them is what lets the link and attach paths be tested at all.
     */
    open fun viewer(token: String): String? = account(token)?.login

    /**
     * The token's owner, with the numeric id GitHub needs to aim an installation link at them.
     *
     * The id is not cosmetic. `apps/<slug>/installations/new` lands on a chooser — "which account
     * are you installing this on?" — even when the answer can only be the one account that just
     * signed in. `suggested_target_id` skips it, which is the difference between two taps in a
     * browser and one.
     */
    open fun account(token: String): GitHubAccount? =
        get("$base/user", token)?.let { body ->
            runCatching { json.decodeFromString(User.serializer(), body) }.getOrNull()
        }?.takeIf { it.id > 0 }?.let { GitHubAccount(it.login, it.id) }

    open fun check(ref: RepoRef, token: String): RepoCheck {
        val conn = open("$base/repos/${ref.owner}/${ref.name}", token)
        return try {
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().readText()
                    val repo = json.decodeFromString(Repo.serializer(), body)
                    RepoCheck.Ok(
                        ref = ref,
                        // A private repo you can only read reports push = false, and so does a
                        // public one you have no rights to. Both are the same problem for us.
                        canPush = repo.permissions?.push == true || repo.permissions?.admin == true,
                        defaultBranch = repo.defaultBranch,
                    )
                }
                401, 403 -> RepoCheck.Unauthorized
                404 -> RepoCheck.NotFound      // or private and invisible to this token; same fix
                else -> RepoCheck.Failed("GitHub returned $code")
            }
        } catch (e: IOException) {
            RepoCheck.Failed(e.message ?: "could not reach GitHub")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Whether this build's App is installed for whoever owns [token].
     *
     * A user token with no installation is the trap this exists to catch: it authenticates perfectly,
     * `/user` answers, and every repository request comes back empty or 404 — because a user token's
     * reach is the App's permissions *intersected* with the user's own, and an App installed nowhere
     * contributes nothing to that intersection. Without this check the app would look signed in and
     * be unable to explain why nothing worked.
     */
    open fun installState(token: String, appSlug: String): InstallState {
        val conn = open("$base/user/installations", token)
        return try {
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().readText()
                    val found = runCatching {
                        json.decodeFromString(Installations.serializer(), body).installations
                    }.getOrDefault(emptyList())
                    // Matched by slug, not by count: someone may have other GitHub Apps installed,
                    // and any of them would otherwise read as ours.
                    if (found.any { it.appSlug == appSlug }) InstallState.Installed
                    else InstallState.Absent
                }
                401, 403 -> InstallState.Unauthorized
                else -> InstallState.Failed("GitHub returned ${conn.responseCode}")
            }
        } catch (e: IOException) {
            InstallState.Failed(e.message ?: "could not reach GitHub")
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, token: String): String? {
        val conn = open(url, token)
        return try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } catch (_: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
}

/** Who a token belongs to. The id is what aims an installation link at one account. */
data class GitHubAccount(val login: String, val id: Long)
