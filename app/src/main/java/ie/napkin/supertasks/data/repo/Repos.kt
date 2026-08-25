package ie.napkin.supertasks.data.repo

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeLabelEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.data.db.SmartListDefEntity
import ie.napkin.supertasks.data.db.SystemKey
import ie.napkin.supertasks.data.filter.ApplyOnCreate
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterCompiler
import ie.napkin.supertasks.data.filter.completedVariant
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.filter.SortSpec
import ie.napkin.supertasks.data.filter.deriveApplyOnCreate
import ie.napkin.supertasks.data.rank.Rank
import ie.napkin.supertasks.data.format.Block
import ie.napkin.supertasks.data.format.Bullet
import ie.napkin.supertasks.data.format.Heading
import ie.napkin.supertasks.data.format.ImageRef
import ie.napkin.supertasks.data.format.InkRef
import ie.napkin.supertasks.data.format.Numbered
import ie.napkin.supertasks.data.format.Prose
import ie.napkin.supertasks.data.format.TaskRef
import ie.napkin.supertasks.data.format.TaskStatus
import ie.napkin.supertasks.data.workspace.Workspaces
import ie.napkin.supertasks.data.sync.Change
import ie.napkin.supertasks.data.workspace.LabelDef
import ie.napkin.supertasks.data.workspace.SmartListDef
import ie.napkin.supertasks.data.format.DueSpec
import ie.napkin.supertasks.data.format.DueValue
import ie.napkin.supertasks.data.time.localDateOf
import ie.napkin.supertasks.data.time.localMidnight
import ie.napkin.supertasks.data.label.LabelPalette
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

private fun now(): Long = System.currentTimeMillis()
private fun newId(): String = UUID.randomUUID().toString()

/** Where blocks that were sitting loose on a list get gathered, so nothing is lost. */
private const val STRAY_NOTES_TASK = "Notes"

/** Title given to a capture list that has to be created; also the pre-[SystemKey] fallback. */
private const val INBOX_TITLE = "Inbox"

/**
 * Reads come from the index; **writes go to the files.**
 *
 * That asymmetry is the whole architecture. Room is a fast, queryable picture of what the workspace
 * says, so observing it is exactly right; but nothing may enter it except by being written to a page
 * first, or the picture would start containing facts the repo does not — and the next reindex would
 * erase them with no error anywhere.
 */
class NodeRepository(private val db: AppDatabase, private val ws: Workspaces) {
    private val dao = db.nodeDao()

    fun topLevel() = dao.topLevel()
    fun allLists() = dao.allLists()
    fun children(parentId: String) = dao.children(parentId)
    suspend fun childrenOnce(parentId: String) = dao.childrenOnce(parentId)
    fun observe(id: String) = dao.observe(id)
    suspend fun byId(id: String) = dao.byId(id)
    fun listTaskCounts() = dao.listTaskCounts()
    fun childCountsUnder(parentId: String) = dao.childCountsUnder(parentId)
    fun childCountsFor(parentIds: List<String>) = dao.childCountsFor(parentIds)

    /**
     * Stable Today lookup, self-healing for databases whose migration backfill found nothing
     * (e.g. the list was recreated by hand). Returns null if the user deleted/renamed it —
     * callers fall back to Home; never recreate it here.
     */
    suspend fun todaySmartList(): NodeEntity? =
        dao.bySystemKey(SystemKey.TODAY)
            ?: dao.byTypeAndTitle(NodeType.SMART_LIST, "Today")?.also {
                // Self-heal writes the page, not the row: the key lives in frontmatter, and
                // stamping only the index would come undone at the next reindex.
                runCatching { ws.writerFor(it.id).setSystemKey(it.id, SystemKey.TODAY) }
            }

    /**
     * The capture list, by stable identity — the single answer to "where does a quick-add go".
     *
     * Unlike [todaySmartList] this always returns something: capture is a gesture that must not
     * fail or ask, so a missing Inbox is created rather than reported. The order matters — key
     * first, then title, then create — because it is the fallback that used to be the whole
     * lookup, and doing it top-level-only is what let a grouped or renamed Inbox be duplicated.
     * Whatever the title match finds adopts the key, so the ambiguity is resolved once.
     */
    suspend fun inboxList(): String {
        dao.bySystemKey(SystemKey.INBOX)?.let { return it.id }
        dao.byTypeAndTitle(NodeType.LIST, INBOX_TITLE)?.let {
            // As in [todaySmartList]: the page carries the key, so the page is what gets written.
            runCatching { ws.writerFor(it.id).setSystemKey(it.id, SystemKey.INBOX) }
            return it.id
        }
        return ws.primary().createTopLevel(NodeType.LIST, INBOX_TITLE, SystemKey.INBOX)
    }

    /** Create a Home group/banner (organizational container for lists & smart lists). */
    suspend fun createGroup(title: String): String = ws.primary().createTopLevel(NodeType.GROUP, title)

    /** Move a list/smart list into a group (or back to top level when [groupId] is null). */
    suspend fun moveToGroup(id: String, groupId: String?) {
        ws.writerFor(id).reparent(id, groupId)
    }

    /** Delete a group but keep its lists — they return to the top level. */
    suspend fun deleteGroup(id: String) {
        dao.childrenOnce(id).forEach { ws.writerFor(it.id).reparent(it.id, null) }
        ws.writerFor(id).removeBlock(id)
    }

    /** Ancestor chain of [id], ordered root → immediate parent (excludes the node itself). */
    suspend fun ancestors(id: String): List<NodeEntity> {
        val chain = ArrayList<NodeEntity>()
        var cursor = dao.byId(id)?.parentId
        var guard = 0
        while (cursor != null && guard++ < 64) {
            val parent = dao.byId(cursor) ?: break
            chain.add(parent)
            cursor = parent.parentId
        }
        return chain.asReversed()
    }

    /**
     * A block on a page, or a page of its own when there is no parent.
     *
     * `afterId` places the new line directly after that one; without it the line is appended. Order
     * is line position now, so there is no rank to compute — the index derives one on the way back.
     */
    suspend fun create(
        parentId: String?,
        type: String,
        title: String?,
        afterId: String? = null,
        indent: Int = 0,
    ): String =
        if (parentId == null) ws.primary().createTopLevel(type, title)
        else ws.writerFor(parentId).addBlock(parentId, type, title, afterId, indent)


    /** Fast capture: new task into the Inbox. */
    suspend fun quickCaptureToInbox(title: String): String =
        create(inboxList(), NodeType.TASK, title)

    suspend fun rename(id: String, title: String?) =
        ws.writerFor(id).editBlock(id) { renamed(it, title.orEmpty()) }

    suspend fun setDone(id: String, done: Boolean) = ws.writerFor(id).editTask(id, Change.STRUCTURAL) {
        // Completion supersedes being started: a finished task is not still being worked on, and
        // the two were never allowed to be true at once.
        it.copy(status = if (done) TaskStatus.DONE else TaskStatus.OPEN)
    }

    suspend fun setInProgress(id: String, inProgress: Boolean) = ws.writerFor(id).editTask(id) {
        if (it.status == TaskStatus.DONE) it
        else it.copy(status = if (inProgress) TaskStatus.IN_PROGRESS else TaskStatus.OPEN)
    }

    /**
     * Device-local, and therefore the one write that stays in Room.
     *
     * Whether a section is folded is about this screen, not about the work. Syncing it would collapse
     * a list on the laptop because it was collapsed on the phone, so it is deliberately not in the
     * file — which also means a reindex forgets it, which is the correct amount of memory for it.
     */
    suspend fun setCollapsed(id: String, collapsed: Boolean) = dao.setCollapsed(id, collapsed, now())

    suspend fun normalizeIndents(parentId: String) = ws.writerFor(parentId).normalizeIndents(parentId)

    /** Sets a block's visual indentation, then re-clamps the run around it. */
    suspend fun setIndent(node: NodeEntity, indent: Int) {
        val parentId = node.parentId ?: return
        ws.writerFor(node.id).editBlock(node.id) { indented(it, indent.coerceAtLeast(0)) }
        normalizeIndents(parentId)
    }

    suspend fun setType(id: String, type: String) = ws.writerFor(id).convertBlock(id, type)

    suspend fun delete(id: String) = ws.writerFor(id).removeBlock(id)

    suspend fun moveUp(node: NodeEntity) = moveBy(node, -1)

    suspend fun moveDown(node: NodeEntity) = moveBy(node, +1)

    private suspend fun moveBy(node: NodeEntity, delta: Int) {
        val parentId = node.parentId ?: return
        val siblings = dao.childrenOnce(parentId)
        val at = siblings.indexOfFirst { it.id == node.id }
        val to = at + delta
        if (at < 0 || to !in siblings.indices) return
        ws.writerFor(node.id).moveBlock(node.id, to)
        normalizeIndents(parentId)
    }

    /** Drops [node] at [toIndex] among its siblings — the commit half of a drag. */
    suspend fun moveToIndex(node: NodeEntity, toIndex: Int) {
        val parentId = node.parentId ?: return
        ws.writerFor(node.id).moveBlock(node.id, toIndex)
        // A block dragged above the line it was indented under has to come back to a legal depth.
        normalizeIndents(parentId)
    }

}

private fun renamed(b: Block, text: String): Block = when (b) {
    is TaskRef -> b.copy(title = text)
    is Heading -> b.copy(text = text)
    is Bullet -> b.copy(text = text)
    is Numbered -> b.copy(text = text)
    is Prose -> b.copy(text = text)
    is ImageRef -> b.copy(uri = text)
    is InkRef -> b
}

private fun indented(b: Block, indent: Int): Block = when (b) {
    is TaskRef -> b.copy(indent = indent)
    is Heading -> b.copy(indent = indent)
    is Bullet -> b.copy(indent = indent)
    is Numbered -> b.copy(indent = indent)
    is Prose -> b.copy(indent = indent)
    is ImageRef -> b.copy(indent = indent)
    is InkRef -> b.copy(indent = indent)
}

@Serializable
data class SelectOption(val name: String, val color: Long? = null)

@Serializable
data class SelectConfig(val options: List<SelectOption> = emptyList())

/**
 * Property values live on the task's line, so setting one rewrites the page that holds it. The
 * defs themselves are the workspace registry and are read-only from here.
 */
class PropertyRepository(private val db: AppDatabase, private val ws: Workspaces) {
    private val dao = db.propertyDao()

    fun defs() = dao.defs()
    suspend fun defsOnce() = dao.defsOnce()
    fun builtInDefs() = dao.builtInDefs()
    suspend fun builtInDefsOnce() = dao.builtInDefsOnce()
    fun valuesForNode(nodeId: String) = dao.valuesForNode(nodeId)
    fun valuesUnder(parentId: String) = dao.valuesUnder(parentId)


    /**
     * Sets one built-in on a task. Which field it becomes is decided by the def, not by the caller,
     * because the line has a slot per meaning rather than a row per column.
     */
    suspend fun setValue(
        nodeId: String,
        defId: String,
        text: String? = null,
        number: Double? = null,
        date: Long? = null,
        bool: Boolean? = null,
    ) = ws.writerFor(nodeId).editTask(nodeId) { t ->
        when (defId) {
            BuiltIns.PRIORITY_DEF_ID -> t.copy(priority = text)
            BuiltIns.ASSIGNEE_DEF_ID -> t.copy(assignee = text)
            BuiltIns.DEADLINE_DEF_ID -> t.copy(deadline = date?.let { localDateOf(it) })
            BuiltIns.DUE_DEF_ID -> t.copy(due = date?.let { dueSpec(it, bool == true, number?.toInt()) })
            else -> t
        }
    }

    suspend fun dueDef(): PropertyDefEntity? =
        dao.builtInDefsOnce().firstOrNull { it.name.equals(BuiltIns.DUE_NAME, ignoreCase = true) }

    /**
     * Due, in the encoding [BuiltIns] documents: an exact instant when [hasTime], otherwise the
     * calendar day. The file distinguishes the two natively — a date has no `T` in it — so the
     * hasTime flag stops being a separate column and becomes a property of the value itself.
     */
    suspend fun setDue(nodeId: String, dateMillis: Long, hasTime: Boolean, reminderOffsetMin: Int?) =
        ws.writerFor(nodeId).editTask(nodeId) {
            it.copy(due = dueSpec(dateMillis, hasTime, reminderOffsetMin))
        }

    suspend fun setDeadline(nodeId: String, dateMillis: Long) =
        ws.writerFor(nodeId).editTask(nodeId) { it.copy(deadline = localDateOf(dateMillis)) }

    suspend fun clearValue(nodeId: String, defId: String) = ws.writerFor(nodeId).editTask(nodeId) { t ->
        when (defId) {
            BuiltIns.PRIORITY_DEF_ID -> t.copy(priority = null)
            BuiltIns.ASSIGNEE_DEF_ID -> t.copy(assignee = null)
            BuiltIns.DEADLINE_DEF_ID -> t.copy(deadline = null)
            BuiltIns.DUE_DEF_ID -> t.copy(due = null)
            else -> t
        }
    }

    private fun dueSpec(millis: Long, hasTime: Boolean, reminderMin: Int?) = DueSpec(
        if (hasTime) DueValue.At(java.time.Instant.ofEpochMilli(millis))
        else DueValue.AllDay(localDateOf(millis)),
        reminderMin,
    )

}

/**
 * A label is a name on a task's line and a colour in the workspace registry — two files, two
 * different reasons. Attaching writes the line; recolouring writes the registry.
 */
class LabelRepository(private val db: AppDatabase, private val ws: Workspaces) {
    private val dao = db.labelDao()

    fun all() = dao.all()
    suspend fun allOnce() = dao.allOnce()
    fun forNode(nodeId: String) = dao.forNode(nodeId)
    fun forChildrenOf(parentId: String) = dao.forChildrenOf(parentId)
    fun allNodeLabels() = dao.allNodeLabels()

    /**
     * Reuses an existing label by name, or registers a new one.
     *
     * Matching is case-insensitive because people type tags casually, but the registry's spelling
     * wins — `#Sync` and `#sync` are one tag, spelled the way it was first written.
     */
    suspend fun getOrCreate(name: String, color: Long? = null): LabelEntity {
        val trimmed = name.trim()
        dao.byName(trimmed)?.let { return it }
        val store = ws.primaryStore()
        val id = "${store.id}:label:${trimmed.lowercase()}"
        ws.primary().upsertLabel(
            LabelDef(id = id, name = trimmed, color = color ?: LabelPalette.defaultFor(trimmed))
        )
        return dao.byName(trimmed)
            ?: LabelEntity(id, store.id, trimmed, color, now(), now())
    }

    suspend fun attach(nodeId: String, labelId: String) {
        val name = dao.allOnce().firstOrNull { it.id == labelId }?.name ?: return
        ws.writerFor(nodeId).editTask(nodeId) {
            if (name in it.labels) it else it.copy(labels = it.labels + name)
        }
    }

    suspend fun detach(nodeId: String, labelId: String) {
        val name = dao.allOnce().firstOrNull { it.id == labelId }?.name ?: return
        ws.writerFor(nodeId).editTask(nodeId) { it.copy(labels = it.labels - name) }
    }

    /** Recolour a label. Null clears it back to the neutral chip. */
    suspend fun setColor(labelId: String, color: Long?) {
        val existing = dao.allOnce().firstOrNull { it.id == labelId } ?: return
        ws.primary().upsertLabel(LabelDef(id = labelId, name = existing.name, color = color))
    }

}

class SmartListRepository(private val db: AppDatabase, private val ws: Workspaces) {
    private val dao = db.smartListDao()
    private val nodeDao = db.nodeDao()
    private val propertyDao = db.propertyDao()

    fun observeDef(nodeId: String) = dao.observe(nodeId)
    suspend fun defById(nodeId: String) = dao.byId(nodeId)

    /** Read side: compile filter_json -> SQL and observe. Recompiled per call so relative dates stay fresh. */
    fun query(def: SmartListDefEntity): Flow<List<NodeEntity>> {
        val filter = FilterJson.decodeFromString(Filter.serializer(), def.filterJson)
        val sort = def.sortJson
            ?.let { FilterJson.decodeFromString(ListSerializer(SortSpec.serializer()), it) }
            ?: emptyList()
        val compiled = FilterCompiler.compile(def.scopeRootId, filter, sort, workspaceId = def.workspaceId)
        return nodeDao.rawNodeQuery(SimpleSQLiteQuery(compiled.sql, compiled.args.toTypedArray()))
    }

    fun allDefs(): Flow<List<SmartListDefEntity>> = dao.all()

    /**
     * The done counterpart of [query]. A rule like "due today AND not done" excludes completed
     * tasks by construction, so a smart list cannot report "n of m done" from its own matches — it
     * has to ask the opposite question too. Null when the rule has no done clause, because then
     * [query] already returns both halves and the done ones can simply be counted.
     */
    fun queryCompleted(def: SmartListDefEntity): Flow<List<NodeEntity>>? {
        val filter = FilterJson.decodeFromString(Filter.serializer(), def.filterJson)
        val flipped = completedVariant(filter) ?: return null
        val compiled = FilterCompiler.compile(def.scopeRootId, flipped, emptyList(), workspaceId = def.workspaceId)
        return nodeDao.rawNodeQuery(SimpleSQLiteQuery(compiled.sql, compiled.args.toTypedArray()))
    }

    suspend fun createSmartList(
        title: String,
        scopeRootId: String?,
        filter: Filter,
        sort: List<SortSpec>,
        homeParentId: String?,
    ): String = ws.primary().createSmartList(defFor("", scopeRootId, filter, sort, homeParentId), title)

    suspend fun updateSmartList(
        nodeId: String,
        scopeRootId: String?,
        filter: Filter,
        sort: List<SortSpec>,
        homeParentId: String?,
    ) = ws.writerFor(nodeId).updateSmartList(defFor(nodeId, scopeRootId, filter, sort, homeParentId))

    private fun defFor(
        nodeId: String,
        scopeRootId: String?,
        filter: Filter,
        sort: List<SortSpec>,
        homeParentId: String?,
    ): SmartListDef {
        val applyOnCreate = deriveApplyOnCreate(filter)
        return SmartListDef(
            nodeId = nodeId,
            scopeRootId = scopeRootId,
            filterJson = FilterJson.encodeToString(Filter.serializer(), filter),
            sortJson = FilterJson.encodeToString(ListSerializer(SortSpec.serializer()), sort),
            homeParentId = homeParentId,
            applyOnCreateJson =
                if (applyOnCreate.isEmpty()) null
                else FilterJson.encodeToString(ListSerializer(ApplyOnCreate.serializer()), applyOnCreate),
        )
    }

    /**
     * Write side: the task is added to `home_parent_id`, then the rule's own equality clauses are
     * stamped onto it so it satisfies the filter it was added through.
     */
    suspend fun addTask(def: SmartListDefEntity, title: String): String? {
        val homeId = def.homeParentId ?: def.scopeRootId ?: return null
        val writer = ws.writerFor(homeId)
        val id = writer.addBlock(homeId, NodeType.TASK, title)
        def.applyOnCreateJson
            ?.let { FilterJson.decodeFromString(ListSerializer(ApplyOnCreate.serializer()), it) }
            ?.forEach { p ->
                // dateRel defers resolution to insert time: a "due today" list stamps the actual
                // today, not the day the rule was written.
                writer.editTask(id) { t ->
                    when (p.defId) {
                        BuiltIns.PRIORITY_DEF_ID -> t.copy(priority = p.text)
                        BuiltIns.DUE_DEF_ID -> t.copy(
                            due = DueSpec(DueValue.AllDay(java.time.LocalDate.now()))
                        )
                        BuiltIns.DEADLINE_DEF_ID -> t.copy(deadline = java.time.LocalDate.now())
                        else -> t
                    }
                }
            }
        return id
    }
}
