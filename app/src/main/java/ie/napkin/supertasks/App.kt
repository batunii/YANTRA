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
import ie.napkin.supertasks.data.workspace.Workspaces
import ie.napkin.supertasks.data.sync.CommitScheduler
import ie.napkin.supertasks.data.sync.Credentials
import ie.napkin.supertasks.data.sync.WorkspaceLinker
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
        val fresh = workspaces.open(
            id = "",
            root = java.io.File(app.filesDir, "workspaces/local"),
            name = "Personal",
        )
        if (fresh) WorkspaceSeeder.seed(workspaces.primaryStore())
        workspaces.reindexAll()

        // The local workspace is a git repo from the start, with no remote and nothing to push to.
        // That is not a placeholder: it is what gives the tasks a history at all, and it means
        // attaching a remote later is one command rather than a migration.
        workspaces.all.forEach { store ->
            val repo = GitRepo(store.root, BRANCH)
            if (!repo.exists) repo.init().use { git ->
                repo.commitAll(git, "scaffold", "Yantra", "yantra@napkin.ie")
            }
            commits[store.id] = CommitScheduler(
                appScope,
                SyncEngine(
                    store, Indexer(db), repo,
                    // The login is the conflict tiebreak, so a linked workspace arbitrates by who
                    // you are and an unlinked one falls back to the device name. Both are stable
                    // and both compare the same way on either side, which is all the rule needs.
                    device = credentials.login(store.id) ?: device,
                    credentials = credentials.providerFor(store.id),
                ),
            )
        }
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
