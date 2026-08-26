package ie.napkin.supertasks.data.seed

import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.SystemKey
import ie.napkin.supertasks.data.filter.ApplyOnCreate
import ie.napkin.supertasks.data.filter.DateRel
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.filter.Op
import ie.napkin.supertasks.data.filter.SortBy
import ie.napkin.supertasks.data.filter.SortSpec
import ie.napkin.supertasks.data.filter.deriveApplyOnCreate
import ie.napkin.supertasks.data.format.Heading
import ie.napkin.supertasks.data.format.PageDoc
import ie.napkin.supertasks.data.format.Prose
import ie.napkin.supertasks.data.format.TaskRef
import ie.napkin.supertasks.data.workspace.SmartListDef
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.util.UUID

/**
 * First-run content, written as files.
 *
 * **Gated on scaffolding, never on an empty index.** The index is empty on every device that clones
 * an existing workspace, so seeding on emptiness would give each machine that joins its own second
 * Inbox and its own second Today. The caller only reaches here when it has just created the
 * directory — see [ie.napkin.supertasks.data.workspace.Workspaces.open].
 */
object WorkspaceSeeder {

    fun seed(store: WorkspaceStore, now: Long = System.currentTimeMillis()) {
        val stamp = Instant.ofEpochMilli(now)
        fun id() = UUID.randomUUID().toString()

        val inbox = id()
        store.writePage(
            PageDoc(
                id = inbox, type = NodeType.LIST, parent = null, title = "Inbox",
                systemKey = SystemKey.INBOX, modifiedAt = stamp, device = null, blocks = emptyList(),
            )
        )

        // A list holds tasks. What a task's page can hold is therefore explained on a task's page,
        // which is also the shortest way to demonstrate the point.
        val sample = id()
        val started = id()
        val gettingStarted = id()
        store.writePage(
            PageDoc(
                id = gettingStarted, type = NodeType.LIST, parent = null, title = "Getting started",
                modifiedAt = stamp, device = null,
                blocks = listOf(
                    TaskRef(
                        id = sample, title = "Try opening this task as a page",
                        priority = "High",
                        due = ie.napkin.supertasks.data.format.DueSpec(
                            ie.napkin.supertasks.data.format.DueValue.AllDay(
                                java.time.LocalDate.now()
                            )
                        ),
                    ),
                    TaskRef(id = started, title = "Start a focus on any task", priority = "Medium"),
                    TaskRef(id = id(), title = "Add an ink block and scribble on it ✏️"),
                ),
            )
        )
        store.writePage(
            PageDoc(
                id = sample, type = NodeType.TASK, parent = gettingStarted, title = null,
                modifiedAt = stamp, device = null,
                blocks = listOf(
                    Heading("Welcome 👋"),
                    Prose(
                        "A list holds tasks. A task's own page holds anything — notes, headings, " +
                            "lists, ink sketches, images — and other tasks."
                    ),
                    TaskRef(id = id(), title = "Tasks can nest — this is a subtask"),
                ),
            )
        )

        // ---- smart lists: computed views over the same pages ----

        val todayFilter = Filter.All(
            listOf(
                Filter.Type(NodeType.TASK),
                Filter.Done(false),
                Filter.AnyOf(
                    listOf(
                        Filter.Prop(defId = BuiltIns.DUE_DEF_ID, op = Op.LTE, dateRel = DateRel.TODAY_END),
                        Filter.Prop(defId = BuiltIns.DEADLINE_DEF_ID, op = Op.LTE, dateRel = DateRel.TODAY_END),
                    )
                ),
            )
        )
        val today = id()
        store.writePage(
            PageDoc(
                id = today, type = NodeType.SMART_LIST, parent = null, title = "Today",
                systemKey = SystemKey.TODAY, modifiedAt = stamp, device = null, blocks = emptyList(),
            )
        )
        store.writeSmartList(
            SmartListDef(
                nodeId = today,
                filterJson = FilterJson.encodeToString(Filter.serializer(), todayFilter),
                sortJson = FilterJson.encodeToString(
                    ListSerializer(SortSpec.serializer()),
                    listOf(SortSpec(by = SortBy.PROP_DATE, defId = BuiltIns.DUE_DEF_ID)),
                ),
                homeParentId = inbox,
                applyOnCreateJson = deriveApplyOnCreate(todayFilter).takeIf { it.isNotEmpty() }
                    ?.let { FilterJson.encodeToString(ListSerializer(ApplyOnCreate.serializer()), it) },
            )
        )

        val high = id()
        store.writePage(
            PageDoc(
                id = high, type = NodeType.SMART_LIST, parent = null, title = "High Priority",
                modifiedAt = stamp, device = null, blocks = emptyList(),
            )
        )
        store.writeSmartList(
            SmartListDef(
                nodeId = high,
                filterJson = FilterJson.encodeToString(
                    Filter.serializer(),
                    Filter.All(
                        listOf(
                            Filter.Type(NodeType.TASK),
                            Filter.Done(false),
                            Filter.Prop(defId = BuiltIns.PRIORITY_DEF_ID, op = Op.EQ, text = "High"),
                        )
                    ),
                ),
                sortJson = FilterJson.encodeToString(
                    ListSerializer(SortSpec.serializer()),
                    listOf(SortSpec(by = SortBy.CREATED, desc = true)),
                ),
                homeParentId = inbox,
                // An equality filter is writable and self-satisfying: a task added here gets the
                // priority that makes it qualify.
                applyOnCreateJson = FilterJson.encodeToString(
                    ListSerializer(ApplyOnCreate.serializer()),
                    listOf(ApplyOnCreate(defId = BuiltIns.PRIORITY_DEF_ID, text = "High")),
                ),
            )
        )
    }

    /**
     * First-run content for a workspace that points at a repository.
     *
     * Deliberately almost nothing: one list, named after the workspace. The full seed above writes
     * an Inbox, a Today and a High priority — all of which already exist, in the local workspace,
     * and all of which span every workspace because the user asked for one Today rather than one per
     * repo. Seeding them again per repository would put a second Inbox and a second Today on Home
     * for every project someone links, which is the mess this app is supposed to be the opposite of.
     *
     * A system key is deliberately absent too. `SystemKey.INBOX` is an identity, not a label, and
     * two pages claiming it would make "the Inbox" a question rather than a place.
     */
    fun seedLinked(store: WorkspaceStore, name: String, now: Long = System.currentTimeMillis()) {
        store.writePage(
            PageDoc(
                id = UUID.randomUUID().toString(),
                type = NodeType.LIST,
                parent = null,
                title = name,
                modifiedAt = Instant.ofEpochMilli(now),
                device = null,
                blocks = emptyList(),
            )
        )
    }
}
