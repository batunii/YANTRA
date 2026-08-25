package ie.napkin.supertasks.data.workspace

import androidx.room.withTransaction
import ie.napkin.supertasks.data.db.AppDatabase

/**
 * Pours a workspace into Room.
 *
 * The index is derived and disposable, so this replaces rather than merges. Anything that survived
 * a rebuild without being in a file would be a fact the app knows and the repo does not — and one
 * device would start disagreeing with another for reasons nothing on disk could explain. Wiping
 * first is what makes "delete the database, nothing is lost" true rather than aspirational.
 *
 * It runs in a single transaction, so a reader never sees the moment where the tables are empty.
 */
class Indexer(private val db: AppDatabase) {

    /** Reads every file and rebuilds the whole index. Returns whatever could not be resolved. */
    suspend fun rebuild(
        store: WorkspaceStore,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val index = WorkspaceReconciler.read(store, now)
        apply(index)
        return index.problems
    }

    suspend fun apply(index: WorkspaceIndex) = db.withTransaction {
        val nodes = db.nodeDao()
        val props = db.propertyDao()
        val labels = db.labelDao()
        val smart = db.smartListDao()
        val ink = db.inkDao()

        // Order matters on the way out as much as on the way in: node_label and property_value
        // point at node, so they go first and come back last.
        labels.clearNodeLabels()
        labels.clearLabels()
        props.clearValues()
        props.clearDefs()
        smart.clearSmartLists()
        ink.clearStrokes()
        nodes.clearNodes()

        props.insertDefs(index.defs)
        // Parents before children, or the foreign key on node.parent_id rejects the insert.
        nodes.insertAll(inParentOrder(index))
        props.insertValues(index.values)
        labels.insertAll(index.labels)
        labels.attachAll(index.nodeLabels)
        smart.insertAll(index.smartLists)
        ink.insertAll(index.ink)
    }

    /**
     * Nodes sorted so every parent precedes its children.
     *
     * `node.parent_id` is a foreign key onto `node`, so a flat insert fails the moment a child
     * happens to sort before its parent — which the filename ordering makes likely rather than
     * unlikely. Anything whose parent is missing entirely goes last and is left for the caller's
     * problem list; the row is still inserted, because dropping it would be the silent data loss
     * the reconciler exists to avoid.
     */
    private fun inParentOrder(index: WorkspaceIndex): List<ie.napkin.supertasks.data.db.NodeEntity> {
        val byId = index.nodes.associateBy { it.id }
        val out = ArrayList<ie.napkin.supertasks.data.db.NodeEntity>(index.nodes.size)
        val placed = HashSet<String>()

        fun place(id: String, guard: Int) {
            if (id in placed || guard > 64) return
            val n = byId[id] ?: return
            n.parentId?.let { place(it, guard + 1) }
            if (placed.add(id)) out += n
        }

        index.nodes.forEach { place(it.id, 0) }
        // A node naming a parent that is not here at all: keep it, but strip the dangling pointer
        // so the insert can succeed. The reconciler has already reported it.
        return out.map { if (it.parentId != null && it.parentId !in byId) it.copy(parentId = null) else it }
    }
}
