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

/** What happened when we asked GitHub to make a repository. */
sealed interface RepoCreate {
    data class Ok(val ref: RepoRef) : RepoCreate
    /**
     * The name is already taken by this account. Not an error worth stopping on: the repository the
     * user asked for exists, and linking to it is almost certainly what they meant.
     */
    data class Taken(val ref: RepoRef) : RepoCreate
    data class Failed(val message: String) : RepoCreate
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
open class GitHubApi(private val base: String = "https://api.github.com") {

    @Serializable
    private data class User(val login: String)

    @Serializable
    private data class Repo(
        @SerialName("default_branch") val defaultBranch: String = "main",
        val permissions: Permissions? = null,
    )

    @Serializable
    private data class NewRepo(
        val name: String,
        val description: String,
        val private: Boolean,
        @SerialName("auto_init") val autoInit: Boolean = false,
    )

    @Serializable
    private data class Permissions(val push: Boolean = false, val admin: Boolean = false)

    // encodeDefaults, because the defaults are the point. `auto_init: false` is what keeps a new
    // repository empty, and leaving it out to be inferred from GitHub's own default would make a
    // behaviour this design depends on into something GitHub could change without telling anyone.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The token's owner, which becomes this device's identity in the workspace.
     *
     * Open, along with [check], so a test can answer these without the network. Everything below
     * them is real git against a real repository; these two are the only calls that need GitHub to
     * exist, and stubbing them is what lets the link and attach paths be tested at all.
     */
    open fun viewer(token: String): String? =
        get("$base/user", token)?.let {
            runCatching { json.decodeFromString(User.serializer(), it).login }.getOrNull()
        }

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
     * Makes a repository for the signed-in account.
     *
     * Private by default, and deliberately so — a task list is a diary of what someone has not done
     * yet, and defaulting that to world-readable is not a mistake the app gets to make on their
     * behalf. `auto_init` is off: an empty repository is exactly what the task branch wants, since
     * an initial commit on `main` would be a second root the orphan branch has to step around.
     */
    fun createRepo(
        name: String,
        token: String,
        private: Boolean = true,
        description: String = "Tasks, kept by Yantra",
    ): RepoCreate {
        val owner = viewer(token) ?: return RepoCreate.Failed("That token was rejected by GitHub")
        val body = json.encodeToString(
            NewRepo.serializer(),
            NewRepo(name = name, description = description, private = private),
        )
        val conn = open("$base/user/repos", token, method = "POST", body = body)
        return try {
            when (val code = conn.responseCode) {
                201 -> RepoCreate.Ok(RepoRef(owner, name))
                // 422 is the whole family of "we will not make that": already exists, or the name is
                // not one GitHub accepts. Only the first has a sensible next step.
                422 -> {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    if (err.contains("already exists")) RepoCreate.Taken(RepoRef(owner, name))
                    else RepoCreate.Failed("GitHub will not accept the name \"$name\"")
                }
                401, 403 -> RepoCreate.Failed("This sign-in cannot create repositories")
                else -> RepoCreate.Failed("GitHub returned $code")
            }
        } catch (e: IOException) {
            RepoCreate.Failed(e.message ?: "could not reach GitHub")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Invites someone to a repository with push rights — what makes a workspace shared.
     *
     * The invitation is theirs to accept, so a true return means "asked", never "they are in". 201
     * is a fresh invitation and 204 means they already had access; both are the outcome the caller
     * wanted, and telling them apart would be a distinction without a difference.
     */
    fun addCollaborator(ref: RepoRef, login: String, token: String): Boolean {
        val conn = open(
            "$base/repos/${ref.owner}/${ref.name}/collaborators/$login",
            token, method = "PUT", body = """{"permission":"push"}""",
        )
        return try {
            conn.responseCode == 201 || conn.responseCode == 204
        } catch (_: IOException) {
            false
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

    private fun open(
        url: String,
        token: String,
        method: String = "GET",
        body: String? = null,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 15_000
            readTimeout = 15_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
}
