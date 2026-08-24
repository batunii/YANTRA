package ie.napkin.supertasks.ui.smart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.napkin.supertasks.AppContainer
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

@OptIn(ExperimentalCoroutinesApi::class)
class SmartListViewModel(
    private val container: AppContainer,
    val nodeId: String,
) : ViewModel() {
    private val smartLists = container.smartLists
    private val nodes = container.nodes
    private val properties = container.properties

    val node: StateFlow<NodeEntity?> =
        nodes.observe(nodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val def: StateFlow<SmartListDefEntity?> =
        smartLists.observeDef(nodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
    val chips: StateFlow<Map<String, List<ChipData>>> =
        combine(
            properties.defs(),
            container.db.propertyDao().allValues(),
            container.labels.all(),
            container.labels.allNodeLabels(),
        ) { d, v, labels, nodeLabels ->
            val merged = buildChips(d, v).toMutableMap()
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
                    .map { rows -> rows.associate { it.rootId to it.total } }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val pomoCounts: StateFlow<Map<String, Int>> =
        container.pomodoro.completedCounts()
            .map { list -> list.associate { it.nodeId to it.count } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** e.g. "Open tasks · Priority = High · label: groceries · new tasks land in Inbox" */
    val description: StateFlow<String> =
        combine(def, properties.defs(), container.labels.all()) { d, defs, labels -> describe(d, defs, labels) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun addTask(title: String) {
        viewModelScope.launch {
            val d = def.value ?: smartLists.defById(nodeId) ?: return@launch
            smartLists.addTask(d, title)
        }
    }

    // What the rule editor needs to offer choices: the properties you can filter on, the labels you
    // can match, and the lists a new task could land in. Same three inputs the create sheet takes on
    // the home screen, because it is the same sheet.
    val defs: StateFlow<List<ie.napkin.supertasks.data.db.PropertyDefEntity>> =
        properties.builtInDefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val labels: StateFlow<List<ie.napkin.supertasks.data.db.LabelEntity>> =
        container.labels.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lists: StateFlow<List<NodeEntity>> =
        nodes.allLists()
            .map { all -> all.filter { it.type == ie.napkin.supertasks.data.db.NodeType.LIST } }
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
        val parts = mutableListOf<String>()
        val filter = runCatching { FilterJson.decodeFromString(Filter.serializer(), d.filterJson) }.getOrNull()
        collectParts(filter, defById, labelById, parts)
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
        out: MutableList<String>,
    ) {
        when (f) {
            null -> Unit
            is Filter.All -> f.filters.forEach { collectParts(it, defs, labels, out) }
            is Filter.AnyOf -> out += "${f.filters.size} rules"
            is Filter.Not -> out += "1 exclusion"
            // "open tasks" is the default reading of a task list — only the unusual case is worth
            // the pill's one line.
            is Filter.Done -> if (f.value) out += "completed"
            is Filter.Type -> Unit
            is Filter.HasLabel -> out += "label: ${labels[f.labelId]?.name ?: "?"}"
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
