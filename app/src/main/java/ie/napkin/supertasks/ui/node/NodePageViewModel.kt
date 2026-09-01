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
import ie.napkin.supertasks.data.format.Links
import ie.napkin.supertasks.data.ink.StrokeCodec
import ie.napkin.supertasks.data.people.Person
import ie.napkin.supertasks.ui.components.ChipData
import ie.napkin.supertasks.ui.components.buildChips
import ie.napkin.supertasks.ui.components.buildLabelChips
import ie.napkin.supertasks.ui.components.strangerAgainst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    // ---- people ----

    private val peopleRefreshing = MutableStateFlow(false)
    private val peopleProblem = MutableStateFlow<String?>(null)

    /**
     * Who a task on this page can be assigned to.
     *
     * Keyed off the page's own workspace, because that is the repository whose collaborators are
     * the answer. A task in Personal has a roster of one; a task in a shared repo has whoever can
     * push to it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val people: StateFlow<List<Person>> =
        node.flatMapLatest { n ->
            if (n == null) flowOf(emptyList()) else container.people.known(n.workspaceId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Just the logins a typed `@` may name here — the same closed list the sheet offers.
     *
     * Anyone already on a task but off the roster is deliberately absent: they are shown in the
     * sheet because they are *there*, not because they are a choice, and capture has no equivalent
     * of "shown but not offered".
     */
    val assignable: StateFlow<List<String>> =
        people.map { who -> who.filter { it.onRepo != false }.map { it.login } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Link names on a captured line, resolved to ids — see `NodeRepository.linkIdsFor`. */
    suspend fun linkIdsFor(text: String) = nodes.linkIdsFor(text)

    val peopleState: StateFlow<Pair<Boolean, String?>> =
        combine(peopleRefreshing, peopleProblem) { busy, note -> busy to note }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false to null)

    /**
     * Whether asking GitHub for this workspace's collaborators could work at all.
     *
     * A flow, not a function, and that is a correctness fix rather than a style one. It was
     * `node.value` read straight out of a composable — not a snapshot read, so composition had no
     * reason to run again when the node finally arrived. The page composes once with no node, the
     * answer is no, and the Collaborators button is simply absent: the feature looks unbuilt rather
     * than unavailable, and nothing on screen ever says otherwise. It happened to recover most of
     * the time because something else in the same scope changed a moment later, which is the worst
     * kind of working.
     */
    val canRefreshPeople: StateFlow<Boolean> =
        node.map { it != null && container.people.canRefresh(it.workspaceId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun refreshPeople() {
        val ws = node.value?.workspaceId ?: return
        if (peopleRefreshing.value) return
        viewModelScope.launch {
            peopleRefreshing.value = true
            peopleProblem.value = container.people.refresh(ws)
            peopleRefreshing.value = false
        }
    }

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

    // ---- links ----

    /**
     * Everything this page's text points at, in the order it is first mentioned.
     *
     * Resolved live rather than trusting the label stored inside each link, so renaming a task
     * updates every reference to it and nothing has to go and rewrite other people's files. An id
     * that resolves to nothing is simply absent, and the renderer falls back to what the file says
     * — see [Links].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val linkedNodes: StateFlow<List<NodeEntity>> =
        combine(node, blocks) { page, children ->
            (listOfNotNull(page?.title) + children.mapNotNull { it.title })
                .flatMap { Links.targets(it) }
                .distinct()
        }
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(emptyList())
                // Ordered by first mention, not by whatever order the query returned them in: the
                // header row reads down the page, and a set of chips that reshuffles when an
                // unrelated block changes is a set of chips nobody can aim at.
                else nodes.byIds(ids).map { rows -> ids.mapNotNull { id -> rows.firstOrNull { it.id == id } } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The same thing in the shape the renderer asks its question in: id → current title. */
    val linkTitles: StateFlow<Map<String, String>> =
        linkedNodes
            .map { rows -> rows.associate { it.id to (it.title?.takeIf { t -> t.isNotBlank() } ?: "Untitled") } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Somewhere a `[[` could point. A page never offers itself. */
    suspend fun linkTargets(query: String): List<NodeEntity> =
        nodes.searchLinkTargets(query).filterNot { it.id == nodeId }

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
            container.people.rosters(),
        ) { d, v, allLabels, nodeLabels, rosters ->
            val merged = buildChips(d, v, strangerAgainst(rosters)).toMutableMap()
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

    /**
     * Every list in the workspace, by name — so `~` in the quick-add bar can name one of them and
     * be offered the ones that match while it is still being typed.
     */
    val listNames: StateFlow<List<String>> =
        nodes.allLists()
            .map { all -> all.filter { it.type == NodeType.LIST }.mapNotNull { it.title } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            nodes.captureTask(nodeId, text, container.labels, container.properties, container.people)
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

    /**
     * Changes a block's kind, reporting the id it ends up with.
     *
     * Converting to a task mints a real id for the line (a derived, positional one is not a name —
     * see WorkspaceWriter.convertBlock), so the row the caret was in is about to be a *different*
     * row. Anything following the caret has to be told where it went.
     */
    fun convert(node: NodeEntity, type: String, onConverted: (String) -> Unit = {}) {
        viewModelScope.launch { onConverted(nodes.setType(node.id, type)) }
    }

    /**
     * What typing a markdown marker does: the line loses the marker and becomes the kind it named.
     *
     * One coroutine, in order, deliberately. The text and the type used to be two independent
     * launches, and the conversion reads the line's text back off the page — so whether the marker
     * survived into the converted block depended on which write happened to land first.
     */
    fun becomeBlock(node: NodeEntity, type: String, text: String, onConverted: (String) -> Unit = {}) {
        viewModelScope.launch {
            nodes.rename(node.id, text)
            onConverted(nodes.setType(node.id, type))
        }
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
            fun spent(b: NodeEntity) =
                b.title.isNullOrBlank() && !b.done && (childCounts[b.id] ?: 0) == 0
            val trailingFrom = blocks.indexOfLast { !(it.type in NodeType.TEXTUAL && spent(it)) } + 1
            // An untouched task is litter wherever it sits, not only at the end.
            //
            // A blank *prose* line in the middle of a page is a spacer somebody made on purpose, so
            // the trailing-only rule is right for it. A blank task is not: nothing about a page ever
            // wants an unnamed checkbox in the middle of it, and one made by tapping Task and then
            // walking away used to survive — with nothing to click, nothing to read, and a checkbox
            // in the margin of a document. It also stopped being reachable by the trailing rule the
            // moment anything was typed below it.
            val disposable = blocks.withIndex().filter { (i, b) ->
                i >= trailingFrom || (b.type == NodeType.TASK && spent(b))
            }
            // Bottom-up. A block the format does not name is identified by its line number, so
            // deleting one renumbers everything below it — and a top-down pass would have every id
            // after the first deletion point at the wrong line.
            disposable.sortedByDescending { it.index }.forEach { nodes.delete(it.value.id) }
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
