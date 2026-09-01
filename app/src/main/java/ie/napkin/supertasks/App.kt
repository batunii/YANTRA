package ie.napkin.supertasks

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.repo.InkRepository
import ie.napkin.supertasks.data.repo.LabelRepository
import ie.napkin.supertasks.data.repo.NodeRepository
import ie.napkin.supertasks.data.repo.FocusRepository
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
import ie.napkin.supertasks.data.sync.TokenRenewal
import ie.napkin.supertasks.domain.FocusTimer
import ie.napkin.supertasks.reminders.ReminderManager
import ie.napkin.supertasks.reminders.ReminderScheduler
import ie.napkin.supertasks.reminders.Reminders
import ie.napkin.supertasks.ui.theme.LauncherIcon
import ie.napkin.supertasks.ui.theme.loadThemeController
import ie.napkin.supertasks.widget.FocusFinalizeWorker
import ie.napkin.supertasks.widget.FocusWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        // Once a day, and a no-op for every workspace that has not asked for it.
        ie.napkin.supertasks.data.sync.ArchiveWorker.schedule(this)
    }
}

/** The branch tasks live on, kept off whatever else a remote repo contains. */
const val BRANCH = "yantra-tasks"

/** A finished task that has left the working set, as the archive screen needs it. */
data class ArchivedTask(val id: String, val title: String, val doneAt: java.time.LocalDate?)

/** Archived tasks, grouped by the list they came from. */
data class ArchivedGroup(
    val workspaceId: String,
    val pageId: String,
    val listTitle: String,
    val tasks: List<ArchivedTask>,
)

/** The outcome of adding a workspace, in terms a screen can say out loud. */
sealed interface AddResult {
    data class Ok(val id: String, val name: String, val adopted: Boolean) : AddResult
    data class Refused(val reason: String) : AddResult
}

/** Plain-manual DI: one graph for the whole app. */
class AppContainer(val app: Application) {
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

    /** Keeps an expiring GitHub App token alive, so sync does not quietly die a few hours in. */
    val tokenRenewal = TokenRenewal(credentials, deviceAuth)

    /**
     * Which workspaces exist, across launches.
     *
     * Not derived from scanning for directories that look like workspaces: a link that got halfway
     * would then come back on every launch as a workspace that cannot sync, with nowhere to record
     * that it was never real.
     */
    val registry = WorkspaceRegistry(File(app.filesDir, "workspaces"))

    /**
     * One indexer for the whole app, deliberately.
     *
     * It remembers what each workspace's tables already hold so a rebuild can skip the ones that did
     * not change. A second instance would start with that memory empty and rewrite everything on its
     * first pass — which, since the sync engine used to have its own, is what every sync did.
     */
    private val indexer = Indexer(db)

    val workspaces = Workspaces(db, indexer, device, appScope) { id, change ->
        commits[id]?.record(change)
    }

    /** One scheduler per workspace: each repo commits on its own rhythm. */
    private val commits = LinkedHashMap<String, CommitScheduler>()

    /** Commit and sync everything now, without waiting — app shutdown, and the Settings button. */
    fun syncNow(reason: String = "asked to sync") = commits.values.forEach { it.requestFlush(reason) }

    /**
     * The same, waited on, one pass per workspace.
     *
     * Concurrently, because workspaces are independent and someone with three repos should not wait
     * for three round trips in a row. Each engine serialises itself, so the only thing being
     * overlapped here is network latency.
     *
     * Joins [seeding] first: pulling to sync during a cold start would otherwise find no schedulers
     * and report a successful sync of nothing.
     */
    suspend fun syncAwait(reason: String): List<ie.napkin.supertasks.data.sync.SyncResult> {
        seeding.join()
        // A deferred rebuild owes the index an edit; sync reads files so it does not care, but it
        // reindexes at the end and would otherwise race the timer to do the same work twice.
        workspaces.flushIndexes()
        return coroutineScope {
            commits.values.toList().map { async { it.flushNow(reason) } }.awaitAll()
        }
    }

    /**
     * Whether any workspace has somewhere to sync *to*.
     *
     * Without this, pulling to sync before signing in succeeds instantly and says nothing, which
     * reads as "synced with GitHub" when nothing of the sort happened.
     */
    suspend fun anyRemote(): Boolean = withContext(Dispatchers.IO) {
        registry.entries().any { it.slug != null }
    }

    /** The most recent sync of the workspace the user is looking at. */
    fun syncState(): kotlinx.coroutines.flow.StateFlow<ie.napkin.supertasks.data.sync.SyncResult?>? =
        commits.values.firstOrNull()?.lastResult

    val people = ie.napkin.supertasks.data.people.People(app, db, credentials, registry, github)
    val nodes = NodeRepository(db, workspaces)
    val properties = PropertyRepository(db, workspaces)
    val labels = LabelRepository(db, workspaces)
    val smartLists = SmartListRepository(db, workspaces)
    val focus = FocusRepository(db, workspaces)
    val ink = InkRepository(db, workspaces)
    val timer = FocusTimer(focus, appScope)
    val reminderScheduler = ReminderScheduler(app)
    val reminders = ReminderManager(db, reminderScheduler, appScope)

    /**
     * Opens the local workspace and builds the index from it. The splash joins this.
     *
     * Seeding is gated on having just scaffolded the directory, never on the index being empty —
     * the index is empty on every device that clones an existing workspace, and gating the other way
     * would give each machine that joins its own second Inbox and its own second Today.
     */
    /**
     * Says out loud what a rebuild could not make sense of.
     *
     * Every caller of `reindexAll` and `reindex` has always been handed this list and every one of
     * them has always dropped it, which made a reported problem and a swallowed one the same thing.
     * That is tolerable for a stale focus-log line and not at all tolerable for the reasons a
     * workspace failed to index — those used to be a crash, and a fix that turns a crash into
     * silence has moved the bug rather than found it.
     *
     * Logcat rather than the UI on purpose: what to *show* someone about a half-read workspace is a
     * design question worth answering properly, and nothing here should pretend to have answered it.
     * This only ensures the answer can be worked out from a bug report.
     */
    private fun report(problems: List<String>) {
        problems.forEach { Log.w("Yantra.index", it) }
    }

    val seeding: Job = appScope.launch {
        val fresh = workspaces.open(id = "", root = registry.dirFor(""), name = "Personal")
        if (fresh) WorkspaceSeeder.seed(workspaces.primaryStore())

        // The local workspace is opened first and by hand; the rest come from the registry. Order
        // matters only in that the local one must be present before anything asks for the primary.
        registry.entries().filter { it.id.isNotEmpty() }.forEach { entry ->
            workspaces.open(entry.id, registry.dirFor(entry.id), entry.name)
        }
        report(workspaces.reindexAll())
        workspaces.all.forEach { attach(it) }

        // Also on launch, not only on the daily worker. Android is free to decide a periodic job can
        // wait until tomorrow — Samsung especially — and archiving is what keeps the working set at
        // the size the whole indexing design assumes. A file scan on a workspace that has not opted
        // in costs one manifest read, so the common case pays nothing.
        //
        // `sweep`, not `archiveNow`: the latter waits for seeding, and this *is* seeding. A job that
        // joins itself waits forever, and everything downstream waits with it — the splash screen
        // holds on this job, so the app never got past its own logo.
        sweep()
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
                store, indexer, repo,
                // The login is the conflict tiebreak, so a linked workspace arbitrates by who you
                // are and an unlinked one falls back to the device name. Both are stable and both
                // compare the same way on either side, which is all the rule needs.
                device = credentials.login(store.id) ?: device,
                // Resolved per pass, after renewing: a token refreshed on the last pass — or a
                // fresh sign-in — has to be picked up without restarting the app.
                credentials = {
                    withContext(Dispatchers.IO) { tokenRenewal.renewIfNeeded() }
                    credentials.providerFor(store.id)
                },
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
                    // Who can be assigned, asked once, here — the moment the app has a repository
                    // and a token for it and is already on the network. Fetching it lazily instead
                    // meant a workspace arrived knowing nobody, and "nobody has been loaded yet"
                    // and "nobody else can push here" look identical from the assignee sheet. The
                    // answer is not allowed to fail the link: the repository is joined either way,
                    // and a roster is something to retry, not a reason to refuse.
                    people.refresh(id)
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
                    // Same as addWorkspace: the roster is fetched the moment there is a repository
                    // to fetch it from, so the assignee sheet is never empty for want of asking.
                    people.refresh(workspaceId)
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

    /**
     * How long finished tasks stay before they leave the working set, and setting it.
     *
     * Per workspace, in its manifest — see [ie.napkin.supertasks.data.workspace.Manifest]. Zero is
     * never, which is what a workspace starts as.
     */
    suspend fun archiveAfterDays(workspaceId: String): Int = withContext(Dispatchers.IO) {
        workspaces.store(workspaceId)?.readManifest()?.archiveAfterDays ?: 0
    }

    suspend fun setArchiveAfterDays(workspaceId: String, days: Int) = withContext(Dispatchers.IO) {
        val store = workspaces.store(workspaceId) ?: return@withContext
        store.readManifest()?.let { store.writeManifest(it.copy(archiveAfterDays = days)) }
    }

    /** Runs the sweep now, for every workspace that has asked for one. */
    suspend fun archiveNow(): Int {
        seeding.join()
        return sweep()
    }

    /**
     * The sweep itself, without waiting for seeding.
     *
     * Separate so that seeding can call it: [archiveNow] joins, and a caller inside the job it joins
     * deadlocks. Everything else should use [archiveNow].
     */
    private suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        workspaces.all.sumOf { store ->
            val days = store.readManifest()?.archiveAfterDays ?: 0
            if (days <= 0) 0
            else workspaces.writer(store.id)
                ?.archiveFinished(java.time.LocalDate.now().minusDays(days.toLong())) ?: 0
        }
    }

    suspend fun archivedCount(): Int = withContext(Dispatchers.IO) {
        seeding.join()
        workspaces.all.sumOf { workspaces.writer(it.id)?.archivedCount() ?: 0 }
    }

    /**
     * What is currently out of the working set, grouped by the list it came from.
     *
     * Read from the archive files rather than the index — archived tasks are deliberately not
     * indexed, which is the entire point of moving them, so this is the one screen that has to go to
     * the files directly.
     */
    suspend fun archivedItems(): List<ArchivedGroup> = withContext(Dispatchers.IO) {
        seeding.join()
        workspaces.all.flatMap { store ->
            store.archivedPageIds().mapNotNull { pageId ->
                val tasks = store.readArchivedLines(pageId).mapNotNull { line ->
                    (ie.napkin.supertasks.data.format.PageCodec.decodeBlock(line)
                        as? ie.napkin.supertasks.data.format.TaskRef)
                        ?.let { ArchivedTask(it.id, it.title, it.doneAt) }
                }
                if (tasks.isEmpty()) null
                else ArchivedGroup(
                    workspaceId = store.id,
                    pageId = pageId,
                    // The page is still indexed; only its finished children left.
                    listTitle = db.nodeDao().byId(pageId)?.title.orEmpty().ifBlank { "Untitled list" },
                    tasks = tasks.sortedByDescending { it.doneAt },
                )
            }
        }.sortedBy { it.listTitle }
    }

    /** Brings one task back to the list it came from. */
    suspend fun restoreArchived(workspaceId: String, pageId: String, taskId: String): Boolean =
        withContext(Dispatchers.IO) {
            (workspaces.writer(workspaceId)?.restoreArchived(pageId, setOf(taskId)) ?: 0) > 0
        }

    suspend fun restoreAllArchived(): Int = withContext(Dispatchers.IO) {
        seeding.join()
        workspaces.all.sumOf { workspaces.writer(it.id)?.restoreAllArchived() ?: 0 }
    }

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
        // Focus widget re-renders on state *transitions* only — never per tick (the widget's
        // Chronometer handles the live countdown). Also (de)schedules the dead-process finalizer.
        appScope.launch {
            timer.state
                .map { s -> s?.let { Triple(it.sessionId, it.isRunning, it.isFinished) } }
                .distinctUntilChanged()
                .collect {
                    val s = timer.state.value
                    if (s != null && s.isRunning && !s.isFinished) {
                        FocusFinalizeWorker.schedule(app, s.remainingSecs)
                    } else if (s == null || s.isFinished) {
                        FocusFinalizeWorker.cancel(app)
                    }
                    FocusWidget().updateAll(app)
                }
        }
    }
}
