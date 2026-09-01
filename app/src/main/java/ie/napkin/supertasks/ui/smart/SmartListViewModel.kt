package ie.napkin.supertasks.ui.smart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.data.workspace.WorkspaceEntry
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.filter.SortSpec
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.SmartListDefEntity
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.filter.Op
import ie.napkin.supertasks.ui.components.ChipData
import ie.napkin.supertasks.ui.components.buildChips
import ie.napkin.supertasks.ui.components.buildLabelChips
import ie.napkin.supertasks.ui.components.strangerAgainst
import ie.napkin.supertasks.ui.components.dateLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ie.napkin.supertasks.data.db.NodeType

@OptIn(ExperimentalCoroutinesApi::class)
class SmartListViewModel(
    private val container: AppContainer,
    val nodeId: String,
) : ViewModel() {
    private val smartLists = container.smartLists
    private val nodes = container.nodes
    private val properties = container.properties

    /**
     * The workspaces the builder may offer as a rule's reach.
     *
     * Registry order, filtered to what is actually open — a repo listed but not opened cannot be
     * searched, and offering it would let someone write a rule that silently matches nothing.
     */
    val workspaces: List<WorkspaceEntry> =
        container.registry.entries().filter { container.workspaces.isOpen(it.id) }

    val node: StateFlow<NodeEntity?> =
        nodes.observe(nodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val def: StateFlow<SmartListDefEntity?> =
        smartLists.observeDef(nodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Workspaces this view asks about that are not on this device, by name.
     *
     * Shown rather than swallowed. A rule spanning repos can only answer for the ones present, and
     * a Today quietly missing half your tasks is worse than no Today — the absence has to be on the
     * screen, where it also tells you the fix (add the workspace back).
     */
    val absentWorkspaces: StateFlow<List<String>> =
        def.map { d ->
            if (d == null) emptyList() else {
                val names = container.registry.entries().associate { it.id to it.name }
                smartLists.absentWorkspaces(d).map { names[it] ?: "an unknown workspace" }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Read side: children are computed, not stored. */
    val tasks: StateFlow<List<NodeEntity>> =
        def.flatMapLatest { d -> if (d == null) flowOf(emptyList()) else smartLists.query(d) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * What you finished in this view. A rule like Today's says "due today AND not done", so
     * completing a task drops it out of its own view — the day's work disappears exactly as you do
     * it. Today is the tasks that are for today, done or not, so the completed half is asked for
     * separately and shown below.
     *
     * Empty when the rule has no done clause, because then [tasks] already contains both halves and
     * there is nothing to append.
     */
    val completed: StateFlow<List<NodeEntity>> =
        def.flatMapLatest { d ->
            if (d == null) flowOf(emptyList())
            else smartLists.queryCompleted(d) ?: flowOf(emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Matching tasks can live anywhere, so chips come from the full value/label set. */
    /**
     * The current title of everything the shown tasks link to, by id.
     *
     * The same lookup the node page does, and it is here for the same reason: without it a task
     * whose title holds a link would read one way on its own page and another in a view of it, the
     * moment anybody renamed what it points at. A link is a reference to a thing, not a copy of
     * what that thing was called when the reference was made — and a view that disagrees with the
     * page about which is which is a view you cannot trust.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val linkTitles: StateFlow<Map<String, String>> =
        combine(tasks, completed) { open, done ->
            (open + done).mapNotNull { it.title }
                .flatMap { ie.napkin.supertasks.data.format.Links.targets(it) }
                .distinct()
        }
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(emptyMap())
                else nodes.byIds(ids).map { rows ->
                    rows.associate { it.id to (it.title?.takeIf { t -> t.isNotBlank() } ?: "Untitled") }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val chips: StateFlow<Map<String, List<ChipData>>> =
        combine(
            properties.defs(),
            container.db.propertyDao().allValues(),
            container.labels.all(),
            container.labels.allNodeLabels(),
            container.people.rosters(),
        ) { d, v, labels, nodeLabels, rosters ->
            // Judged per row's own repository: a smart list gathers from every workspace at once,
            // so "can this person see it" has a different answer for two rows side by side.
            val merged = buildChips(d, v, strangerAgainst(rosters)).toMutableMap()
            buildLabelChips(labels, nodeLabels).forEach { (nodeId, chips) ->
                merged[nodeId] = merged[nodeId].orEmpty() + chips
            }
            merged
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Subtask counts for whatever is currently matching, so the chevron reads the same here. */
    val childCounts: StateFlow<Map<String, Int>> =
        combine(tasks, completed) { open, done -> open + done }
            .flatMapLatest { list ->
                if (list.isEmpty()) flowOf(emptyMap())
                else nodes.childCountsFor(list.map { it.id })
                    // Open subtasks — the same thing the badge means on a task's own page.
                    .map { rows ->
                        rows.associate {
                            it.rootId to (it.taskCount - it.doneCount).coerceAtLeast(0)
                        }
                    }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val pomoCounts: StateFlow<Map<String, Int>> =
        container.focus.perNode()
            .map { list -> list.associate { it.nodeId to it.count } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** e.g. "Open tasks · Priority = High · label: groceries · new tasks land in Inbox" */
    val description: StateFlow<String> =
        combine(def, properties.defs(), container.labels.all()) { d, defs, labels -> describe(d, defs, labels) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * Who a typed `@` may name here — the roster of the workspace new tasks land in.
     *
     * A smart list spans repos, but a task it creates lands in exactly one: its home list's. So the
     * answer is a property of the rule, not of whatever is currently on screen.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val assignable: StateFlow<List<String>> =
        def.flatMapLatest { d ->
            flowOf(d?.homeParentId).map { home ->
                container.people.loginsFor(home?.let { nodes.byId(it)?.workspaceId }.orEmpty())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun linkTargets(query: String) = nodes.searchLinkTargets(query)

    suspend fun linkIdsFor(text: String) = nodes.linkIdsFor(text)

    fun addTask(title: String) {
        viewModelScope.launch {
            val d = def.value ?: smartLists.defById(nodeId) ?: return@launch
            // A smart list is a view, so the workspace a new task lands in is the one its home list
            // is in — that is where `@name` has to be checked, and it is not necessarily the
            // workspace of anything currently on screen.
            val homeWorkspace = d.homeParentId?.let { nodes.byId(it)?.workspaceId }.orEmpty()
            val parsed = ie.napkin.supertasks.data.capture.CaptureParse.parse(
                title,
                people = container.people.loginsFor(homeWorkspace),
                links = nodes.linkIdsFor(title),
            )
            if (parsed.title.isBlank()) return@launch
            // The list's own apply-on-create lands first — a task added to Today is due today — and
            // anything typed on the line then overrides it, because an explicit "friday" must beat
            // the list's default rather than lose to it.
            val id = smartLists.addTask(d, parsed.title) ?: return@launch
            applyCaptured(id, parsed)
        }
    }

    private suspend fun applyCaptured(id: String, parsed: ie.napkin.supertasks.data.capture.Captured) {
        parsed.dueAt()?.let { at ->
            container.properties.setDue(
                id,
                at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                hasTime = parsed.time != null,
                reminderOffsetMin = null,
            )
        }
        parsed.priority?.let {
            container.properties.setValue(id, ie.napkin.supertasks.data.db.BuiltIns.PRIORITY_DEF_ID, text = it)
        }
        parsed.assignee?.let {
            container.properties.setValue(id, ie.napkin.supertasks.data.db.BuiltIns.ASSIGNEE_DEF_ID, text = it)
        }
        parsed.labels.forEach { name ->
            container.labels.attach(id, container.labels.getOrCreate(name).id)
        }
    }

    // What the rule editor needs to offer choices: the properties you can filter on, the labels you
    // can match, and the lists a new task could land in. Same three inputs the create sheet takes on
    // the home screen, because it is the same sheet.
    val defs: StateFlow<List<PropertyDefEntity>> =
        properties.builtInDefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val labels: StateFlow<List<LabelEntity>> =
        container.labels.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lists: StateFlow<List<NodeEntity>> =
        nodes.allLists()
            .map { all -> all.filter { it.type == NodeType.LIST } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun createLabel(name: String) = container.labels.getOrCreate(name)

    /** Rename the view itself, not a task in it. */
    fun renameList(title: String) {
        viewModelScope.launch { nodes.rename(nodeId, title) }
    }

    /**
     * Rewrite this view's rule. Nothing else has to happen: the task list is a query, so the screen
     * re-populates from the new definition the moment it lands.
     */
    fun updateRule(filter: Filter, sort: List<SortSpec>, homeParentId: String?) {
        viewModelScope.launch {
            smartLists.updateSmartList(
                nodeId = nodeId,
                scopeRootId = def.value?.scopeRootId,
                filter = filter,
                sort = sort,
                homeParentId = homeParentId,
            )
        }
    }

    fun setDone(id: String, done: Boolean) {
        viewModelScope.launch { nodes.setDone(id, done) }
    }

    fun setInProgress(id: String, inProgress: Boolean) {
        viewModelScope.launch { nodes.setInProgress(id, inProgress) }
    }

    private suspend fun describe(d: SmartListDefEntity?, defs: List<PropertyDefEntity>, labels: List<LabelEntity>): String {
        if (d == null) return ""
        val defById = defs.associateBy { it.id }
        val labelById = labels.associateBy { it.id }
        // Named from the registry, not the index: a rule may name a workspace this device has since
        // forgotten, and "from 93c907a5-…" is worse than nothing on a one-line pill.
        val wsById = container.registry.entries().associate { it.id to it.name }
        val parts = mutableListOf<String>()
        val filter = runCatching { FilterJson.decodeFromString(Filter.serializer(), d.filterJson) }.getOrNull()
        collectParts(filter, defById, labelById, wsById, parts)
        val home = d.homeParentId?.let { nodes.byId(it)?.title }
        if (home != null) parts += "lands in $home"
        // This renders as a filter pill next to the title, not a sentence under it — keep it to
        // the few parts that actually fit on one line.
        return parts.take(3).joinToString(" · ")
    }

    private fun collectParts(
        f: Filter?,
        defs: Map<String, PropertyDefEntity>,
        labels: Map<String, LabelEntity>,
        workspaces: Map<String, String>,
        out: MutableList<String>,
    ) {
        when (f) {
            null -> Unit
            is Filter.All -> f.filters.forEach { collectParts(it, defs, labels, workspaces, out) }
            is Filter.AnyOf -> out += "${f.filters.size} rules"
            is Filter.Not -> out += "1 exclusion"
            // "open tasks" is the default reading of a task list — only the unusual case is worth
            // the pill's one line.
            is Filter.Done -> if (f.value) out += "completed"
            is Filter.InProgress -> out += if (f.value) "started" else "not started"
            is Filter.Type -> Unit
            is Filter.HasLabel -> out += "label: ${labels[f.labelId]?.name ?: "?"}"
            is Filter.InWorkspace -> out += "from ${workspaces[f.workspaceId] ?: "another workspace"}"
            is Filter.Prop -> {
                val name = defs[f.defId]?.name ?: "property"
                val op = when (f.op) {
                    Op.EQ -> "="
                    Op.NEQ -> "≠"
                    Op.LT -> "<"
                    Op.LTE -> "≤"
                    Op.GT -> ">"
                    Op.GTE -> "≥"
                    Op.IS_SET -> "is set"
                    Op.NOT_SET -> "is not set"
                }
                val value = when {
                    f.dateRel != null -> "today"
                    f.text != null -> f.text
                    f.number != null -> f.number.toString()
                    f.date != null -> dateLabel(f.date)
                    f.bool != null -> f.bool.toString()
                    else -> ""
                }
                out += listOf(name, op, value).filter { it.isNotBlank() }.joinToString(" ")
            }
        }
    }
}
