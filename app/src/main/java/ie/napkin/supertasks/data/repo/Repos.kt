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

class NodeRepository(private val db: AppDatabase) {
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
                // Self-heal is best-effort: a pre-fix tombstone may still hold the key
                // (unique index) — the lookup result is valid either way.
                runCatching { dao.setSystemKey(it.id, SystemKey.TODAY, now()) }
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
            // Best-effort, exactly as in [todaySmartList]: a tombstone may still hold the key
            // under the unique index, and the id we return is correct either way.
            runCatching { dao.setSystemKey(it.id, SystemKey.INBOX, now()) }
            return it.id
        }
        val id = create(null, NodeType.LIST, INBOX_TITLE)
        runCatching { dao.setSystemKey(id, SystemKey.INBOX, now()) }
        return id
    }

    /** Create a Home group/banner (organizational container for lists & smart lists). */
    suspend fun createGroup(title: String): String = create(null, NodeType.GROUP, title)

    /** Move a list/smart list into a group (or back to top level when [groupId] is null). */
    suspend fun moveToGroup(id: String, groupId: String?) {
        val rank = if (groupId == null) Rank.after(dao.lastRankTopLevel()) else Rank.after(dao.lastRank(groupId))
        dao.move(id, groupId, rank, now())
    }

    /** Delete a group but keep its lists — they return to the top level. */
    suspend fun deleteGroup(id: String) {
        dao.childrenOnce(id).forEach { dao.move(it.id, null, Rank.after(dao.lastRankTopLevel()), now()) }
        dao.softDelete(listOf(id), now())
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

    suspend fun create(
        parentId: String?,
        type: String,
        title: String?,
        afterId: String? = null,
        indent: Int = 0,
    ): String {
        val ts = now()
        val id = newId()
        val rank = rankFor(parentId, afterId)
        dao.insert(
            NodeEntity(
                id = id, parentId = parentId, type = type, title = title,
                rank = rank, indent = indent, createdAt = ts, updatedAt = ts,
            )
        )
        return id
    }

    private suspend fun rankFor(parentId: String?, afterId: String?): String {
        if (afterId != null && parentId != null) {
            val after = dao.byId(afterId)
            if (after != null && after.parentId == parentId) {
                return Rank.between(after.rank, dao.nextRank(parentId, after.rank))
            }
        }
        val last = if (parentId == null) dao.lastRankTopLevel() else dao.lastRank(parentId)
        return Rank.after(last)
    }

    /**
     * Enforces the one rule a list has: a list holds tasks.
     *
     * Anything else that ends up directly on a list — from an older build, or an import — is moved
     * onto a task on that same list rather than hidden or deleted. Writing belongs on a task's
     * page, so that is where it goes, and it stays reachable by opening that task. Idempotent: in
     * the normal case this is one query per list that finds nothing and writes nothing.
     */
    suspend fun tidyListsToTasksOnly() {
        for (list in dao.allListsOnce()) {
            val children = dao.childrenOnce(list.id)
            val stray = children.filter { it.type != NodeType.TASK }
            if (stray.isEmpty()) continue
            val home = children
                .firstOrNull { it.type == NodeType.TASK && it.title == STRAY_NOTES_TASK }
                ?.id
                ?: create(list.id, NodeType.TASK, STRAY_NOTES_TASK)
            stray.forEach { block ->
                dao.move(block.id, home, Rank.after(dao.lastRank(home)), now())
            }
        }
    }

    /** Fast capture: new task into the Inbox. */
    suspend fun quickCaptureToInbox(title: String): String =
        create(inboxList(), NodeType.TASK, title)

    suspend fun rename(id: String, title: String?) = dao.setTitle(id, title, now())
    suspend fun setDone(id: String, done: Boolean) = dao.setDone(id, done, now())
    suspend fun setInProgress(id: String, inProgress: Boolean) = dao.setInProgress(id, inProgress, now())
    suspend fun setCollapsed(id: String, collapsed: Boolean) = dao.setCollapsed(id, collapsed, now())

    /**
     * The one rule indentation has to obey: the first line of a run sits flush left, and no line is
     * more than one step deeper than the line above it. Anything else cannot be read as structure.
     *
     * [indent] is stored rather than derived, so it does not stay true on its own — reordering a
     * block, or deleting the line above one, can leave an indent with nothing to be indented under.
     * Every operation that changes a sibling run therefore ends here, and this is the only place
     * that decides what a legal indent is.
     */
    suspend fun normalizeIndents(parentId: String) {
        var ceiling = 0
        dao.childrenOnce(parentId).forEach { n ->
            val fixed = n.indent.coerceIn(0, ceiling)
            if (fixed != n.indent) dao.setIndent(n.id, fixed, now())
            ceiling = fixed + 1
        }
    }

    /** Sets a block's visual indentation, then re-clamps the run around it. */
    suspend fun setIndent(node: NodeEntity, indent: Int) {
        val parentId = node.parentId ?: return
        dao.setIndent(node.id, indent.coerceAtLeast(0), now())
        normalizeIndents(parentId)
    }
    suspend fun setType(id: String, type: String) = dao.setType(id, type, now())
    suspend fun delete(id: String) = dao.softDeleteSubtree(id, now())

    suspend fun moveUp(node: NodeEntity) {
        val parentId = node.parentId ?: return
        val siblings = dao.childrenOnce(parentId)
        val idx = siblings.indexOfFirst { it.id == node.id }
        if (idx <= 0) return
        val prev = siblings[idx - 1]
        val prevPrev = siblings.getOrNull(idx - 2)
        dao.move(node.id, parentId, Rank.between(prevPrev?.rank, prev.rank), now())
    }

    suspend fun moveDown(node: NodeEntity) {
        val parentId = node.parentId ?: return
        val siblings = dao.childrenOnce(parentId)
        val idx = siblings.indexOfFirst { it.id == node.id }
        if (idx < 0 || idx >= siblings.size - 1) return
        val next = siblings[idx + 1]
        val nextNext = siblings.getOrNull(idx + 2)
        dao.move(node.id, parentId, Rank.between(next.rank, nextNext?.rank), now())
    }

    /**
     * Drops [node] into position [toIndex] among its siblings — the commit half of a drag.
     *
     * Indices are of the sibling list *without* [node], so a drag that ends where it started is a
     * no-op rather than a rank churn. Only the moved node's rank changes; neighbours are never
     * renumbered, which is the point of fractional ranks.
     */
    suspend fun moveToIndex(node: NodeEntity, toIndex: Int) {
        val parentId = node.parentId ?: return
        val others = dao.childrenOnce(parentId).filter { it.id != node.id }
        val target = toIndex.coerceIn(0, others.size)
        val before = others.getOrNull(target - 1)
        val after = others.getOrNull(target)
        if (before?.id == node.id || after?.id == node.id) return
        dao.move(node.id, parentId, Rank.between(before?.rank, after?.rank), now())
        // A block dragged to the top of a run, or above the line it was indented under, has to be
        // brought back to a legal depth.
        normalizeIndents(parentId)
    }

}

@Serializable
data class SelectOption(val name: String, val color: Long? = null)

@Serializable
data class SelectConfig(val options: List<SelectOption> = emptyList())

class PropertyRepository(private val db: AppDatabase) {
    private val dao = db.propertyDao()

    fun defs() = dao.defs()
    suspend fun defsOnce() = dao.defsOnce()
    fun builtInDefs() = dao.builtInDefs()
    suspend fun builtInDefsOnce() = dao.builtInDefsOnce()
    fun valuesForNode(nodeId: String) = dao.valuesForNode(nodeId)
    fun valuesUnder(parentId: String) = dao.valuesUnder(parentId)


    /**
     * Generic whole-row write (REPLACE — omitted columns become NULL). Never use for
     * Due/Deadline: their multi-column encodings go through [setDue]/[setDeadline].
     */
    suspend fun setValue(
        nodeId: String,
        defId: String,
        text: String? = null,
        number: Double? = null,
        date: Long? = null,
        bool: Boolean? = null,
    ) {
        dao.upsertValue(
            PropertyValueEntity(
                nodeId = nodeId, defId = defId,
                vText = text, vNumber = number, vDate = date, vBool = bool,
                updatedAt = now(),
            )
        )
    }

    suspend fun dueDef(): PropertyDefEntity? =
        dao.builtInDefsOnce().firstOrNull { it.name.equals(BuiltIns.DUE_NAME, ignoreCase = true) }

    /**
     * Whole-row Due write (encoding documented on [BuiltIns]). [dateMillis]: exact instant
     * when [hasTime], else any instant on the intended LOCAL day (floored to local midnight
     * here — callers must already have converted the M3 picker's UTC-midnight value).
     * [reminderOffsetMin]: minutes before the due instant; null = no reminder.
     */
    suspend fun setDue(nodeId: String, dateMillis: Long, hasTime: Boolean, reminderOffsetMin: Int?) {
        val def = dueDef() ?: return
        dao.upsertValue(
            PropertyValueEntity(
                nodeId = nodeId, defId = def.id,
                vNumber = reminderOffsetMin?.toDouble(),
                vDate = if (hasTime) dateMillis else localMidnight(dateMillis),
                vBool = hasTime,
                updatedAt = now(),
            )
        )
    }

    suspend fun setDeadline(nodeId: String, dateMillis: Long) {
        dao.upsertValue(
            PropertyValueEntity(
                nodeId = nodeId, defId = BuiltIns.DEADLINE_DEF_ID,
                vDate = localMidnight(dateMillis),
                updatedAt = now(),
            )
        )
    }

    suspend fun clearValue(nodeId: String, defId: String) = dao.deleteValue(nodeId, defId)

}

class LabelRepository(private val db: AppDatabase) {
    private val dao = db.labelDao()

    fun all() = dao.all()
    suspend fun allOnce() = dao.allOnce()
    fun forNode(nodeId: String) = dao.forNode(nodeId)
    fun forChildrenOf(parentId: String) = dao.forChildrenOf(parentId)
    fun allNodeLabels() = dao.allNodeLabels()

    /**
     * Reuses an existing label by name (case-insensitive) or creates a new one.
     *
     * A new label is never colourless: [color] wins if given, otherwise the palette picks one from
     * the name. Reuse keeps the colour the tag already has — typing an existing name is asking for
     * that tag, not asking to restyle it.
     */
    suspend fun getOrCreate(name: String, color: Long? = null): LabelEntity {
        val trimmed = name.trim()
        dao.byName(trimmed)?.let { return it }
        val ts = now()
        val label = LabelEntity(
            id = newId(), name = trimmed,
            color = color ?: LabelPalette.defaultFor(trimmed),
            createdAt = ts, updatedAt = ts,
        )
        dao.upsert(label)
        return dao.byName(trimmed) ?: label
    }

    suspend fun attach(nodeId: String, labelId: String) =
        dao.attach(NodeLabelEntity(nodeId = nodeId, labelId = labelId, createdAt = now()))

    suspend fun detach(nodeId: String, labelId: String) = dao.detach(nodeId, labelId)

    /** Recolour a label. Null clears it back to the neutral chip. */
    suspend fun setColor(labelId: String, color: Long?) = dao.setColor(labelId, color, now())

}

class SmartListRepository(private val db: AppDatabase) {
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
        val compiled = FilterCompiler.compile(def.scopeRootId, filter, sort)
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
        val compiled = FilterCompiler.compile(def.scopeRootId, flipped, emptyList())
        return nodeDao.rawNodeQuery(SimpleSQLiteQuery(compiled.sql, compiled.args.toTypedArray()))
    }

    suspend fun createSmartList(
        title: String,
        scopeRootId: String?,
        filter: Filter,
        sort: List<SortSpec>,
        homeParentId: String?,
    ): String = db.withTransaction {
        val ts = now()
        val id = newId()
        nodeDao.insert(
            NodeEntity(
                id = id, parentId = null, type = NodeType.SMART_LIST, title = title,
                rank = Rank.after(nodeDao.lastRankTopLevel()), createdAt = ts, updatedAt = ts,
            )
        )
        dao.upsert(defFor(id, scopeRootId, filter, sort, homeParentId))
        id
    }

    suspend fun updateSmartList(
        nodeId: String,
        scopeRootId: String?,
        filter: Filter,
        sort: List<SortSpec>,
        homeParentId: String?,
    ) {
        dao.upsert(defFor(nodeId, scopeRootId, filter, sort, homeParentId))
    }

    private fun defFor(
        nodeId: String,
        scopeRootId: String?,
        filter: Filter,
        sort: List<SortSpec>,
        homeParentId: String?,
    ): SmartListDefEntity {
        val applyOnCreate = deriveApplyOnCreate(filter)
        return SmartListDefEntity(
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
     * Write side: the new task physically lives in home_parent_id, then apply_on_create
     * property values make it satisfy the filter (for equality filters).
     */
    suspend fun addTask(def: SmartListDefEntity, title: String): String? {
        val homeId = def.homeParentId ?: def.scopeRootId ?: return null
        return db.withTransaction {
            val ts = now()
            val id = newId()
            nodeDao.insert(
                NodeEntity(
                    id = id, parentId = homeId, type = NodeType.TASK, title = title,
                    rank = Rank.after(nodeDao.lastRank(homeId)), createdAt = ts, updatedAt = ts,
                )
            )
            def.applyOnCreateJson
                ?.let { FilterJson.decodeFromString(ListSerializer(ApplyOnCreate.serializer()), it) }
                ?.forEach { p ->
                    // dateRel defers resolution to insert time: a "due today" list stamps
                    // the actual today (local midnight), not the day the list was defined.
                    val date = if (p.dateRel != null) localMidnight(ts) else p.date
                    propertyDao.upsertValue(
                        PropertyValueEntity(
                            nodeId = id, defId = p.defId,
                            vText = p.text, vNumber = p.number, vDate = date,
                            vBool = p.bool,
                            updatedAt = ts,
                        )
                    )
                }
            id
        }
    }
}
