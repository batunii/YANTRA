package ie.shoonya.yantra.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
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
         * Accepts the shapes people actually paste: a browser URL from anywhere inside the
         * repository, a clone URL, an SSH remote, or just `owner/repo`.
         *
         * **Deep links resolve rather than being refused.** This used to insist on exactly two path
         * segments, so a URL copied from the address bar while looking at a file, an issue or a
         * pull request — which is where you are when you decide to link the thing — was rejected as
         * not a GitHub repository at all. It cost several attempts to link one workspace, and the
         * message blamed the address rather than saying what was wrong with it.
         *
         * The old reasoning was that truncating an issue link would silently link the workspace to
         * something the person had not asked for. But the first two segments of a github.com path
         * *are* the repository — `/batunii/YANTRA/issues/4` cannot mean any repository other than
         * `batunii/YANTRA`. There was never an ambiguity to protect against; the protection was
         * against the user's intent, which is answered far better by showing them the `owner/name`
         * this resolved to before anything is linked.
         *
         * Still null rather than a guess for anything with no repository in it: a bare name with no
         * owner, a user's profile page, a sentence.
         */
        fun parse(input: String): RepoRef? {
            // Query and fragment go first, so `?tab=readme-ov-file` — which github.com puts in the
            // address bar on the repository's own page — does not become part of the name on the
            // slug path, where there is no URL parser to strip it.
            val cleaned = input.trim()
                .substringBefore('?')
                .substringBefore('#')
                .trim()
                .removeSuffix("/")
                .removeSuffix(".git")
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
            if (parts.size < 2) return null
            // `.git` again, because a deep link's repository segment can carry it in the middle of
            // the path where `removeSuffix` never looked.
            return RepoRef(parts[0], parts[1].removeSuffix(".git"), host.ifBlank { "github.com" })
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
 * Whether the stored sign-in still works.
 *
 * The half of the old install check that was never about installing, and applies to both methods.
 * A token that has been revoked, or one whose Keystore key did not survive a device restore, needs
 * saying out loud rather than surfacing later as a failed push. [Failed] is kept apart because a
 * dead network is not a dead sign-in.
 *
 * [InstallState] is the other half, and is asked only under [GitHubAuth.Method.Restricted].
 */
sealed interface SignInState {
    data object Ok : SignInState
    data object Unauthorized : SignInState
    data class Failed(val message: String) : SignInState
}

/**
 * Whether this build's GitHub App is installed for the signed-in user.
 *
 * Only ever asked under [GitHubAuth.Method.Restricted] — an OAuth app has no installation to be
 * missing. Worth a type of its own rather than a boolean, because the three ways of not being
 * installed need three different things said. [Absent] is a browser trip. [Unauthorized] is a
 * sign-in. [Failed] is a network that will probably work in a minute and should not be dressed up
 * as either.
 */
sealed interface InstallState {
    data object Installed : InstallState
    data object Absent : InstallState
    /** The token no longer works: revoked, uninstalled, or undecryptable on this device. */
    data object Unauthorized : InstallState
    data class Failed(val message: String) : InstallState
}

/** What GitHub did when asked to make a repository. */
sealed interface RepoCreate {
    data class Ok(val ref: RepoRef, val defaultBranch: String) : RepoCreate
    /**
     * A repository of that name is already there.
     *
     * Its own answer rather than a [Failed] carrying GitHub's wording, because it is the one
     * failure here the user can act on without leaving the screen: pick another name, or go to the
     * other tab and join the one that exists. It is also the likeliest — people name their task
     * repository the obvious thing, and then do it again on a second device.
     */
    data object Exists : RepoCreate
    /**
     * The token cannot make one.
     *
     * Two different reasons wearing the same status code: a sign-in that has been revoked, and a
     * sign-in through [GitHubAuth.Method.Restricted], which can never create a repository in a
     * personal account however healthy it is. The screens know which method they are on, so they
     * say the right one — this only reports that GitHub said no.
     */
    data object Unauthorized : RepoCreate
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
 * [createRepo] is the one call that writes, and it is new: this file used to be all GETs because a
 * GitHub App cannot create a repository in a personal account, so the app opened GitHub's own
 * new-repository form and asked the user to press the button. An OAuth app's `repo` scope can do it
 * directly, which removes a browser trip, a return-to-the-app, and the polling that watched for a
 * repository to appear. Inviting people still happens on GitHub's own pages, because it needs
 * Administration rights this app has no business holding.
 *
 * Uses `HttpURLConnection` on purpose. It is enough for a handful of requests, and an HTTP client is
 * a large dependency to add to an app whose whole transport is otherwise JGit's.
 */
open class GitHubApi(private val base: String = "https://api.github.com") {

    @Serializable
    private data class User(val login: String, val id: Long = 0)

    @Serializable
    private data class Repo(
        @SerialName("default_branch") val defaultBranch: String = "main",
        val permissions: Permissions? = null,
    )

    /**
     * No default on [private], and that is load-bearing.
     *
     * kotlinx does not serialise a property that still holds its default, so `private = true` as a
     * default would be left out of the body entirely — and GitHub's own default for a repository
     * created through the API is public. The repository would have been made, the workspace would
     * have attached to it, everything would have worked, and someone's task list would have been
     * world-readable with nothing on any screen saying so.
     */
    @Serializable
    private data class NewRepo(val name: String, val description: String, val private: Boolean)

    @Serializable
    private data class CreatedRepo(
        @SerialName("full_name") val fullName: String = "",
        @SerialName("default_branch") val defaultBranch: String = "main",
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
     * Whether [token] is still one GitHub will answer.
     *
     * `/user` because it is the cheapest authenticated call there is and needs no scope at all: the
     * question is only whether the token is alive, and asking it against a repository would confuse
     * a revoked sign-in with a repository that has been renamed or deleted.
     */
    open fun signInState(token: String): SignInState {
        val conn = open("$base/user", token)
        return try {
            when (val code = conn.responseCode) {
                200 -> SignInState.Ok
                401 -> SignInState.Unauthorized
                // **403 is not only "revoked".** GitHub answers 403 for a secondary rate limit too,
                // and reading that as a dead sign-in is expensive in a way nothing on the screen
                // would explain: the user is told to sign in again, does, and burns one of the ten
                // tokens GitHub will hold for this app — to fix a token that was never broken. Ten
                // is roomy, but it is the same mistake that made a cap of two fatal, and the fix is
                // to believe GitHub's own header rather than guess from the status line.
                403 -> {
                    val exhausted = conn.getHeaderField("x-ratelimit-remaining") == "0" ||
                        conn.getHeaderField("retry-after") != null
                    if (exhausted) SignInState.Failed("GitHub is rate-limiting this app — try later")
                    else SignInState.Unauthorized
                }
                else -> SignInState.Failed("GitHub returned $code")
            }
        } catch (e: IOException) {
            SignInState.Failed(e.message ?: "could not reach GitHub")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Whether this build's App is installed for whoever owns [token].
     *
     * A user token with no installation is the trap this exists to catch: it authenticates
     * perfectly, `/user` answers, and every repository request comes back empty or 404 — because a
     * user token's reach is the App's permissions *intersected* with the user's own, and an App
     * installed nowhere contributes nothing to that intersection. Without this check the app looks
     * signed in and cannot explain why nothing works.
     *
     * It came back with [GitHubAuth.Method.Restricted]. It was deleted when the app moved to an
     * OAuth app, on the reasoning that there was no installation any more — true of that method and
     * of nothing else, which is what made deleting it a decision rather than a cleanup.
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

    /**
     * Makes a private repository for tasks, and says where it landed.
     *
     * Private without asking. A task list is the most personal thing this app holds, and a public
     * repository cannot be made private again by anyone who is not an admin of it — so the safe
     * default is the one that can be widened later rather than the one that cannot be narrowed.
     *
     * The name is sent as the user typed it. GitHub does its own normalising (spaces become dashes,
     * and it will say so in the response), and guessing at that here would mean the app telling
     * someone their repository is called one thing while GitHub calls it another — which then fails
     * at the first push, a long way from the screen that caused it.
     */
    open fun createRepo(name: String, token: String, description: String = "Tasks, kept by Yantra"): RepoCreate {
        val body = runCatching {
            json.encodeToString(NewRepo.serializer(), NewRepo(name, description, private = true))
        }.getOrNull() ?: return RepoCreate.Failed("could not ask for that name")

        val conn = open("$base/user/repos", token, "POST")
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            when (val code = conn.responseCode) {
                201 -> {
                    val made = json.decodeFromString(
                        CreatedRepo.serializer(),
                        conn.inputStream.bufferedReader().readText(),
                    )
                    // Parsed back rather than assembled from what we sent, because what we sent is
                    // not necessarily what exists: GitHub rewrites a name it does not like, and the
                    // workspace has to point at the repository that is actually there.
                    RepoRef.parse(made.fullName)
                        ?.let { RepoCreate.Ok(it, made.defaultBranch) }
                        ?: RepoCreate.Failed("GitHub made it but would not say where")
                }
                401, 403 -> RepoCreate.Unauthorized
                // 422 is every kind of "no" this endpoint gives — a name already taken, a name made
                // only of punctuation, a plan limit. Only the first is worth its own answer, and
                // GitHub names it in the body rather than in the status.
                422 -> {
                    val why = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    if (why.contains("already exists", ignoreCase = true)) RepoCreate.Exists
                    else RepoCreate.Failed("GitHub would not make that one")
                }
                else -> RepoCreate.Failed("GitHub returned $code")
            }
        } catch (e: IOException) {
            RepoCreate.Failed(e.message ?: "could not reach GitHub")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Who can push to a repository — the people a task in it can be assigned to.
     *
     * A GET like everything else here, and for the same reason: inviting someone needs a permission
     * far heavier than this App asks for, and happens on GitHub's own pages. This only reads the
     * answer.
     *
     * The result is typed rather than a nullable list, and that is the whole point of it. This
     * endpoint has more ways to say no than any other call in this file, and they need three
     * different things said to the user: a token that has lapsed is a sign-in, a token that is fine
     * but not allowed to see the roster is a permission on GitHub, and a dead network is neither.
     * Collapsing them into null produced "could not reach GitHub" while the phone was online and
     * GitHub had answered perfectly promptly with a refusal.
     *
     * One page. A hundred collaborators on a task repository is not the case worth paginating for,
     * and the picker searches what it has rather than scrolling it.
     */
    open fun collaborators(ref: RepoRef, token: String): Collaborators {
        val conn = open("$base/repos/${ref.owner}/${ref.name}/collaborators?per_page=100", token)
        return try {
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().readText()
                    val logins = runCatching {
                        json.decodeFromString(ListSerializer(User.serializer()), body).map { it.login }
                    }.getOrDefault(emptyList())
                    Collaborators.Ok(logins.filter { it.isNotBlank() }.distinct())
                }
                401 -> Collaborators.Unauthorized
                // 403 and 404 are the same answer wearing different clothes. GitHub hides what you
                // may not see rather than admitting it exists, so a token without the permission
                // this endpoint wants gets a 404 for a repository it can otherwise read and push
                // to — which is exactly the case a task app hits, because listing collaborators
                // needs a heavier permission than syncing files does.
                403, 404 -> Collaborators.NotPermitted(code)
                else -> Collaborators.Failed("GitHub returned $code")
            }
        } catch (e: IOException) {
            Collaborators.Failed(e.message ?: "could not reach GitHub")
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

    private fun open(url: String, token: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
}

/**
 * What GitHub said when asked who can push to a repository.
 *
 * [NotPermitted] is the interesting one and is not an error: the repository is there, the token
 * works, and GitHub will not list its people for this token. Everything the picker does still
 * works — the signed-in account and every login already on a task are read out of the index — so
 * this is a note, not a failure.
 */
sealed interface Collaborators {
    data class Ok(val logins: List<String>) : Collaborators
    data object Unauthorized : Collaborators
    /** [code] is 403 or 404 — kept because which one it is says *why* it is refused. */
    data class NotPermitted(val code: Int) : Collaborators
    data class Failed(val message: String) : Collaborators
}

/** Who a token belongs to. The id is what aims an installation link at one account. */
data class GitHubAccount(val login: String, val id: Long)
