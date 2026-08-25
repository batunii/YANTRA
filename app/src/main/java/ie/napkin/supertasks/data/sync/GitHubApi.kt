package ie.napkin.supertasks.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where a workspace points, parsed from whatever the user pasted. */
data class RepoRef(val owner: String, val name: String) {
    val slug: String get() = "$owner/$name"
    val httpsUrl: String get() = "https://github.com/$owner/$name.git"

    companion object {
        /**
         * Accepts the shapes people actually paste: a browser URL, a clone URL, an SSH remote, or
         * just `owner/repo`. Anything else is null rather than a guess — pointing a workspace at the
         * wrong repository is not a mistake worth being clever about.
         */
        fun parse(input: String): RepoRef? {
            val cleaned = input.trim().removeSuffix("/").removeSuffix(".git")
            val path = when {
                cleaned.startsWith("git@") -> cleaned.substringAfter(':', "")
                cleaned.contains("://") -> runCatching { URL(cleaned).path }.getOrNull()?.trimStart('/')
                else -> cleaned
            } ?: return null
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.size != 2) return null
            return RepoRef(parts[0], parts[1])
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
 * The smallest useful slice of the GitHub API: who you are, and whether you can push there.
 *
 * Both answers are needed before a workspace is created rather than after. The login is not a
 * nicety — it is the conflict tiebreak and the value behind `@assignee`, so a workspace without one
 * cannot arbitrate deterministically. And discovering you have no push access *after* a week of
 * local commits is a much worse conversation than discovering it while pasting the URL.
 *
 * Uses `HttpURLConnection` on purpose. It is enough for two GET requests, and an HTTP client is a
 * large dependency to add to an app whose whole transport is otherwise JGit's.
 */
class GitHubApi(private val base: String = "https://api.github.com") {

    @Serializable
    private data class User(val login: String)

    @Serializable
    private data class Repo(
        @SerialName("default_branch") val defaultBranch: String = "main",
        val permissions: Permissions? = null,
    )

    @Serializable
    private data class Permissions(val push: Boolean = false, val admin: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true }

    /** The token's owner, which becomes this device's identity in the workspace. */
    fun viewer(token: String): String? =
        get("$base/user", token)?.let {
            runCatching { json.decodeFromString(User.serializer(), it).login }.getOrNull()
        }

    fun check(ref: RepoRef, token: String): RepoCheck {
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
