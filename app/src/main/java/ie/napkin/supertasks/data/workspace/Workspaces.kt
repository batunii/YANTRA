package ie.napkin.supertasks.data.workspace

import ie.napkin.supertasks.data.db.AppDatabase
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
    /** Told about every write, with the workspace it belongs to, so commits can be scheduled. */
    private val onChange: (String, ie.napkin.supertasks.data.sync.Change) -> Unit = { _, _ -> },
) {
    private val stores = LinkedHashMap<String, WorkspaceStore>()
    private val writers = LinkedHashMap<String, WorkspaceWriter>()

    /** Adds a workspace, scaffolding it if the directory is new. Returns true if it was scaffolded. */
    fun open(id: String, root: File, name: String): Boolean {
        val store = WorkspaceStore(root, id)
        val fresh = !store.exists
        if (fresh) store.scaffold(name, System.currentTimeMillis())
        stores[id] = store
        writers[id] = WorkspaceWriter(store, db, indexer, device) { onChange(id, it) }
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

    /** Rebuilds every workspace's index — a cold start, or after a sync. */
    suspend fun reindexAll(): List<String> = stores.values.flatMap { indexer.rebuild(it) }
}
