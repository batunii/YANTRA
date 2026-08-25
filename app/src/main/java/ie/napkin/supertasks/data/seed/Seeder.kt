package ie.napkin.supertasks.data.seed

import androidx.room.withTransaction
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyKind
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.data.db.SmartListDefEntity
import ie.napkin.supertasks.data.db.SystemKey
import ie.napkin.supertasks.data.filter.ApplyOnCreate
import ie.napkin.supertasks.data.filter.DateRel
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.filter.Op
import ie.napkin.supertasks.data.filter.SortBy
import ie.napkin.supertasks.data.filter.SortSpec
import ie.napkin.supertasks.data.filter.deriveApplyOnCreate
import ie.napkin.supertasks.data.rank.Rank
import ie.napkin.supertasks.data.time.todayMidnight
import ie.napkin.supertasks.data.repo.SelectConfig
import ie.napkin.supertasks.data.repo.SelectOption
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** First-run content: two property defs, an Inbox + sample list, and two smart lists. */
object Seeder {

    suspend fun seedIfEmpty(db: AppDatabase) {
        if (db.nodeDao().countAll() > 0) return
        db.withTransaction {
            if (db.nodeDao().countAll() > 0) return@withTransaction
            seed(db)
        }
    }

    private suspend fun seed(db: AppDatabase) {
        val now = System.currentTimeMillis()
        val nodeDao = db.nodeDao()
        val propDao = db.propertyDao()

        fun id() = UUID.randomUUID().toString()

        // ---- property defs ----
        val priorityDef = id()
        propDao.upsertDef(
            PropertyDefEntity(
                id = priorityDef, name = BuiltIns.PRIORITY_NAME, kind = PropertyKind.SELECT,
                config = FilterJson.encodeToString(
                    SelectConfig.serializer(),
                    SelectConfig(
                        listOf(
                            SelectOption("High", 0xFFFF4A1F),
                            SelectOption("Medium", 0xFFFFB020),
                            SelectOption("Low", 0xFF4A90D9),
                        )
                    )
                ),
                isBuiltIn = true,
                createdAt = now, updatedAt = now,
            )
        )
        val dueDef = id()
        propDao.upsertDef(
            PropertyDefEntity(
                id = dueDef, name = BuiltIns.DUE_NAME, kind = PropertyKind.DATE, config = null,
                isBuiltIn = true,
                createdAt = now, updatedAt = now,
            )
        )
        propDao.upsertDef(
            PropertyDefEntity(
                id = BuiltIns.DEADLINE_DEF_ID, name = BuiltIns.DEADLINE_NAME, kind = PropertyKind.DATE,
                config = null, isBuiltIn = true,
                createdAt = now, updatedAt = now,
            )
        )

        // ---- lists ----
        var rank = Rank.FIRST
        val inbox = id()
        nodeDao.insert(
            NodeEntity(id = inbox, parentId = null, type = NodeType.LIST, title = "Inbox", rank = rank, systemKey = SystemKey.INBOX, createdAt = now, updatedAt = now)
        )

        rank = Rank.after(rank)
        val personal = id()
        nodeDao.insert(
            NodeEntity(id = personal, parentId = null, type = NodeType.LIST, title = "Getting started", rank = rank, createdAt = now, updatedAt = now)
        )

        // A list holds tasks. The explanation of what a task's page can hold therefore lives on a
        // task's page, which is also the shortest way to demonstrate the point.
        var childRank = Rank.FIRST
        val sampleTask = id()
        nodeDao.insert(
            NodeEntity(id = sampleTask, parentId = personal, type = NodeType.TASK, title = "Try opening this task as a page", rank = childRank, createdAt = now, updatedAt = now)
        )
        propDao.upsertValue(PropertyValueEntity(nodeId = sampleTask, defId = priorityDef, vText = "High", updatedAt = now))
        // All-day Due = today's local midnight (encoding on BuiltIns).
        val todayMidnight = todayMidnight()
        propDao.upsertValue(
            PropertyValueEntity(nodeId = sampleTask, defId = dueDef, vDate = todayMidnight, vBool = false, updatedAt = now)
        )

        var subRank = Rank.FIRST
        nodeDao.insert(
            NodeEntity(id = id(), parentId = sampleTask, type = NodeType.HEADING, title = "Welcome \uD83D\uDC4B", rank = subRank, createdAt = now, updatedAt = now)
        )
        subRank = Rank.after(subRank)
        nodeDao.insert(
            NodeEntity(
                id = id(), parentId = sampleTask, type = NodeType.PARAGRAPH,
                title = "A list holds tasks. A task's own page holds anything — notes, headings, lists, ink sketches, images — and other tasks.",
                rank = subRank, createdAt = now, updatedAt = now,
            )
        )
        subRank = Rank.after(subRank)
        nodeDao.insert(
            NodeEntity(id = id(), parentId = sampleTask, type = NodeType.TASK, title = "Tasks can nest — this is a subtask", rank = subRank, createdAt = now, updatedAt = now)
        )

        childRank = Rank.after(childRank)
        val task2 = id()
        nodeDao.insert(
            NodeEntity(id = task2, parentId = personal, type = NodeType.TASK, title = "Start a pomodoro on any task", rank = childRank, createdAt = now, updatedAt = now)
        )
        propDao.upsertValue(PropertyValueEntity(nodeId = task2, defId = priorityDef, vText = "Medium", updatedAt = now))

        childRank = Rank.after(childRank)
        nodeDao.insert(
            NodeEntity(id = id(), parentId = personal, type = NodeType.TASK, title = "Add an ink block and scribble on it ✏️", rank = childRank, createdAt = now, updatedAt = now)
        )

        // ---- smart lists (computed views over the same nodes) ----
        rank = Rank.after(rank)
        val today = id()
        nodeDao.insert(
            NodeEntity(id = today, parentId = null, type = NodeType.SMART_LIST, title = "Today", rank = rank, systemKey = SystemKey.TODAY, createdAt = now, updatedAt = now)
        )
        // Todoist rule: Due ≤ today OR Deadline ≤ today. Quick-added tasks get Due = today
        // via the derived apply-on-create (dateRel resolved at insert), so they stay in view.
        val todayFilter = Filter.All(
            listOf(
                Filter.Type(NodeType.TASK),
                Filter.Done(false),
                Filter.AnyOf(
                    listOf(
                        Filter.Prop(defId = dueDef, op = Op.LTE, dateRel = DateRel.TODAY_END),
                        Filter.Prop(defId = BuiltIns.DEADLINE_DEF_ID, op = Op.LTE, dateRel = DateRel.TODAY_END),
                    )
                ),
            )
        )
        val todayApply = deriveApplyOnCreate(todayFilter)
        db.smartListDao().upsert(
            SmartListDefEntity(
                nodeId = today,
                scopeRootId = null,
                filterJson = FilterJson.encodeToString(Filter.serializer(), todayFilter),
                sortJson = FilterJson.encodeToString(
                    ListSerializer(SortSpec.serializer()),
                    listOf(SortSpec(by = SortBy.PROP_DATE, defId = dueDef)),
                ),
                homeParentId = inbox,
                applyOnCreateJson = todayApply.takeIf { it.isNotEmpty() }
                    ?.let { FilterJson.encodeToString(ListSerializer(ApplyOnCreate.serializer()), it) },
            )
        )

        rank = Rank.after(rank)
        val highPriority = id()
        nodeDao.insert(
            NodeEntity(id = highPriority, parentId = null, type = NodeType.SMART_LIST, title = "High Priority", rank = rank, createdAt = now, updatedAt = now)
        )
        db.smartListDao().upsert(
            SmartListDefEntity(
                nodeId = highPriority,
                scopeRootId = null,
                filterJson = FilterJson.encodeToString(
                    Filter.serializer(),
                    Filter.All(
                        listOf(
                            Filter.Type(NodeType.TASK),
                            Filter.Done(false),
                            Filter.Prop(defId = priorityDef, op = Op.EQ, text = "High"),
                        )
                    )
                ),
                sortJson = FilterJson.encodeToString(
                    ListSerializer(SortSpec.serializer()),
                    listOf(SortSpec(by = SortBy.CREATED, desc = true)),
                ),
                homeParentId = inbox,
                // equality filter -> writable & self-satisfying
                applyOnCreateJson = FilterJson.encodeToString(
                    ListSerializer(ApplyOnCreate.serializer()),
                    listOf(ApplyOnCreate(defId = priorityDef, text = "High")),
                ),
            )
        )
    }
}
