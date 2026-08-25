package ie.napkin.supertasks

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.repo.InkRepository
import ie.napkin.supertasks.data.repo.LabelRepository
import ie.napkin.supertasks.data.repo.NodeRepository
import ie.napkin.supertasks.data.repo.PomodoroRepository
import ie.napkin.supertasks.data.repo.PropertyRepository
import ie.napkin.supertasks.data.repo.SmartListRepository
import ie.napkin.supertasks.data.seed.WorkspaceSeeder
import ie.napkin.supertasks.data.workspace.Indexer
import ie.napkin.supertasks.data.workspace.WorkspaceRegistry
import ie.napkin.supertasks.data.workspace.WorkspaceEntry
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import ie.napkin.supertasks.data.workspace.Workspaces
import ie.napkin.supertasks.data.sync.CommitScheduler
import ie.napkin.supertasks.data.sync.Credentials
import ie.napkin.supertasks.data.sync.WorkspaceLinker
import ie.napkin.supertasks.data.sync.GitHubApi
import ie.napkin.supertasks.data.sync.GitHubDeviceAuth
import ie.napkin.supertasks.data.sync.LinkResult
import ie.napkin.supertasks.data.sync.RepoRef
import ie.napkin.supertasks.data.sync.SyncWorker
import ie.napkin.supertasks.data.sync.GitRepo
import ie.napkin.supertasks.data.sync.SyncEngine
import ie.napkin.supertasks.domain.PomodoroTimer
import ie.napkin.supertasks.reminders.ReminderManager
import ie.napkin.supertasks.reminders.ReminderScheduler
import ie.napkin.supertasks.reminders.Reminders
import ie.napkin.supertasks.ui.theme.LauncherIcon
import ie.napkin.supertasks.ui.theme.loadThemeController
import ie.napkin.supertasks.widget.PomodoroFinalizeWorker
import ie.napkin.supertasks.widget.PomodoroWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Reminders.CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        // Component enabled-states are package state, not app data: a reinstall resets them to the
        // manifest defaults while the stored accent survives in prefs, which would leave a coral
        // icon on an indigo app. Cheap to check and a no-op whenever they already agree.
        container.appScope.launch {
            LauncherIcon.apply(this@App, loadThemeController(this@App).accent)
        }
        // The only path that pulls other people's work down without being asked. Everything else
        // syncs because you did something.
        SyncWorker.schedule(this)
    }
}

/** The branch tasks live on, kept off whatever else a remote repo contains. */
const val BRANCH = "yantra-tasks"

/** The outcome of adding a workspace, in terms a screen can say out loud. */
sealed interface AddResult {
    data class Ok(val id: String, val name: String, val adopted: Boolean) : AddResult
    data class Refused(val reason: String) : AddResult
}

/** Plain-manual DI: one graph for the whole app. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = AppDatabase.build(app)

    /**
     * The workspaces the app has open, and the writer for each.
     *
     * Files are the source of truth and Room is an index rebuilt from them, so everything below
     * reads from [db] and writes through here. The local workspace has the empty id — it is the one
     * that existed before any repo was attached, and the one rows migrated from v9 belong to.
     */
    private val device: String = android.os.Build.MODEL?.lowercase().orEmpty()

    /** Keystore-backed tokens, one per workspace. */
    val credentials = Credentials(app)

    /** Validates a repo, creates or adopts its task branch, and stores the token. */
    val linker = WorkspaceLinker()

    /** Who you are, and whether you can push there. */
    val github = GitHubApi()

    /** Signing in without a client secret — the only flow that fits in an app with no server. */
    val deviceAuth = GitHubDeviceAuth()

    /**
     * Which workspaces exist, across launches.
     *
     * Not derived from scanning for directories that look like workspaces: a link that got halfway
     * would then come back on every launch as a workspace that cannot sync, with nowhere to record
     * that it was never real.
     */
    val registry = WorkspaceRegistry(File(app.filesDir, "workspaces"))

    val workspaces = Workspaces(db, Indexer(db), device) { id, change ->
        commits[id]?.record(change)
    }

    /** One scheduler per workspace: each repo commits on its own rhythm. */
    private val commits = LinkedHashMap<String, CommitScheduler>()

    /** Commit and sync everything now — the manual pull-to-refresh, and app shutdown. */
    fun syncNow(reason: String = "asked to sync") = commits.values.forEach { it.requestFlush(reason) }

    /** The most recent sync of the workspace the user is looking at. */
    fun syncState(): kotlinx.coroutines.flow.StateFlow<ie.napkin.supertasks.data.sync.SyncResult?>? =
        commits.values.firstOrNull()?.lastResult

    val nodes = NodeRepository(db, workspaces)
    val properties = PropertyRepository(db, workspaces)
    val labels = LabelRepository(db, workspaces)
    val smartLists = SmartListRepository(db, workspaces)
    val pomodoro = PomodoroRepository(db, workspaces)
    val ink = InkRepository(db, workspaces)
    val timer = PomodoroTimer(pomodoro, appScope)
    val reminderScheduler = ReminderScheduler(app)
    val reminders = ReminderManager(db, reminderScheduler, appScope)

    /**
     * Opens the local workspace and builds the index from it. The splash joins this.
     *
     * Seeding is gated on having just scaffolded the directory, never on the index being empty —
     * the index is empty on every device that clones an existing workspace, and gating the other way
     * would give each machine that joins its own second Inbox and its own second Today.
     */
    val seeding: Job = appScope.launch {
        val fresh = workspaces.open(id = "", root = registry.dirFor(""), name = "Personal")
        if (fresh) WorkspaceSeeder.seed(workspaces.primaryStore())

        // The local workspace is opened first and by hand; the rest come from the registry. Order
        // matters only in that the local one must be present before anything asks for the primary.
        registry.entries().filter { it.id.isNotEmpty() }.forEach { entry ->
            workspaces.open(entry.id, registry.dirFor(entry.id), entry.name)
        }
        workspaces.reindexAll()
        workspaces.all.forEach { attach(it) }
    }

    /**
     * Gives a workspace a commit scheduler, and a git repo if it has none.
     *
     * The local workspace is a git repo from the start, with no remote and nothing to push to. That
     * is not a placeholder: it is what gives the tasks a history at all, and it means attaching a
     * remote later is one command rather than a migration.
     */
    private fun attach(store: WorkspaceStore) {
        val repo = GitRepo(store.root, BRANCH)
        if (!repo.exists) repo.init().use { git ->
            repo.commitAll(git, "scaffold", "Yantra", "yantra@napkin.ie")
        }
        commits[store.id] = CommitScheduler(
            appScope,
            SyncEngine(
                store, Indexer(db), repo,
                // The login is the conflict tiebreak, so a linked workspace arbitrates by who you
                // are and an unlinked one falls back to the device name. Both are stable and both
                // compare the same way on either side, which is all the rule needs.
                device = credentials.login(store.id) ?: device,
                credentials = credentials.providerFor(store.id),
            ),
        )
    }

    /**
     * Adds a workspace pointing at [urlOrSlug], and makes it usable without a restart.
     *
     * The order is the whole of it. The token is stored before [attach] runs, because the scheduler
     * reads the credentials once when it is built — storing it afterwards would give the workspace a
     * sync engine that cannot authenticate until the next launch, which looks exactly like a bad
     * token. And the registry is written only after the link succeeds, so a refused attempt leaves
     * nothing behind to resurrect.
     */
    suspend fun addWorkspace(urlOrSlug: String, token: String, name: String? = null): AddResult =
        withContext(Dispatchers.IO) {
            seeding.join()
            val id = UUID.randomUUID().toString()
            val dir = registry.dirFor(id)
            val label = name?.takeIf { it.isNotBlank() }
                ?: RepoRef.parse(urlOrSlug)?.name
                ?: "Workspace"

            when (val result = linker.link(dir, id, label, urlOrSlug, token) { store ->
                WorkspaceSeeder.seedLinked(store, label)
            }) {
                is LinkResult.Refused -> {
                    // Nothing here is worth keeping, and leaving it would make a second attempt at
                    // the same repo look like an already-linked workspace.
                    dir.deleteRecursively()
                    AddResult.Refused(result.reason)
                }
                is LinkResult.Ok -> {
                    credentials.store(id, token, result.login)
                    // A workspace we joined already has a name, chosen by whoever started it. Taking
                    // ours over theirs would rename the same shared project on every device.
                    val store = WorkspaceStore(dir, id)
                    val named = store.readManifest()?.name?.takeIf { it.isNotBlank() } ?: label
                    registry.add(WorkspaceEntry(id, named, result.ref.slug))
                    workspaces.open(id, dir, named)
                    attach(store)
                    workspaces.reindexAll()
                    AddResult.Ok(id, named, result.adopted)
                }
            }
        }

    /**
     * Gives the tasks already on this device somewhere to live — the end of signing in.
     *
     * Distinct from [addWorkspace] because the tasks are already here: there is nothing to clone and
     * nothing to seed, and the linker refuses rather than merging if the remote turns out to have
     * tasks of its own. The scheduler is rebuilt afterwards so it picks up the credentials, which is
     * also what makes the first push happen without a restart.
     */
    suspend fun attachRemote(workspaceId: String, urlOrSlug: String, token: String): AddResult =
        withContext(Dispatchers.IO) {
            seeding.join()
            val store = workspaces.store(workspaceId)
                ?: return@withContext AddResult.Refused("That workspace is not open")

            when (val result = linker.attach(store, urlOrSlug, token)) {
                is LinkResult.Refused -> AddResult.Refused(result.reason)
                is LinkResult.Ok -> {
                    credentials.store(workspaceId, token, result.login)
                    val name = store.readManifest()?.name ?: "Workspace"
                    // The local workspace gets a registry entry too once it has a remote, even
                    // though it is not opened from the registry — it is the only record of where it
                    // points, and without it the UI could not tell a backed-up workspace from one
                    // that has never left the device.
                    registry.add(WorkspaceEntry(workspaceId, name, result.ref.slug))
                    attach(store)
                    AddResult.Ok(workspaceId, name, adopted = false)
                }
            }
        }

    /**
     * Where a workspace points, for the UI to show. Null for one with no remote.
     *
     * Read from the registry rather than from git, because the local workspace is not in the registry
     * at all and asking git would mean opening every repository to draw a settings row.
     */
    fun slugOf(workspaceId: String): String? =
        registry.entries().firstOrNull { it.id == workspaceId }?.slug

    /** Forgets a workspace: its credentials, its registration, and its files. */
    suspend fun forgetWorkspace(workspaceId: String) = withContext(Dispatchers.IO) {
        if (workspaceId.isEmpty()) return@withContext   // the local workspace is not optional
        commits.remove(workspaceId)
        credentials.clear(workspaceId)
        registry.remove(workspaceId)
        workspaces.close(workspaceId)
        registry.dirFor(workspaceId).deleteRecursively()
        workspaces.reindexAll()
    }

    init {
        // Any process wake (widget tap, worker, activity) revives a live focus session.
        appScope.launch { timer.restoreIfNeeded() }
        // Pomodoro widget re-renders on state *transitions* only — never per tick (the widget's
        // Chronometer handles the live countdown). Also (de)schedules the dead-process finalizer.
        appScope.launch {
            timer.state
                .map { s -> s?.let { Triple(it.sessionId, it.isRunning, it.isFinished) } }
                .distinctUntilChanged()
                .collect {
                    val s = timer.state.value
                    if (s != null && s.isRunning && !s.isFinished) {
                        PomodoroFinalizeWorker.schedule(app, s.remainingSecs)
                    } else if (s == null || s.isFinished) {
                        PomodoroFinalizeWorker.cancel(app)
                    }
                    PomodoroWidget().updateAll(app)
                }
        }
    }
}
