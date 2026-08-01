package ie.napkin.supertasks.ui.node

import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.data.db.SubtreeTaskCount
import ie.napkin.supertasks.data.ink.StrokeCodec
import ie.napkin.supertasks.ui.components.ChipData
import ie.napkin.supertasks.ui.components.buildChips
import ie.napkin.supertasks.ui.components.buildLabelChips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NodePageViewModel(
    private val container: AppContainer,
    val nodeId: String,
) : ViewModel() {
    private val nodes = container.nodes
    private val properties = container.properties
    private val labels = container.labels
    private val ink = container.ink

    val node: StateFlow<NodeEntity?> =
        nodes.observe(nodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Titles of this page's ancestors, root → parent, for the header breadcrumb. */
    val breadcrumb: StateFlow<List<String>> =
        nodes.observe(nodeId)
            .map { current ->
                if (current == null) emptyList()
                else nodes.ancestors(nodeId).map { it.title?.takeIf { t -> t.isNotBlank() } ?: "Untitled" }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val children: StateFlow<List<NodeEntity>> =
        nodes.children(nodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Only the fixed Priority/Due fields — no more arbitrary user-created schema fields. */
    val defs: StateFlow<List<PropertyDefEntity>> =
        properties.builtInDefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Chips for every direct child, keyed by child node id — properties plus labels. */
    val chips: StateFlow<Map<String, List<ChipData>>> =
        combine(
            properties.defs(),
            properties.valuesUnder(nodeId),
            labels.all(),
            labels.forChildrenOf(nodeId),
        ) { d, v, allLabels, nodeLabels ->
            val merged = buildChips(d, v).toMutableMap()
            buildLabelChips(allLabels, nodeLabels).forEach { (id, chips) ->
                merged[id] = merged[id].orEmpty() + chips
            }
            merged
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Raw values of the page node itself, keyed by def — feeds the always-visible pills. */
    val ownValues: StateFlow<Map<String, PropertyValueEntity>> =
        properties.valuesForNode(nodeId)
            .map { list -> list.associateBy { it.defId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Raw property values of the selected child, feeding the property sheet. */
    fun valuesFor(childId: String) = properties.valuesForNode(childId)

    /** Every label that exists in the workspace — feeds the label picker. */
    val allLabels: StateFlow<List<LabelEntity>> =
        labels.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Labels attached to the page node itself — feeds the always-visible label row. */
    val ownLabels: StateFlow<List<LabelEntity>> =
        labelsFor(nodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Labels attached to any given node, resolved from the join table. */
    fun labelsFor(childId: String): Flow<List<LabelEntity>> =
        combine(labels.forNode(childId), labels.all()) { nodeLabels, allLabels ->
            val byId = allLabels.associateBy { it.id }
            nodeLabels.mapNotNull { byId[it.labelId] }
        }

    val childCounts: StateFlow<Map<String, SubtreeTaskCount>> =
        nodes.childCountsUnder(nodeId)
            .map { list -> list.associateBy { it.rootId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val pomoCounts: StateFlow<Map<String, Int>> =
        container.pomodoro.completedCounts()
            .map { list -> list.associate { it.nodeId to it.count } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Decoded strokes for every ink block on this page, keyed by ink node id. */
    val inkPreviews: StateFlow<Map<String, List<Stroke>>> =
        ink.strokesUnder(nodeId)
            .map { rows ->
                rows.groupBy { it.nodeId }.mapValues { (_, list) ->
                    list.mapNotNull { row -> runCatching { StrokeCodec.decode(row.data) }.getOrNull() }
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ---- block ops ----

    fun addBlock(type: String, title: String? = null, afterId: String? = null, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = nodes.create(nodeId, type, title, afterId)
            onCreated(id)
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { nodes.rename(id, title) }
    }

    fun renamePage(title: String) = rename(nodeId, title)

    fun setDone(id: String, done: Boolean) {
        viewModelScope.launch { nodes.setDone(id, done) }
    }

    fun delete(id: String) {
        viewModelScope.launch { nodes.delete(id) }
    }

    fun moveUp(node: NodeEntity) {
        viewModelScope.launch { nodes.moveUp(node) }
    }

    fun moveDown(node: NodeEntity) {
        viewModelScope.launch { nodes.moveDown(node) }
    }

    fun indent(node: NodeEntity) {
        viewModelScope.launch { nodes.indent(node) }
    }

    fun outdent(node: NodeEntity) {
        viewModelScope.launch { nodes.outdent(node, nodeId) }
    }

    fun convert(node: NodeEntity, type: String) {
        viewModelScope.launch { nodes.setType(node.id, type) }
    }

    // ---- properties ----

    fun setProperty(childId: String, def: PropertyDefEntity, text: String? = null, number: Double? = null, date: Long? = null, bool: Boolean? = null) {
        viewModelScope.launch { properties.setValue(childId, def.id, text, number, date, bool) }
    }

    fun clearProperty(childId: String, defId: String) {
        viewModelScope.launch { properties.clearValue(childId, defId) }
    }

    // ---- labels ----

    fun attachLabel(targetId: String, labelId: String) {
        viewModelScope.launch { labels.attach(targetId, labelId) }
    }

    fun detachLabel(targetId: String, labelId: String) {
        viewModelScope.launch { labels.detach(targetId, labelId) }
    }

    fun createAndAttachLabel(targetId: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val label = labels.getOrCreate(name)
            labels.attach(targetId, label.id)
        }
    }
}
