package ie.napkin.supertasks.data.people

import android.content.Context
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.sync.Collaborators
import ie.napkin.supertasks.data.sync.Credentials
import ie.napkin.supertasks.data.sync.GitHubApi
import ie.napkin.supertasks.data.sync.RepoRef
import ie.napkin.supertasks.data.workspace.WorkspaceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Somebody a task can be given to.
 *
 * [isYou] rather than comparing logins at every call site: which account is signed in is a fact
 * about the device, and a screen that has to look it up in order to say "you" will eventually
 * forget to.
 *
 * [onRepo] is deliberately three-valued. False means GitHub has told us who can push here and this
 * person is not among them — an assignment nobody will ever see. **Null means we do not know**, and
 * that is a different thing entirely: no roster has been fetched for this workspace, because the
 * phone has been offline, or nobody has pressed Collaborators, or GitHub refuses to list them for
 * this token. Collapsing null into false would put a warning on every name on a repository whose
 * roster simply has not arrived, which teaches people to ignore the warning that matters.
 */
data class Person(
    val login: String,
    val isYou: Boolean = false,
    val inUse: Boolean = false,
    val onRepo: Boolean? = null,
)

/**
 * Who can be put on a task, per workspace.
 *
 * Three sources, unioned, in descending order of certainty and ascending order of how much has to
 * work for them to exist:
 *
 *  1. **The signed-in login.** Always there, needs nothing, and covers the overwhelmingly common
 *     case of assigning something to yourself.
 *  2. **Logins already written on tasks**, read out of the index. Also needs nothing — they are in
 *     the files — and means the second task you give someone costs no network either.
 *  3. **The repository's collaborators**, from GitHub, cached.
 *
 * The ordering is the design. This is an offline-first app whose whole storage story is "the files
 * are the truth", and a picker that is empty until a request comes back would be a screen that does
 * not work on a train. The network only ever *adds* names, and free-typing a login is always
 * allowed — so someone can be assigned before the roster has ever been fetched, and the fetch is an
 * improvement rather than a gate.
 *
 * ## Where the roster is cached, and why not in the repo
 *
 * In app-private prefs, keyed by workspace — deliberately **not** in `meta/`. Everything in a
 * workspace directory is committed and pulled by everyone, and a collaborator list is derived
 * remote state that each device can re-fetch for itself. Putting it in the repo would turn "someone
 * joined the project" into a merge conflict in a file nobody edits, which is exactly the kind of
 * churn the format is designed to avoid.
 */
class People(
    context: Context,
    private val db: AppDatabase,
    private val credentials: Credentials,
    private val registry: WorkspaceRegistry,
    private val api: GitHubApi = GitHubApi(),
) {

    private val prefs = context.getSharedPreferences("yantra_people", Context.MODE_PRIVATE)

    /** Bumped after a refresh so the flows below recompute without observing SharedPreferences. */
    private val cacheVersion = MutableStateFlow(0)

    /**
     * Everyone [workspaceId] could assign to, you first and then alphabetically.
     *
     * You first because it is the answer most of the time and a list you have to read to find
     * yourself in is a list that is slower than typing.
     */
    fun known(workspaceId: String): Flow<List<Person>> =
        combine(
            db.propertyDao().textValuesInUse(BuiltIns.ASSIGNEE_DEF_ID, workspaceId),
            cacheVersion,
        ) { inUse, _ ->
            val me = loginFor(workspaceId)
            val used = inUse.toSet()
            val roster = cached(workspaceId)
            // An empty cache is "never asked", not "nobody". See Person.onRepo.
            val known = roster.takeIf { it.isNotEmpty() }?.map { it.lowercase() }?.toSet()
            val all = LinkedHashSet<String>()
            me?.let { all += it }
            all += used
            all += roster
            all.map { login ->
                Person(
                    login = login,
                    isYou = login.equals(me, ignoreCase = true),
                    inUse = login in used,
                    onRepo = known?.contains(login.lowercase()),
                )
            }.sortedWith(
                compareByDescending<Person> { it.isYou }
                    // People who can actually see the repository first. Someone already on a task
                    // here but no longer on the repo still belongs in the list — that is a fact
                    // about the data, and hiding it would hide the problem with it — but it does
                    // not belong above the people you can really hand work to.
                    .thenByDescending { it.onRepo != false }
                    .thenBy { it.login.lowercase() }
            )
        }

    /**
     * The logins assignable in one workspace, right now, as a plain list.
     *
     * A snapshot for the capture path, which is answering a single question once rather than
     * watching for changes: is the `@name` somebody typed a person this repository has. Built from
     * the same three sources as [known], so a name capture accepts is a name the picker would have
     * offered.
     */
    suspend fun loginsFor(workspaceId: String): List<String> = withContext(Dispatchers.IO) {
        val roster = cached(workspaceId)
        // Closed where the roster is known, open only where it is not — the same rule the assignee
        // sheet enforces, and it has to be the *same* rule. A capture line accepting `@octocat`
        // while the sheet refuses them would make the grammar a way around the check rather than
        // another way to pass it.
        if (roster.isNotEmpty()) return@withContext roster
        val all = LinkedHashSet<String>()
        loginFor(workspaceId)?.let { all += it }
        all += db.propertyDao().textValuesInUseOnce(BuiltIns.ASSIGNEE_DEF_ID, workspaceId)
        all.toList()
    }

    /**
     * Every workspace's cached roster, by workspace id, lowercased for comparison.
     *
     * A flow over all of them rather than a lookup per workspace, because the caller that needs it
     * most is a smart list: it gathers tasks from every repo at once, and each row has to be judged
     * against the roster of the repo *it* came from. Asking one workspace at a time would mean the
     * view deciding which workspace a chip belongs to, which is a fact the row already carries.
     *
     * A workspace absent from the map is one nobody has fetched — see [Person.onRepo] for why that
     * is not the same as a workspace with nobody in it.
     */
    fun rosters(): Flow<Map<String, Set<String>>> = cacheVersion.map {
        prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { key ->
                val logins = cached(key.removePrefix(KEY_PREFIX))
                    .map { it.lowercase() }
                    .toSet()
                if (logins.isEmpty()) null else key.removePrefix(KEY_PREFIX) to logins
            }
            .toMap()
    }

    /**
     * Asks GitHub who else is on this workspace's repository, and remembers the answer.
     *
     * Returns a line to show the user — **including when it worked**. That is deliberate and it is
     * the fix for the way this feature failed most convincingly: on a repository whose only
     * collaborator is you, a perfectly successful fetch adds nobody, so the spinner blinks and the
     * list is character-for-character identical. There is no difference on screen between "GitHub
     * said you are the only one" and "the request died", and the natural reading of no change is
     * that nothing worked.
     *
     * An outcome nobody can observe is not an outcome. So every branch says what happened, and the
     * successful ones say it too.
     */
    suspend fun refresh(workspaceId: String): String? = withContext(Dispatchers.IO) {
        val token = credentials.token(workspaceId)
            ?: credentials.token(Credentials.ACCOUNT)
            ?: return@withContext "Not signed in to GitHub."
        val slug = registry.entries().firstOrNull { it.id == workspaceId }?.slug
            ?: return@withContext "This workspace is not linked to a repository."
        val ref = RepoRef.parse(slug) ?: return@withContext "Could not read \"$slug\" as a repository."
        when (val answer = api.collaborators(ref, token)) {
            is Collaborators.Ok -> {
                store(workspaceId, answer.logins)
                cacheVersion.value++
                // Named by repository, because "only you" is a fact about a specific repo and the
                // obvious next question is which one it asked.
                if (answer.logins.size <= 1) "Only you can push to $slug — nobody else to assign yet."
                else "${answer.logins.size} people can push to $slug."
            }
            // Named plainly, with the reason, because the obvious reading of a failure here is
            // "the app is broken" and the true one is "GitHub will not tell this app that". The
            // list below still works, which is the sentence that stops it being alarming.
            is Collaborators.NotPermitted -> {
                android.util.Log.w(
                    "Yantra.people",
                    "GitHub refused the collaborator list for $slug with ${answer.code}",
                )
                "GitHub won't list $slug's collaborators for this app — reading a repository's " +
                    "people needs a heavier permission than syncing its files. Type a login below " +
                    "instead; anyone already on a task is here already."
            }
            Collaborators.Unauthorized -> "GitHub rejected the sign-in. Reconnect in Settings."
            is Collaborators.Failed -> "Couldn't reach GitHub (${answer.message})."
        }
    }

    /** Whether asking GitHub could even work here — a linked repository and a token for it. */
    fun canRefresh(workspaceId: String): Boolean =
        registry.entries().any { it.id == workspaceId && it.slug != null } &&
            (credentials.has(workspaceId) || credentials.has(Credentials.ACCOUNT))

    private fun loginFor(workspaceId: String): String? =
        credentials.login(workspaceId)?.takeIf { it.isNotBlank() }
            ?: credentials.login(Credentials.ACCOUNT)?.takeIf { it.isNotBlank() }

    // A newline-joined string rather than a StringSet: prefs sets have no order, and the order
    // GitHub returns is at least stable, which makes a cached list that has not changed look like
    // one that has not changed.
    private fun cached(workspaceId: String): List<String> =
        prefs.getString(key(workspaceId), null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun store(workspaceId: String, logins: List<String>) {
        prefs.edit().putString(key(workspaceId), logins.joinToString("\n")).apply()
    }

    private fun key(workspaceId: String) = "$KEY_PREFIX$workspaceId"

    private companion object {
        const val KEY_PREFIX = "collaborators:"
    }
}
