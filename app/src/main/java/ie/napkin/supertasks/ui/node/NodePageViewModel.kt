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
import kotlinx.coroutines.withContext

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

    /**
     * The page's own blocks. A page shows what was written on it — a task's contents belong to that
     * task's page, reachable through its chevron, and are not repeated here.
     */
    val blocks: StateFlow<List<NodeEntity>> =
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

    /** Sessions per task, for the little badge on a row. Every session, not only the finished ones. */
    val pomoCounts: StateFlow<Map<String, Int>> =
        container.focus.perNode()
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

    /** The full-size picture this device happens to hold for a block, if any. */
    private val originals = ie.napkin.supertasks.data.image.LocalOriginals(container.app)

    fun originalFor(blockId: String) = originals.originalFor(blockId)

    /** Where the workspace's copy of an image block lives, once it has one. */
    suspend fun imageFile(nodeId: String) = nodes.imageFile(nodeId)

    /**
     * Brings a picked image into the workspace.
     *
     * Downscaled and stripped of metadata on the way in — see
     * [ie.napkin.supertasks.data.image.ImageImport]. The original stays where it was and is
     * remembered locally, so this device draws the sharp one and every other device draws the copy
     * that actually travels.
     */
    fun addImage(uri: android.net.Uri, afterId: String? = null, onFailed: () -> Unit = {}) {
        viewModelScope.launch {
            val bytes: ByteArray? = withContext(Dispatchers.Default) {
                ie.napkin.supertasks.data.image.ImageImport.downscale(container.app, uri)
            }
            if (bytes == null) {
                onFailed()
                return@launch
            }
            val after = afterId?.let { nodes.byId(it) }
            val id = nodes.addImage(
                parentId = after?.parentId ?: nodeId,
                bytes = bytes,
                afterId = afterId,
                indent = after?.indent ?: 0,
            )
            originals.remember(id, uri)
        }
    }

    /**
     * A task from a line of typing, with whatever the line said applied.
     *
     * Goes through the shared capture path so the quick-add bar agrees with the widget and the share
     * sheet about what "tomorrow" means.
     */
    fun captureTask(text: String) {
        viewModelScope.launch {
            nodes.captureTask(nodeId, text, container.labels, container.properties)
        }
    }

    // ---- block ops ----

    /**
     * Creates a block. With [afterId] it lands immediately after that block *as its sibling*,
     * whatever depth that is; without one it goes at the end of the page.
     *
     * The parent has to be resolved from [afterId] rather than assumed to be the page: a page
     * renders nested blocks now, and [NodeRepository.create] only honours `afterId` when the two
     * share a parent — so passing the page root while pointing at a nested block silently appended
     * to the bottom of the page instead of inserting where the caret was.
     */
    fun addBlock(type: String, title: String? = null, afterId: String? = null, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            // Inherits the indentation of the block it follows: inserting below an indented line
            // should continue at that level, not jump back to the margin. Appending to the page
            // (no afterId) starts flush left.
            val after = afterId?.let { nodes.byId(it) }
            onCreated(nodes.create(after?.parentId ?: nodeId, type, title, afterId, after?.indent ?: 0))
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { nodes.rename(id, title) }
    }

    fun renamePage(title: String) = rename(nodeId, title)

    fun setDone(id: String, done: Boolean) {
        viewModelScope.launch { nodes.setDone(id, done) }
    }

    fun setInProgress(id: String, inProgress: Boolean) {
        viewModelScope.launch { nodes.setInProgress(id, inProgress) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            // Deleting the line above an indented one leaves it indented under nothing.
            val parent = nodes.byId(id)?.parentId
            nodes.delete(id)
            if (parent != null) nodes.normalizeIndents(parent)
        }
    }

    fun moveUp(node: NodeEntity) {
        viewModelScope.launch { nodes.moveUp(node) }
    }

    fun moveDown(node: NodeEntity) {
        viewModelScope.launch { nodes.moveDown(node) }
    }

    /**
     * Indentation is layout only: it shifts the line on this page and never moves the block into
     * the one above it. Somewhere to *put* things is what a task's own page is for; how a line sits
     * is a separate question, and conflating them is what made indenting look like deletion.
     */
    fun indent(node: NodeEntity) {
        viewModelScope.launch { nodes.setIndent(node, node.indent + 1) }
    }

    fun outdent(node: NodeEntity) {
        viewModelScope.launch { nodes.setIndent(node, node.indent - 1) }
    }

    /** Commit of a drag-to-reorder: put [node] at [toIndex] among its siblings. */
    fun moveToIndex(node: NodeEntity, toIndex: Int) {
        viewModelScope.launch { nodes.moveToIndex(node, toIndex) }
    }

    fun convert(node: NodeEntity, type: String) {
        viewModelScope.launch { nodes.setType(node.id, type) }
    }

    /**
     * Splits a block at the caret: [before] stays, [after] becomes a new block of the same kind
     * directly below, which the caller then focuses. This is what Enter does — a block editor
     * makes blocks with Enter, not with a toolbar.
     *
     * A heading splits into a paragraph, since the thing you type after a heading is body text.
     */
    fun splitBlock(node: NodeEntity, before: String, after: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            nodes.rename(node.id, before)
            // A heading is a one-off, so what follows it is body text. A list item continues the
            // list — that is the whole point of pressing Enter in one.
            val type = if (node.type == NodeType.HEADING) NodeType.PARAGRAPH else node.type
            // Same level as the block being split — Enter on an indented line keeps you there.
            onCreated(nodes.create(node.parentId ?: nodeId, type, after, node.id, node.indent))
        }
    }

    /**
     * Drops the run of blank blocks at the *end* of the page. Called when leaving, never while
     * editing: an empty block is only litter once you have walked away from it, and deleting on
     * blur would take the block you just tapped away from to reach the toolbar.
     *
     * Trailing-only and blank-only by design. A blank block in the middle of a page is a spacer
     * someone made on purpose, and a task with children or a completion is not blank whatever
     * its title says.
     */
    fun pruneTrailingBlanks(childCounts: Map<String, Int>) {
        // appScope, not viewModelScope: this is called from onDispose as the screen goes away, and
        // the ViewModel's scope is cancelled at the same moment — which cancelled the loop after
        // the first delete and left the rest of the blanks on the page.
        container.appScope.launch {
            val blocks = this@NodePageViewModel.blocks.value
            val disposable = blocks.reversed().takeWhile { b ->
                b.title.isNullOrBlank() &&
                    b.type in NodeType.TEXTUAL &&
                    !b.done &&
                    (childCounts[b.id] ?: 0) == 0
            }
            disposable.forEach { nodes.delete(it.id) }
            if (disposable.isNotEmpty()) nodes.normalizeIndents(nodeId)
        }
    }

    // ---- properties ----

    fun setProperty(childId: String, def: PropertyDefEntity, text: String? = null, number: Double? = null, date: Long? = null, bool: Boolean? = null) {
        viewModelScope.launch { properties.setValue(childId, def.id, text, number, date, bool) }
    }

    fun setDue(childId: String, dateMillis: Long, hasTime: Boolean, reminderOffsetMin: Int?) {
        viewModelScope.launch { properties.setDue(childId, dateMillis, hasTime, reminderOffsetMin) }
    }

    fun setDeadline(childId: String, dateMillis: Long) {
        viewModelScope.launch { properties.setDeadline(childId, dateMillis) }
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

    /** [color] null means "let the palette choose from the name" — never means "no colour". */
    fun createAndAttachLabel(targetId: String, name: String, color: Long? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val label = labels.getOrCreate(name, color)
            labels.attach(targetId, label.id)
        }
    }

    /** Recolour a label wherever it appears. Null clears it back to the neutral chip. */
    fun setLabelColor(labelId: String, color: Long?) {
        viewModelScope.launch { labels.setColor(labelId, color) }
    }
}
