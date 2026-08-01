package ie.napkin.supertasks.data.repo

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeLabelEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.data.db.SmartListDefEntity
import ie.napkin.supertasks.data.filter.ApplyOnCreate
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterCompiler
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.filter.SortSpec
import ie.napkin.supertasks.data.filter.deriveApplyOnCreate
import ie.napkin.supertasks.data.rank.Rank
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

private fun now(): Long = System.currentTimeMillis()
private fun newId(): String = UUID.randomUUID().toString()

class NodeRepository(private val db: AppDatabase) {
    private val dao = db.nodeDao()

    fun topLevel() = dao.topLevel()
    fun allLists() = dao.allLists()
    fun children(parentId: String) = dao.children(parentId)
    suspend fun childrenOnce(parentId: String) = dao.childrenOnce(parentId)
    fun observe(id: String) = dao.observe(id)
    suspend fun byId(id: String) = dao.byId(id)
    fun topLevelTaskCounts() = dao.topLevelTaskCounts()
    fun listTaskCounts() = dao.listTaskCounts()
    fun childCountsUnder(parentId: String) = dao.childCountsUnder(parentId)

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
    ): String {
        val ts = now()
        val id = newId()
        val rank = rankFor(parentId, afterId)
        dao.insert(
            NodeEntity(
                id = id, parentId = parentId, type = type, title = title,
                rank = rank, createdAt = ts, updatedAt = ts,
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

    suspend fun rename(id: String, title: String?) = dao.setTitle(id, title, now())
    suspend fun setDone(id: String, done: Boolean) = dao.setDone(id, done, now())
    suspend fun setCollapsed(id: String, collapsed: Boolean) = dao.setCollapsed(id, collapsed, now())
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

    /** Makes the node the last child of its previous sibling. */
    suspend fun indent(node: NodeEntity) {
        val parentId = node.parentId ?: return
        val siblings = dao.childrenOnce(parentId)
        val idx = siblings.indexOfFirst { it.id == node.id }
        if (idx <= 0) return
        val newParent = siblings[idx - 1]
        dao.move(node.id, newParent.id, Rank.after(dao.lastRank(newParent.id)), now())
    }

    /** Moves the node to be the next sibling of its parent. */
    suspend fun outdent(node: NodeEntity, pageRootId: String) {
        val parentId = node.parentId ?: return
        if (parentId == pageRootId) return // already at page level
        val parent = dao.byId(parentId) ?: return
        val grandparentId = parent.parentId ?: return
        val next = dao.nextRank(grandparentId, parent.rank)
        dao.move(node.id, grandparentId, Rank.between(parent.rank, next), now())
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

    suspend fun createDef(name: String, kind: String, config: String? = null): String {
        val ts = now()
        val id = newId()
        dao.upsertDef(
            PropertyDefEntity(id = id, name = name, kind = kind, config = config, createdAt = ts, updatedAt = ts)
        )
        return id
    }

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

    suspend fun clearValue(nodeId: String, defId: String) = dao.deleteValue(nodeId, defId)

    fun parseSelectConfig(def: PropertyDefEntity): SelectConfig =
        def.config?.let { runCatching { FilterJson.decodeFromString(SelectConfig.serializer(), it) }.getOrNull() }
            ?: SelectConfig()
}

class LabelRepository(private val db: AppDatabase) {
    private val dao = db.labelDao()

    fun all() = dao.all()
    suspend fun allOnce() = dao.allOnce()
    fun forNode(nodeId: String) = dao.forNode(nodeId)
    fun forChildrenOf(parentId: String) = dao.forChildrenOf(parentId)
    fun allNodeLabels() = dao.allNodeLabels()

    /** Reuses an existing label by name (case-insensitive) or creates a new one. */
    suspend fun getOrCreate(name: String, color: Long? = null): LabelEntity {
        val trimmed = name.trim()
        dao.byName(trimmed)?.let { return it }
        val ts = now()
        val label = LabelEntity(id = newId(), name = trimmed, color = color, createdAt = ts, updatedAt = ts)
        dao.upsert(label)
        return dao.byName(trimmed) ?: label
    }

    suspend fun attach(nodeId: String, labelId: String) =
        dao.attach(NodeLabelEntity(nodeId = nodeId, labelId = labelId, createdAt = now()))

    suspend fun detach(nodeId: String, labelId: String) = dao.detach(nodeId, labelId)

    /** Real delete — detaches this label from every task it was on (node_label cascades). */
    suspend fun deleteLabel(labelId: String) = dao.delete(labelId)
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
                    propertyDao.upsertValue(
                        PropertyValueEntity(
                            nodeId = id, defId = p.defId,
                            vText = p.text, vNumber = p.number, vDate = p.date,
                            vBool = p.bool?.let { it },
                            updatedAt = ts,
                        )
                    )
                }
            id
        }
    }
}
