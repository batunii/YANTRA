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

    /**
     * What each workspace's tables were last filled with.
     *
     * The index is a pure function of the working tree, so two readings of an unchanged file produce
     * equal rows — which is what lets a rebuild tell which tables genuinely changed and leave the
     * rest alone.
     *
     * That matters more than it sounds. A rebuild runs after *every* write and a keystroke changes
     * one title: without this, all eight tables were emptied and refilled, and since Room invalidates
     * per table, every flow in the app woke up. The drawing previews on a page re-decoded every
     * stroke on it because someone renamed a task.
     */
    private val last = HashMap<String, WorkspaceIndex>()

    /** Per workspace, the mapping of each page — reused while the page's file is untouched. */
    private val mapped = HashMap<String, MutableMap<String, Pair<
        ie.napkin.supertasks.data.format.PageDoc, MappedPage>>>()

    /** Reads every file and rebuilds the whole index. Returns whatever could not be resolved. */
    suspend fun rebuild(
        store: WorkspaceStore,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val index = WorkspaceReconciler.read(
            store, now,
            mapCache = mapped.getOrPut(store.id) { HashMap() },
        )
        apply(index, store.id)
        return index.problems
    }

    suspend fun apply(index: WorkspaceIndex, workspaceId: String) = db.withTransaction {
        val nodes = db.nodeDao()
        val props = db.propertyDao()
        val labels = db.labelDao()
        val smart = db.smartListDao()
        val ink = db.inkDao()

        // Scoped: one database holds every workspace, so an unscoped wipe here would erase the
        // other repos rather than refresh this one.
        //
        // Order matters on the way out as much as on the way in: node_label, property_value,
        // ink_stroke and pomodoro_session all point at node, so they go first and come back last.
        // Pomodoro is included for exactly that reason — a scoped node wipe fails on its foreign
        // key otherwise, which is why sessions had to become part of the workspace rather than
        // something the index alone remembered.
        // Trust the memory only if the database still agrees with it. Room's `clearAllTables` — and
        // anything else that empties the tables from underneath this class — would otherwise leave a
        // rebuild convinced that rows it can no longer see are already there, and it would skip
        // writing them. A single indexed count is a cheap price for an optimisation that cannot
        // silently produce an empty index.
        val was = last[workspaceId]?.takeIf { it.nodes.size == nodes.countNodes(workspaceId) }
        val nodesChanged = was?.nodes != index.nodes
        val valuesChanged = was?.values != index.values
        val labelsChanged = was?.labels != index.labels
        val linksChanged = was?.nodeLabels != index.nodeLabels
        val defsChanged = was?.defs != index.defs
        val smartChanged = was?.smartLists != index.smartLists
        val inkChanged = !sameInk(was?.ink, index.ink)
        val pomodoroChanged = was?.pomodoro != index.pomodoro

        // Everything above points at node, so its rows can only be replaced once the dependents are
        // out of the way. When a dependent table is *not* being rewritten its rows stay put while
        // node is emptied beneath them, which SQLite rejects the moment it happens — unless the
        // constraint is deferred to the end of the transaction, by which point the same node ids are
        // back and it holds again.
        val leavingDependents = nodesChanged &&
            !(valuesChanged && linksChanged && inkChanged && pomodoroChanged)
        if (leavingDependents) {
            db.openHelper.writableDatabase.execSQL("PRAGMA defer_foreign_keys = TRUE")
        }

        if (linksChanged) labels.clearNodeLabels(workspaceId)
        if (labelsChanged) labels.clearLabels(workspaceId)
        if (valuesChanged) props.clearValues(workspaceId)
        if (defsChanged) props.clearDefs()
        if (smartChanged) smart.clearSmartLists(workspaceId)
        if (inkChanged) ink.clearStrokes(workspaceId)
        if (pomodoroChanged) db.pomodoroDao().clearSessions(workspaceId)
        if (nodesChanged) nodes.clearNodes(workspaceId)

        if (defsChanged) props.insertDefs(index.defs)
        // Parents before children, or the foreign key on node.parent_id rejects the insert.
        if (nodesChanged) nodes.insertAll(inParentOrder(index))
        if (valuesChanged) props.insertValues(index.values)
        if (labelsChanged) labels.insertAll(index.labels)
        if (linksChanged) labels.attachAll(index.nodeLabels)
        if (smartChanged) smart.insertAll(index.smartLists)
        if (inkChanged) ink.insertAll(index.ink)
        if (pomodoroChanged) db.pomodoroDao().insertAll(index.pomodoro)

        last[workspaceId] = index
    }

    /**
     * Forgets what a workspace's tables hold, so the next rebuild writes all of them.
     *
     * For anything that empties the database behind this class, after which "unchanged since last
     * time" would be a claim about rows that are no longer there.
     */
    fun forget(workspaceId: String? = null) {
        if (workspaceId == null) {
            last.clear()
            mapped.clear()
        } else {
            last.remove(workspaceId)
            mapped.remove(workspaceId)
        }
    }

    /**
     * Whether the ink table already holds exactly these strokes.
     *
     * Deliberately not `==`. [ie.napkin.supertasks.data.db.InkStrokeEntity] carries `createdAt` and
     * `updatedAt`, both stamped with the clock at rebuild time, so two identical readings of the same
     * unchanged file never compare equal. What identifies a stroke is where it sits and what it
     * contains — and `data` is compared by reference on purpose, because [WorkspaceStore] returns the
     * same blob instance for as long as the sidecar is untouched. Same instance means the file did
     * not change; it can never mean two different drawings coincidentally matched.
     */
    private fun sameInk(
        was: List<ie.napkin.supertasks.data.db.InkStrokeEntity>?,
        now: List<ie.napkin.supertasks.data.db.InkStrokeEntity>,
    ): Boolean {
        if (was == null || was.size != now.size) return false
        return was.indices.all { i ->
            val a = was[i]
            val b = now[i]
            a.id == b.id && a.nodeId == b.nodeId && a.rank == b.rank && a.data === b.data
        }
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
