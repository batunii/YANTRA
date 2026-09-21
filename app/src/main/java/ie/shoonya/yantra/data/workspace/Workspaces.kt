package ie.shoonya.yantra.data.workspace

import ie.shoonya.yantra.data.db.AppDatabase
import java.io.File

/**
 * Every workspace the app has open, and the writer for each.
 *
 * The repositories are global but a write is not — it belongs to whichever repo the node came from.
 * So mutations resolve their writer here, from the node's `workspace_id`. Today there is one entry
 * and the lookup is trivial; the shape is what multiple repos need, and putting it in now costs
 * nothing while retrofitting it later would mean touching every mutation twice.
 */
class Workspaces(
    private val db: AppDatabase,
    private val indexer: Indexer,
    private val device: String?,
    /** Where deferred index rebuilds run. Null keeps every write's rebuild inline. */
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
    /** Told about every write, with the workspace it belongs to, so commits can be scheduled. */
    private val onChange: (String, ie.shoonya.yantra.data.sync.Change) -> Unit = { _, _ -> },
    /** Told when a write is refused because a workspace is newer than this build. */
    private val onRefused: (String, WorkspaceWriter.WorkspaceReadOnly) -> Unit = { _, _ -> },
) {
    private val stores = LinkedHashMap<String, WorkspaceStore>()
    private val writers = LinkedHashMap<String, WorkspaceWriter>()

    /** Adds a workspace, scaffolding it if the directory is new. Returns true if it was scaffolded. */
    fun open(id: String, root: File, name: String): Boolean {
        val store = WorkspaceStore(root, id)
        val fresh = !store.exists
        if (fresh) store.scaffold(name, System.currentTimeMillis())
        // An existing directory was scaffolded by whatever build made it, so it is missing every
        // built-in field added since. Filling those in on open is the only migration this format
        // needs: the file is the truth, and the truth simply has one more line in it now.
        else {
            store.ensureBuiltInProperties()
            store.upgradeFormat()
        }
        stores[id] = store
        writers[id] = WorkspaceWriter(
            store, db, indexer, device, scope,
            onChange = { onChange(id, it) },
            onRefused = { onRefused(id, it) },
        )
        return fresh
    }

    /** Forgets a workspace. The files stay on disk; the caller decides whether to delete them. */
    fun close(id: String) {
        stores.remove(id)
        writers.remove(id)
    }

    val all: Collection<WorkspaceStore> get() = stores.values

    fun store(id: String): WorkspaceStore? = stores[id]

    fun writer(id: String): WorkspaceWriter? = writers[id]

    /** The one workspace, while there is only one. The switcher replaces this. */
    fun primary(): WorkspaceWriter = writers.values.first()

    /**
     * The local workspace, asked for by name rather than by being first.
     *
     * Smart lists live here, and that has to survive [primary] coming to mean "whichever workspace
     * you are currently in". A smart list is a view over repos, not a thing inside one: it can name
     * any of them, so no repo it names can own it without becoming unreadable when cloned alone.
     * Personal is the only workspace that is always present — seeding creates it unconditionally and
     * `forgetWorkspace` refuses to remove it — which makes it the one place a cross-repo view can
     * sit and still be found. Following a switcher would scatter them across repos that cannot hold
     * them.
     *
     * Falls back to first only for the moment before seeding has opened anything.
     */
    fun personal(): WorkspaceWriter = writers[""] ?: writers.values.first()

    /** Whether this device has the given workspace open. See [Filter.workspacesNamed]. */
    fun isOpen(id: String): Boolean = stores.containsKey(id)

    /**
     * Workspaces written by a newer build than this one, and so open read-only.
     *
     * Reported alongside the other things a rebuild could not make sense of, because that is what
     * this is from the app's point of view: a workspace it can read and must not write. Nothing
     * shows it to anyone yet — see [WorkspaceStore.isAhead].
     */
    fun aheadIds(): List<String> = stores.values.filter { it.isAhead }.map { it.id }

    fun primaryStore(): WorkspaceStore = stores.values.first()

    /**
     * The writer that owns [nodeId], found through the index.
     *
     * Using the index to route a write is not a contradiction of files-are-truth: the index is a
     * map of what the files say, and "which repo is this node in" is exactly the sort of question a
     * map is for. Falls back to the primary for a node that does not exist yet.
     */
    suspend fun writerFor(nodeId: String?): WorkspaceWriter {
        val ws = nodeId?.let { db.nodeDao().byId(it)?.workspaceId }
        return ws?.let { writers[it] } ?: primary()
    }

    /**
     * Moves a node, and everything under it, to a page in another workspace — CALENDAR_PLAN.md §28.
     *
     * **Why this cannot be [WorkspaceWriter.reparent].** A writer owns one store. `reparent` looks
     * the destination page up in its own, so across workspaces it found nothing, appended the line
     * to a page it had just invented in the *source* repo, and left the node in neither place a
     * person could reach. Nothing exercised it, because until now no screen offered the move.
     *
     * Ordered so that a failure in the middle leaves **two copies rather than none**: the files are
     * copied in first, then the line changes hands, and only then are the originals removed. A
     * duplicate is a thing somebody can delete; a page that stopped existing halfway through a move
     * is not.
     */
    suspend fun moveAcross(nodeId: String, newParent: String) {
        val from = db.nodeDao().byId(nodeId)?.workspaceId ?: return
        val to = db.nodeDao().byId(newParent)?.workspaceId ?: return
        if (from == to) {
            writers[from]?.reparent(nodeId, newParent)
            return
        }
        val source = stores[from] ?: return
        val dest = stores[to] ?: return
        val sourceWriter = writers[from] ?: return
        val destWriter = writers[to] ?: return

        dest.adopt(source, nodeId)
        // Null only if the line is not where the index says it is, which is a workspace already
        // disagreeing with itself. The copy above is then a harmless orphan rather than a deletion.
        val line = sourceWriter.takeLine(nodeId) ?: return
        destWriter.putLine(line, newParent, nodeId)
        sourceWriter.dropFiles(nodeId)
    }

    /** Applies any index rebuild a deferred edit still owes. The app going to the background. */
    suspend fun flushIndexes() = writers.values.forEach { it.flushIndex() }

    /**
     * Rebuilds every workspace's index — a cold start, or after a sync.
     *
     * One workspace failing must never cost the others, and it must never cost the app. This runs
     * inside the job the splash joins, on a scope with a `SupervisorJob` and **no exception
     * handler** — a supervisor stops a sibling being cancelled, it does not stop the throw reaching
     * the thread's uncaught handler, so anything escaping here is process death at launch. The
     * screen that could remove the offending workspace is behind that splash, which makes the
     * failure unrecoverable from inside the app: reinstalling is the only way out, and that wipes
     * the local workspace with it.
     *
     * A rebuild is a single transaction, so a workspace that throws keeps whatever its tables last
     * held rather than half of a new index — empty, on a cold start. Degraded and reported beats
     * absent. [Indexer.forget] because the memo of "what these tables hold" cannot be trusted once
     * a rebuild has failed part-way; the next attempt writes everything again.
     */
    suspend fun reindexAll(): List<String> = stores.values.flatMap { store ->
        // Still indexed, and deliberately: read-only means readable. A workspace from a newer
        // build that came back empty would look broken rather than protected, and the whole point
        // is that its files are fine — it is this build's writing that is not to be trusted.
        val ahead = if (!store.isAhead) emptyList() else listOf(
            "workspace '${store.id}' is format ${store.formatVersion} and this build reads " +
                "${WorkspaceStore.FORMAT_VERSION}: it is open read-only and edits will be refused",
        )
        ahead + try {
            indexer.rebuild(store)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e     // never a failure — the caller going away must stay cancellation
        } catch (t: Throwable) {
            indexer.forget(store.id)
            listOf("workspace '${store.id}' could not be indexed and was left as it was: $t")
        }
    }
}
