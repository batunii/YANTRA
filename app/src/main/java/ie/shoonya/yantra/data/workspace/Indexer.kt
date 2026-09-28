package ie.shoonya.yantra.data.workspace

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import ie.shoonya.yantra.data.db.AppDatabase

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
    private val last = java.util.concurrent.ConcurrentHashMap<String, WorkspaceIndex>()

    /**
     * One rebuild at a time, across every caller.
     *
     * Writers rebuild under their own lock and sync rebuilds under the engine's, so nothing else
     * kept two of them apart — and with writes now on IO threads they genuinely overlap. [mapped]
     * and the reconciler's page cache are plain maps mutated outside the Room transaction, and
     * [last] is read and written around it; two passes interleaving could leave either describing
     * rows the other wrote.
     */
    private val lock = kotlinx.coroutines.sync.Mutex()

    /**
     * Removes every row a workspace owns.
     *
     * Forgetting a workspace used to delete its files, its credentials and its registry entry, and
     * leave the index alone — so its lists went on appearing on Home and in Today, belonging to a
     * repository that was no longer on the device. They could not be deleted either: a write is
     * routed by the node's `workspace_id`, and with no writer under that id it fell through to
     * Personal, which has no such page, so Delete succeeded at doing nothing.
     *
     * The same seven tables the rebuild clears, in the same order — children before parents, or the
     * foreign key on `node.parent_id` refuses. [reindexAll] cannot do this job: it rebuilds the
     * workspaces that are *open*, and the whole point here is one that is not.
     */
    suspend fun purge(workspaceId: String) = lock.withLock {
        last.remove(workspaceId)
        db.labelDao().clearNodeLabels(workspaceId)
        db.labelDao().clearLabels(workspaceId)
        db.propertyDao().clearValues(workspaceId)
        db.smartListDao().clearSmartLists(workspaceId)
        db.inkDao().clearStrokes(workspaceId)
        db.focusDao().clearSessions(workspaceId)
        db.nodeDao().clearNodes(workspaceId)
    }

    /** Per workspace, the mapping of each page — reused while the page's file is untouched. */
    private val mapped = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Pair<
        ie.shoonya.yantra.data.format.PageDoc, MappedPage>>>()

    /** Reads every file and rebuilds the whole index. Returns whatever could not be resolved. */
    suspend fun rebuild(
        store: WorkspaceStore,
        now: Long = System.currentTimeMillis(),
    ): List<String> = lock.withLock {
        val index = WorkspaceReconciler.read(
            store, now,
            mapCache = mapped.getOrPut(store.id) { HashMap() },
        )
        write(index, store.id)
        index.problems
    }

    suspend fun apply(index: WorkspaceIndex, workspaceId: String) = lock.withLock { write(index, workspaceId) }

    private suspend fun write(index: WorkspaceIndex, workspaceId: String) = db.withTransaction {
        val nodes = db.nodeDao()
        val props = db.propertyDao()
        val labels = db.labelDao()
        val smart = db.smartListDao()
        val ink = db.inkDao()
        val events = db.eventDao()

        // Scoped: one database holds every workspace, so an unscoped wipe here would erase the
        // other repos rather than refresh this one.
        //
        // Order matters on the way out as much as on the way in: node_label, property_value,
        // ink_stroke and focus_session all point at node, so they go first and come back last.
        // Focus is included for exactly that reason — a scoped node wipe fails on its foreign
        // key otherwise, which is why sessions had to become part of the workspace rather than
        // something the index alone remembered.
        // Trust the memory only if the database still agrees with it. Room's `clearAllTables` — and
        // anything else that empties the tables from underneath this class — would otherwise leave a
        // rebuild convinced that rows it can no longer see are already there, and it would skip
        // writing them. A single indexed count is a cheap price for an optimisation that cannot
        // silently produce an empty index.
        val was = last[workspaceId]?.takeIf { it.nodes.size == nodes.countNodes(workspaceId) }
        // With a trusted memory, write only the rows that changed. The wholesale path below is for
        // the first rebuild of a workspace, when there is nothing to compare against.
        if (was != null) {
            applyChanges(was, index, workspaceId)
            last[workspaceId] = index
            return@withTransaction
        }
        val nodesChanged = was?.nodes != index.nodes
        val valuesChanged = was?.values != index.values
        val labelsChanged = was?.labels != index.labels
        // `node_label` and `event` are the two tables that hang off `node` with ON DELETE CASCADE,
        // so clearing nodes takes their rows with it whether or not they changed — and the skip
        // below then never writes them back. `defer_foreign_keys` postpones the *check*; it does
        // not cancel the *action*, which is why the other dependents survive this and these two do
        // not.
        //
        // The shape of the failure, since it took a log line on a phone to see: you tap somebody's
        // meeting, the page opens with its header, you type one word, and the header goes. Typing
        // changes nodes and nothing else, so the event rows were cascaded away and then skipped —
        // and because `last` had already been told they were written, every rebuild afterwards
        // agreed they were there. Gone until the process restarted.
        //
        // **`node_label` hangs off two parents, not one.** It cascades from `label` as well, and
        // that edge was missed when the above was fixed: the comment said "hang off `node`", the
        // fix OR'd in `nodesChanged`, and the second foreign key went unmentioned and unhandled.
        // So recolouring a single tag — which changes `label` and nothing else — cleared every
        // attachment in the workspace and then declined to write any of them back. Every chip on
        // every task disappeared at once, from one tap on a colour, and stayed gone because `last`
        // recorded them as present. The lesson is the edge, not the table: anything with a
        // cascading key to `label` needs `labelsChanged` here exactly as `node`'s dependents need
        // `nodesChanged`.
        val linksChanged = nodesChanged || labelsChanged || was?.nodeLabels != index.nodeLabels
        val defsChanged = was?.defs != index.defs
        val smartChanged = was?.smartLists != index.smartLists
        val inkChanged = !sameInk(was?.ink, index.ink)
        val eventsChanged = nodesChanged || was?.events != index.events
        val focusChanged = was?.focus != index.focus

        // Everything above points at node, so its rows can only be replaced once the dependents are
        // out of the way. When a dependent table is *not* being rewritten its rows stay put while
        // node is emptied beneath them, which SQLite rejects the moment it happens — unless the
        // constraint is deferred to the end of the transaction, by which point the same node ids are
        // back and it holds again.
        //
        // **Every** table with a foreign key onto `node` belongs in this list, not only the ones
        // that cascade. `smart_list_def` was missing from it, and it is the one dependent an
        // ordinary day leaves alone: you edit tasks, so nodes, values, ink and focus all move,
        // while the smart lists themselves sit still. With every other dependent rewritten this
        // expression came out `false`, no deferral was asked for, and `clearNodes()` then deleted
        // rows that `smart_list_def` still pointed at — FOREIGN KEY constraint failed (787), raised
        // inside the transaction, which loses the whole rebuild and with it the sync that asked for
        // one. The rule is the edge, not the table: a key onto `node` with no `ON DELETE` action is
        // precisely the kind that refuses rather than cascades, so it must either be rewritten in
        // the same pass or have its check deferred. Guarded by
        // [ie.shoonya.yantra.ReindexSurvivesUnchangedSmartListsTest].
        val leavingDependents = nodesChanged &&
            !(valuesChanged && linksChanged && inkChanged && focusChanged && eventsChanged &&
                smartChanged)
        if (leavingDependents) {
            db.openHelper.writableDatabase.execSQL("PRAGMA defer_foreign_keys = TRUE")
        }

        if (linksChanged) labels.clearNodeLabels(workspaceId)
        if (labelsChanged) labels.clearLabels(workspaceId)
        if (valuesChanged) props.clearValues(workspaceId)
        if (smartChanged) smart.clearSmartLists(workspaceId)
        if (inkChanged) ink.clearStrokes(workspaceId)
        if (eventsChanged) events.clearEvents(workspaceId)
        if (focusChanged) db.focusDao().clearSessions(workspaceId)
        if (nodesChanged) nodes.clearNodes(workspaceId)

        // Upserted, never wiped. The property registry is the one table with no workspace column —
        // a Priority means the same thing in every repo — so the wipe that used to precede this was
        // unscoped while every other clear here is scoped, and rebuilding one workspace's index
        // emptied the registry for all of them. Harmless only because every workspace happens to
        // scaffold the same built-ins; a workspace with a property of its own lost it until the next
        // time it was indexed. REPLACE on conflict already does the whole job.
        if (defsChanged) props.insertDefs(index.defs)
        // Parents before children, or the foreign key on node.parent_id rejects the insert.
        if (nodesChanged) nodes.insertAll(inParentOrder(index))
        if (valuesChanged) props.insertValues(index.values)
        if (labelsChanged) labels.insertAll(index.labels)
        if (linksChanged) labels.attachAll(index.nodeLabels)
        if (smartChanged) smart.insertAll(index.smartLists)
        if (inkChanged) ink.insertAll(index.ink)
        if (eventsChanged) events.insertAll(index.events)
        if (focusChanged) db.focusDao().insertAll(index.focus)

        last[workspaceId] = index
    }

    /**
     * Brings a workspace's rows from [was] to [index] by touching only the rows that differ.
     *
     * **Why not clear and refill.** The wholesale path deletes every node in the workspace and
     * inserts them again whenever any node changed — and a keystroke changes a title, so every
     * keystroke did it. `node_label` and `event` cascade from `node`, so they had to be rewritten
     * too; and every property value on the edited page carries the page's modified time, so a page
     * with any dated or prioritised task rewrote `property_value` as well. Four whole tables per
     * keystroke, each of which wakes every flow that watches it.
     *
     * Here a renamed task is one UPDATE (plus the page's other rows, which share its modified time),
     * and the tables whose rows did not change are not written at all, so their observers stay
     * asleep. The small tables — labels, definitions, smart lists, ink, focus — keep the wholesale
     * treatment: they change rarely, and label rows are what `node_label` cascades from a second way.
     *
     * Order is what the constraints ask for:
     *  - Foreign-key *checks* are deferred to the end of the transaction, so a removed node and the
     *    rows pointing at it can leave in any order, and a moved child can be written before or
     *    after its new parent.
     *  - Cascades are not deferred — they fire at the delete — so rows that leave go before the
     *    nodes they belong to, and nothing that stays is ever deleted.
     *  - Removed nodes go before changed ones are written, and a node whose `system_key` changes is
     *    first written without one: `(workspace_id, system_key)` is unique, and an Inbox that moved
     *    to a new id would otherwise collide with the row it is replacing — which an upsert takes as
     *    "exists, update it" and then fails.
     */
    private suspend fun applyChanges(was: WorkspaceIndex, index: WorkspaceIndex, workspaceId: String) {
        val nodes = db.nodeDao()
        val props = db.propertyDao()
        val labels = db.labelDao()
        val events = db.eventDao()
        db.openHelper.writableDatabase.execSQL("PRAGMA defer_foreign_keys = TRUE")

        val labelsChanged = was.labels != index.labels
        val defsChanged = was.defs != index.defs
        val smartChanged = was.smartLists != index.smartLists
        val inkChanged = !sameInk(was.ink, index.ink)
        val focusChanged = was.focus != index.focus

        // Equal lists first: most rebuilds leave most tables exactly as they were, and a list
        // comparison is far cheaper than keying every row to find that nothing moved.
        val nodesSame = was.nodes == index.nodes
        val wasNodes = if (nodesSame) emptyMap() else was.nodes.associateBy { it.id }
        val nowNodes = if (nodesSame) emptyList() else inParentOrder(index)
        val nowIds = nowNodes.mapTo(HashSet()) { it.id }
        val changedNodes = nowNodes.filter { wasNodes[it.id] != it }
        val removedNodes = if (nodesSame) emptyList() else was.nodes.filter { it.id !in nowIds }

        // A value's `updatedAt` is the page's modified time and nothing reads it; comparing it
        // would rewrite every value on a page whenever any line on that page changed.
        val values = if (was.values == index.values) RowDiff.none()
        else rowDiff(was.values, index.values, { it.nodeId to it.defId }) { a, b ->
            a == b.copy(updatedAt = a.updatedAt)
        }
        val links = if (was.nodeLabels == index.nodeLabels) RowDiff.none()
        else rowDiff(was.nodeLabels, index.nodeLabels, { it.nodeId to it.labelId })
        val eventRows = if (was.events == index.events) RowDiff.none()
        else rowDiff(was.events, index.events, { it.nodeId })

        // Leaving, dependents first.
        if (labelsChanged) labels.clearNodeLabels(workspaceId)
        else if (links.removed.isNotEmpty()) labels.detachAll(links.removed)
        if (labelsChanged) labels.clearLabels(workspaceId)
        if (values.removed.isNotEmpty()) props.deleteValues(values.removed)
        if (eventRows.removed.isNotEmpty()) events.deleteAll(eventRows.removed)
        if (smartChanged) db.smartListDao().clearSmartLists(workspaceId)
        if (inkChanged) db.inkDao().clearStrokes(workspaceId)
        if (focusChanged) db.focusDao().clearSessions(workspaceId)
        if (removedNodes.isNotEmpty()) nodes.deleteAll(removedNodes)

        // Arriving or changed.
        if (defsChanged) props.insertDefs(index.defs)
        if (changedNodes.isNotEmpty()) {
            // Both sides of a key that moves: the row taking it and the row giving it up. Uniqueness
            // is checked per statement, not at commit, so neither may hold it while the other is
            // written.
            val rekeyed = changedNodes.filter { n ->
                val before = wasNodes[n.id]
                before != null && before.systemKey != n.systemKey || before == null && n.systemKey != null
            }
            if (rekeyed.isNotEmpty()) nodes.upsertAll(rekeyed.map { it.copy(systemKey = null) })
            nodes.upsertAll(changedNodes)
        }
        if (values.changed.isNotEmpty()) props.upsertValues(values.changed)
        if (labelsChanged) {
            labels.insertAll(index.labels)
            labels.attachAll(index.nodeLabels)
        } else if (links.changed.isNotEmpty()) labels.attachAll(links.changed)
        if (smartChanged) db.smartListDao().insertAll(index.smartLists)
        if (inkChanged) db.inkDao().insertAll(index.ink)
        if (eventRows.changed.isNotEmpty()) events.upsertAll(eventRows.changed)
        if (focusChanged) db.focusDao().insertAll(index.focus)
    }

    /** Rows of [now] that are new or differ from [was] by [key], and rows of [was] that are gone. */
    private class RowDiff<T>(val changed: List<T>, val removed: List<T>) {
        companion object {
            fun <T> none() = RowDiff<T>(emptyList(), emptyList())
        }
    }

    private fun <T, K> rowDiff(
        was: List<T>,
        now: List<T>,
        key: (T) -> K,
        same: (T, T) -> Boolean = { a, b -> a == b },
    ): RowDiff<T> {
        val before = was.associateBy(key)
        val nowKeys = HashSet<K>(now.size)
        val changed = now.filter { row ->
            nowKeys += key(row)
            val old = before[key(row)]
            old == null || !same(old, row)
        }
        return RowDiff(changed, was.filter { key(it) !in nowKeys })
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
     * Deliberately not `==`. [ie.shoonya.yantra.data.db.InkStrokeEntity] carries `createdAt` and
     * `updatedAt`, both stamped with the clock at rebuild time, so two identical readings of the same
     * unchanged file never compare equal. What identifies a stroke is where it sits and what it
     * contains — and `data` is compared by reference on purpose, because [WorkspaceStore] returns the
     * same blob instance for as long as the sidecar is untouched. Same instance means the file did
     * not change; it can never mean two different drawings coincidentally matched.
     */
    private fun sameInk(
        was: List<ie.shoonya.yantra.data.db.InkStrokeEntity>?,
        now: List<ie.shoonya.yantra.data.db.InkStrokeEntity>,
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
    private fun inParentOrder(index: WorkspaceIndex): List<ie.shoonya.yantra.data.db.NodeEntity> {
        val byId = index.nodes.associateBy { it.id }
        val out = ArrayList<ie.shoonya.yantra.data.db.NodeEntity>(index.nodes.size)
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
