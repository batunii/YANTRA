package ie.napkin.supertasks.ui.node

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import ie.napkin.supertasks.data.db.BlockRowEntity
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.components.ChipData
import ie.napkin.supertasks.ui.components.ConfirmDialog
import ie.napkin.supertasks.ui.components.MarkdownEmphasis
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.horizontalFadingEdge
import ie.napkin.supertasks.ui.components.NeutralChip
import ie.napkin.supertasks.ui.components.PomodoroCount
import ie.napkin.supertasks.ui.components.PropertyChip
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.TaskCheck
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.ink.InkPreview
import ie.napkin.supertasks.ui.ink.inkContentHeight
import ie.napkin.supertasks.ui.theme.MonoBreadcrumb
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodePageScreen(nav: NavHostController, nodeId: String) {
    val vm: NodePageViewModel = viewModel(key = "node-$nodeId") { NodePageViewModel(container(), nodeId) }
    val node by vm.node.collectAsStateWithLifecycle()
    val crumbs by vm.breadcrumb.collectAsStateWithLifecycle()
    // Every block on the page, nested ones included, in reading order. One list, one meaning:
    // everything on this screen — the tally, the ordinals, the drag geometry, the write line —
    // reasons about what is *rendered*. Keeping a second "direct children only" list beside it is
    // how nested blocks ended up with no strokes, no chips and no counts.
    val rows by vm.blocks.collectAsStateWithLifecycle()
    val blocks = remember(rows) { rows.map { it.node } }
    val depthOf = remember(rows) { rows.associate { it.node.id to it.depth } }
    val chips by vm.chips.collectAsStateWithLifecycle()
    val defs by vm.defs.collectAsStateWithLifecycle()
    val ownValues by vm.ownValues.collectAsStateWithLifecycle()
    val allLabels by vm.allLabels.collectAsStateWithLifecycle()
    val ownLabels by vm.ownLabels.collectAsStateWithLifecycle()
    val childCounts by vm.childCounts.collectAsStateWithLifecycle()
    val pomoCounts by vm.pomoCounts.collectAsStateWithLifecycle()
    val inkPreviews by vm.inkPreviews.collectAsStateWithLifecycle()

    var propertySheetFor by remember { mutableStateOf<String?>(null) }
    var deletingPage by remember { mutableStateOf(false) }
    // Which block owns the handles. A column of ⋮ down the right edge is noise on every row to
    // serve the one row you actually want to act on, so a block earns its handle by being
    // touched: focused (text blocks) or long-pressed (anything).
    var activeBlockId by remember { mutableStateOf<String?>(null) }
    // The block the caret was last in, which is not the same as the active block: long-pressing a
    // row makes it active without putting a caret in it. Only a caret means "insert around here".
    //
    // Deliberately sticky — set on focus, never cleared on blur. Tapping a button in the add bar
    // takes focus away from the text field, so a value cleared on blur would already be null by
    // the time the button's handler asks where the caret was, and every insert would fall back to
    // the end of the page: exactly the bug this is meant to fix.
    var lastCaretBlockId by remember { mutableStateOf<String?>(null) }
    // Set when an edit moves the caret to another block (Enter split, Backspace merge, tapping
    // the write line). The row whose id matches claims focus once and clears it.
    var caretTarget by remember { mutableStateOf<String?>(null) }
    val drag = remember { BlockDrag() }
    // A freshly-added task auto-focuses for typing (since tapping a row now opens it).
    var justCreatedId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.addBlock(NodeType.IMAGE, uri.toString(), afterId = lastCaretBlockId)
        }
    }

    val current = node
    val isTask = current?.type == NodeType.TASK
    val y = Yantra.colors

    // Collapse the header once the user scrolls — the page feels immersive, like a note.
    //
    // The header's size MUST NOT be derived, even indirectly, from where the list ended up.
    // Every such version of this oscillates: folding the header away hands ~150dp back to the
    // list, the list then fits and clamps its offset to the top, whatever read that position
    // expands the header again, the content overflows once more, and the next scroll — a finger,
    // or bring-into-view on a focused field — collapses it. Several times a second. A previous
    // attempt at this fix kept one such read (reset when the list is pinned at the top) and kept
    // the loop with it.
    //
    // So the only inputs here are the user's own drag deltas:
    //  - accumulate from what the list actually consumed, so a page with nothing to scroll never
    //    collapses;
    //  - de-accumulate with the unconsumed downward remainder too, so a pull-down still expands
    //    the header once the list has hit the top and has nothing left to give (without this the
    //    header can latch collapsed with no way back);
    //  - latch across a hysteresis band, so a finger resting near the threshold doesn't flutter.
    val listState = rememberLazyListState()
    val collapsePx = with(LocalDensity.current) { 56.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    var collapsed by remember { mutableStateOf(false) }
    val headerScroll = remember(collapsePx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Fling and bring-into-view arrive as SideEffect; only a real gesture counts.
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val delta = consumed.y + available.y.coerceAtLeast(0f)
                dragged = (dragged - delta).coerceIn(0f, collapsePx * 1.5f)
                collapsed = when {
                    dragged >= collapsePx -> true
                    dragged <= collapsePx * 0.35f -> false
                    else -> collapsed
                }
                return Offset.Zero
            }
        }
    }

    // Direct-child task tallies for the list meta line.
    // Counts what the page shows, which is now the whole subtree — the same thing the Home row for
    // this list already reports, so the two no longer disagree.
    val taskChildren = blocks.count { it.type == NodeType.TASK }
    val doneChildren = blocks.count { it.type == NodeType.TASK && it.done }

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page),
    ) {
        PageBand(
            node = current,
            isTask = isTask,
            collapsed = collapsed,
            crumbs = crumbs,
            metaTotal = taskChildren,
            metaDone = doneChildren,
            onBack = { nav.popBackStack() },
            onFocus = { nav.navigate(Routes.focus(nodeId)) },
            onDelete = { deletingPage = true },
            onRename = vm::renamePage,
            onToggleDone = { done -> vm.setDone(nodeId, done) },
            properties = {
                if (isTask) {
                    PropertyRow(
                        defs = defs,
                        values = ownValues,
                        allLabels = allLabels,
                        attachedLabels = ownLabels,
                        onSet = { def, t, n, d, b -> vm.setProperty(nodeId, def, t, n, d, b) },
                        onSetDue = { d, hasTime, remMin -> vm.setDue(nodeId, d, hasTime, remMin) },
                        onSetDeadline = { d -> vm.setDeadline(nodeId, d) },
                        onClear = { defId -> vm.clearProperty(nodeId, defId) },
                        onAttachLabel = { label -> vm.attachLabel(nodeId, label.id) },
                        onDetachLabel = { label -> vm.detachLabel(nodeId, label.id) },
                        onCreateAndAttachLabel = { name -> vm.createAndAttachLabel(nodeId, name) },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            },
        )

        // Where a dragged block lands. Sideways past a threshold is indent/outdent (the same two
        // operations the menu used to offer); otherwise it drops next to whichever block it is
        // nearest, and only the moved block's rank changes.
        // Where the lifted block would land right now, and how tall it is. Computed once here and
        // read by every row, so the rows between origin and target can step aside and show the gap
        // the block is going to fall into. Without that the drag is just a floating rectangle and
        // you have to guess.
        val dragRows = listState.layoutInfo.visibleItemsInfo
        val liftedId = drag.id
        // Clamped: the gap should read as "something this shape lands here", but a full-page sketch
        // would otherwise shove its neighbours clean off the screen.
        val liftedHeight = (dragRows.firstOrNull { it.key == liftedId }?.size?.toFloat() ?: 0f)
            .coerceAtMost(listState.layoutInfo.viewportSize.height * 0.35f)
        val liftedFrom = blocks.indexOfFirst { it.id == liftedId }
        // A lifted block carries its nested blocks: reordering the parent moves the whole subtree
        // in the data, so letting the children sit still while the parent floats away would show
        // something that is not what happens.
        val liftedSubtree = remember(rows, liftedId) {
            if (liftedId == null) emptySet() else buildSet {
                val start = rows.indexOfFirst { it.node.id == liftedId }
                if (start >= 0) {
                    val base = rows[start].depth
                    add(liftedId)
                    for (i in start + 1 until rows.size) {
                        if (rows[i].depth <= base) break
                        add(rows[i].node.id)
                    }
                }
            }
        }
        // The row under the finger, preferring the one it is actually inside.
        fun rowUnderFinger(): androidx.compose.foundation.lazy.LazyListItemInfo? {
            val id = drag.id ?: return null
            // Siblings only. A block reorders within its own level; changing its nesting is what
            // Indent and Outdent are for, and letting a drag do it as well would make every
            // slightly-diagonal move a guess about which the user meant.
            val parent = blocks.firstOrNull { it.id == id }?.parentId
            val ids = blocks.filter { it.parentId == parent }.mapTo(mutableSetOf()) { it.id }
            val rows = dragRows.filter { it.key in ids && it.key != id }
            val fy = drag.pointerY
            return rows.firstOrNull { fy >= it.offset && fy < it.offset + it.size }
                ?: rows.minByOrNull { kotlin.math.abs((it.offset + it.size / 2f) - fy) }
        }
        val liftedTo = if (liftedId == null) -1
            else blocks.indexOfFirst { it.id == rowUnderFinger()?.key as? String }

        // Vertical only. Sideways-drag used to indent, and indenting moves a block *into* its
        // neighbour — on a page that renders only direct children, that made the block disappear.
        // A gesture whose failure mode is "your block vanished" is the wrong home for it, so
        // indent and outdent are buttons in the bar now, where they are deliberate and named.
        fun commitDrag() {
            val id = drag.id
            val node = blocks.firstOrNull { it.id == id }
            if (id != null && node != null) {
                val over = rowUnderFinger()
                // Indexed among siblings, because that is what moveToIndex takes.
                val others = blocks.filter { it.parentId == node.parentId && it.id != id }
                val overId = over?.key as? String
                val pos = others.indexOfFirst { it.id == overId }
                if (pos >= 0) {
                    // Direction from the indices rather than the sign of the drag, so a long slow
                    // drag that ends where it started is a no-op instead of a move by one.
                    val siblings = blocks.filter { it.parentId == node.parentId }
                    val from = siblings.indexOfFirst { it.id == id }
                    val to = siblings.indexOfFirst { it.id == overId }
                    vm.moveToIndex(node, if (to > from) pos + 1 else pos)
                }
            }
            drag.stop()
        }

        // A numbered item's number is its position in the *run* of numbered items it belongs to, so
        // a list that is interrupted by a paragraph starts counting again after it — which is what
        // you see in any editor, and it means nothing has to be stored.
        val ordinals = remember(blocks) {
            buildMap {
                // Counted per parent: a nested list is its own list, and it restarts at 1 rather
                // than continuing whatever numbering surrounded it.
                val running = mutableMapOf<String?, Int>()
                blocks.forEach { c ->
                    if (c.type == NodeType.NUMBERED) {
                        val n = (running[c.parentId] ?: 0) + 1
                        running[c.parentId] = n
                        put(c.id, n)
                    } else {
                        running[c.parentId] = 0
                    }
                }
            }
        }


        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(headerScroll),
            // The start inset moves into each block's drag gutter, so nothing shifts sideways.
            contentPadding = PaddingValues(start = 2.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        ) {
            itemsIndexed(blocks, key = { _, it -> it.id }) { index, child ->
                // Tasks, sketches and images are carried; prose is not. A handle on every paragraph
                // was mostly noise, but a sketch or a picture is a distinct object you place, and
                // it is the block you are most likely to want somewhere else. The gutter stays on
                // every block regardless, so text still lines up down the page — it just has no
                // grip in it.
                //
                // Ink and image keep their own long-press for selecting (that is how the Delete
                // chip finds them), so for those two the gutter is specifically the drag handle
                // rather than "anywhere that isn't text".
                val draggable = child.type == NodeType.TASK ||
                    child.type == NodeType.INK ||
                    child.type == NodeType.IMAGE
                val lifted = child.id in liftedSubtree
                // Only the block itself gets the raised surface; its children ride along beneath.
                val liftedRoot = drag.id == child.id
                // Rows between the block's origin and where it now hovers step aside by exactly
                // the block's own height, so the gap that opens is the shape of what is landing.
                val stepAside = when {
                    liftedId == null || lifted || liftedFrom < 0 || liftedTo < 0 -> 0f
                    liftedTo > liftedFrom && index in (liftedFrom + 1)..liftedTo -> -liftedHeight
                    liftedTo < liftedFrom && index in liftedTo..(liftedFrom - 1) -> liftedHeight
                    else -> 0f
                }
                val slide by animateFloatAsState(
                    targetValue = stepAside,
                    animationSpec = YantraMotion.spatial(),
                    label = "blockSlide",
                )
                val liftScale by animateFloatAsState(
                    targetValue = if (lifted) 1.02f else 1f,
                    animationSpec = YantraMotion.fastSpatial(),
                    label = "blockLift",
                )
                Box(
                    Modifier
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            if (lifted) {
                                translationY = drag.dy
                                if (liftedRoot) {
                                    scaleX = liftScale
                                    scaleY = liftScale
                                }
                            } else {
                                translationY = slide
                            }
                        }
                        .then(
                            if (liftedRoot) {
                                Modifier
                                    .shadow(12.dp, RoundedCornerShape(14.dp))
                                    .background(y.tileWarm, RoundedCornerShape(14.dp))
                                    .border(1.dp, y.tileBorder, RoundedCornerShape(14.dp))
                            } else Modifier
                        )
                        // Long-press anywhere in the block's own space to pick it up. It cannot
                        // be the words: a text field claims long-press for its own selection, and
                        // taking that away would cost you Cut/Copy/Select-all. So every block gets
                        // a gutter down its left instead — empty space belonging to this box, wide
                        // enough to hit, with a grip drawn in it once the block is selected.
                        .then(
                            if (!draggable) Modifier else Modifier.pointerInput(child.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { local ->
                                        // Long-press selects as well as lifts, so releasing
                                        // without moving leaves the block selected — which is how
                                        // ink and image reach the Delete chip now that the
                                        // long-press belongs to the drag instead of to them.
                                        activeBlockId = child.id
                                        val top = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == child.id }?.offset ?: 0
                                        drag.start(child.id, top + local.y)
                                    },
                                    onDrag = { _, amount -> drag.move(amount.y) },
                                    onDragEnd = { commitDrag() },
                                    onDragCancel = { drag.stop() },
                                )
                            }
                        ),
                ) {
                // Visible on the block you are working on, and on every block once a drag starts —
                // mid-drag you want to see where the other handles are. Faded rather than
                // switched, so it never pops.
                val gripAlpha by animateFloatAsState(
                    targetValue = when {
                        !draggable -> 0f
                        liftedRoot -> 1f
                        child.id == activeBlockId -> 0.75f
                        drag.id != null -> 0.35f
                        else -> 0f
                    },
                    animationSpec = YantraMotion.effects(),
                    label = "gripAlpha",
                )
                if (gripAlpha > 0.01f) {
                    Icon(
                        Icons.Default.DragIndicator,
                        contentDescription = "Drag to move",
                        tint = if (liftedRoot) y.accent else y.textDim,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 4.dp)
                            .size(18.dp)
                            .alpha(gripAlpha),
                    )
                }
                Box(Modifier.padding(start = BLOCK_GUTTER + NEST_STEP * (depthOf[child.id] ?: 0))) {
                BlockRow(
                    child = child,
                    active = child.id == activeBlockId,
                    onActivate = { activeBlockId = child.id },
                    onFocusChange = { focused -> if (focused) lastCaretBlockId = child.id },
                    claimCaret = child.id == caretTarget,
                    onCaretClaimed = { if (caretTarget == child.id) caretTarget = null },
                    onSplit = { before, after ->
                        vm.splitBlock(child, before, after) { id -> caretTarget = id }
                    },
                    onMergeBack = {
                        val i = blocks.indexOfFirst { it.id == child.id }
                        val prev = blocks.getOrNull(i - 1)?.takeIf { it.type in NodeType.TEXTUAL }
                        // Only if nothing is nested under it. delete() removes the whole subtree,
                        // so backspacing a blank block that still had children would have taken
                        // them with it — content you cannot even see from the empty line you are
                        // deleting. Backspace must never be able to do that.
                        val childless = (childCounts[child.id]?.total ?: 0) == 0
                        if (prev != null && childless) {
                            caretTarget = prev.id
                            vm.delete(child.id)
                        }
                    },
                    chips = chips[child.id].orEmpty(),
                    childCount = childCounts[child.id]?.total ?: 0,
                    ordinal = ordinals[child.id] ?: 0,
                    pomoCount = pomoCounts[child.id] ?: 0,
                    inkStrokes = inkPreviews[child.id].orEmpty(),
                    autoFocus = child.type == NodeType.TASK && child.id == justCreatedId,
                    onAutoFocusConsumed = { if (justCreatedId == child.id) justCreatedId = null },
                    vm = vm,
                    onOpen = {
                        when (child.type) {
                            NodeType.INK -> nav.navigate(Routes.ink(child.id))
                            else -> nav.navigate(Routes.node(child.id))
                        }
                    },
                )
                }
                }
            }
            // One write line instead of a persisted run of empty blocks — but only when the page
            // does not already end in a blank one. The line exists to guarantee somewhere to
            // start typing; when the last block is itself blank it already *is* that place, and
            // showing both put two identical "Write something…" rows under the caret.
            // Judged on the visually last block, whatever its depth: the line exists so there is
            // always somewhere to start typing, and a blank already sitting at the bottom of the
            // page is that place — nested or not, two blank rows in a row is the thing to avoid.
            val endsBlank = blocks.lastOrNull()?.let {
                it.title.isNullOrBlank() && it.type in NodeType.TEXTUAL
            } ?: false
            if (!endsBlank) {
                item(key = "write-line") {
                    WriteLine(onClick = { vm.addBlock(NodeType.PARAGRAPH, "") { id -> caretTarget = id } })
                }
            }
            item(key = "bottom-spacer") { Spacer(Modifier.height(12.dp)) }
        }

        // The toolbar is a *type selector* for the block you are in, not an add button. Tapping
        // Task / Note / Heading turns the current block into that type; it never appends. Adding
        // is Enter (split) and the write line at the end of the page, which is how a block editor
        // is meant to work — an "add" bar that appends to the page end while you are typing
        // halfway up it just produces blocks you never see.
        //
        // Ink and Image are the exception: they carry content a text block has no way to hold, so
        // they insert below the caret rather than converting.
        val textTypes = NodeType.TEXTUAL
        val caretBlock = blocks.firstOrNull { it.id == lastCaretBlockId }
        fun setType(type: String) {
            val caret = caretBlock
            when {
                caret == null -> vm.addBlock(type, "") { id -> caretTarget = id }
                caret.type == type -> caretTarget = caret.id
                else -> {
                    vm.convert(caret, type)
                    caretTarget = caret.id
                }
            }
        }
        fun insertBelow(type: String, payload: String? = null, onCreated: (String) -> Unit = {}) {
            vm.addBlock(type, payload, afterId = lastCaretBlockId, onCreated = onCreated)
        }

        // Actions act on the block you last touched — the caret, or a long-pressed ink/image.
        val actOn = blocks.firstOrNull { it.id == activeBlockId }
        BlockTypeBar(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding(),
            currentType = caretBlock?.type?.takeIf { it in textTypes },
            onTask = { setType(NodeType.TASK) },
            onText = { setType(NodeType.PARAGRAPH) },
            onHeading = { setType(NodeType.HEADING) },
            onBullet = { setType(NodeType.BULLET) },
            onNumbered = { setType(NodeType.NUMBERED) },
            onInk = { insertBelow(NodeType.INK) { id -> nav.navigate(Routes.ink(id)) } },
            onImage = { imagePicker.launch(arrayOf("image/*")) },
            actOnTask = actOn?.type == NodeType.TASK,
            // Tab and shift-tab, as buttons. A block can only move under the one above it, so
            // Indent is offered only when there is something above it to go under.
            onIndent = actOn
                // Indentable only when it has a sibling above to go under.
                ?.takeIf { block ->
                    blocks.filter { it.parentId == block.parentId }.indexOfFirst { it.id == block.id } > 0
                }
                ?.let { block -> { vm.indent(block) } },
            onOutdent = actOn?.let { block -> { vm.outdent(block) } },
            onProperties = actOn?.let { block -> { propertySheetFor = block.id } },
            onFocusTask = actOn?.takeIf { it.type == NodeType.TASK }
                ?.let { block -> { nav.navigate(Routes.focus(block.id)) } },
            onDelete = actOn?.let { block ->
                {
                    if (activeBlockId == block.id) activeBlockId = null
                    if (lastCaretBlockId == block.id) lastCaretBlockId = null
                    vm.delete(block.id)
                }
            },
        )
    }

    // Leaving the page is the moment trailing blanks become litter rather than workspace.
    DisposableEffect(Unit) {
        onDispose { vm.pruneTrailingBlanks(childCounts.mapValues { it.value.total }) }
    }

    propertySheetFor?.let { targetId ->
        PropertySheet(
            vm = vm,
            targetId = targetId,
            onDismiss = { propertySheetFor = null },
        )
    }

    if (deletingPage && current != null) {
        ConfirmDialog(
            title = "Delete \"${current.title.orEmpty()}\"?",
            body = "This page and everything inside it will be deleted.",
            onDismiss = { deletingPage = false },
            onConfirm = {
                deletingPage = false
                vm.delete(nodeId)
                nav.popBackStack()
            },
        )
    }
}

@Composable
private fun PageBand(
    node: NodeEntity?,
    isTask: Boolean,
    collapsed: Boolean,
    crumbs: List<String>,
    metaTotal: Int,
    metaDone: Int,
    onBack: () -> Unit,
    onFocus: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onToggleDone: (Boolean) -> Unit,
    properties: @Composable () -> Unit,
) {
    val y = Yantra.colors
    val crumbCurrent = y.textSecondary
    var menu by remember { mutableStateOf(false) }
    var title by remember(node?.id) { mutableStateOf(node?.title.orEmpty()) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(y.band, RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = if (collapsed) 10.dp else 22.dp),
    ) {
        // top row: back · (breadcrumb / collapsed title) · actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavCircle(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                onClick = onBack,
                iconSize = 20.dp,
            )
            if (collapsed) {
                Text(
                    title.ifBlank { "Untitled" },
                    fontFamily = ie.napkin.supertasks.ui.theme.YantraDisplay,
                    fontSize = 16.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.2).sp,
                    color = y.textPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                val segments = remember(crumbs) {
                    val all = listOf("Workspace") + crumbs
                    if (all.size <= 3) all else listOf("…") + all.takeLast(2)
                }
                // Body face, not tracked mono caps. The trail is a place you can read at a
                // glance; the mono voice stays reserved for instruments (the timer, the ink
                // watermark), where a machine-set look actually means something.
                val trail = remember(segments) {
                    buildAnnotatedString {
                        segments.forEachIndexed { i, seg ->
                            if (i > 0) {
                                withStyle(SpanStyle(fontWeight = FontWeight.W400)) { append("  /  ") }
                            }
                            if (i == segments.lastIndex) {
                                withStyle(SpanStyle(color = crumbCurrent, fontWeight = FontWeight.W600)) { append(seg) }
                            } else {
                                append(seg)
                            }
                        }
                    }
                }
                Text(
                    trail,
                    fontFamily = ie.napkin.supertasks.ui.theme.YantraText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W500,
                    color = y.textMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isTask) {
                NavCircle(
                    Icons.Default.Timer,
                    contentDescription = "Focus on this task",
                    onClick = onFocus,
                    accent = true,
                    iconSize = 18.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Box {
                NavCircle(
                    Icons.Default.MoreVert,
                    contentDescription = "Page options",
                    onClick = { menu = true },
                    iconSize = 18.dp,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }

        // The big title, checkbox, meta and properties fold away as you scroll.
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Column {
                Row(
                    Modifier.padding(top = 22.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (isTask && node != null) {
                        TaskCheck(
                            done = node.done,
                            onToggle = { onToggleDone(!node.done) },
                            size = 24.dp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it; onRename(it) },
                        textStyle = MaterialTheme.typography.headlineMedium.copy(color = y.textPrimary),
                        cursorBrush = SolidColor(y.accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box {
                                if (title.isEmpty()) {
                                    Text("Untitled", style = MaterialTheme.typography.headlineMedium, color = y.textMuted.copy(alpha = 0.6f))
                                }
                                inner()
                            }
                        },
                    )
                }

                if (!isTask && metaTotal > 0) {
                    Text(
                        "$metaDone of $metaTotal done",
                        style = MaterialTheme.typography.bodySmall,
                        color = y.textMuted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                properties()
            }
        }
    }
}

@Composable
private fun BlockRow(
    child: NodeEntity,
    active: Boolean,
    onActivate: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    claimCaret: Boolean,
    onCaretClaimed: () -> Unit,
    onSplit: (before: String, after: String) -> Unit,
    onMergeBack: () -> Unit,
    chips: List<ChipData>,
    childCount: Int,
    ordinal: Int,
    pomoCount: Int,
    inkStrokes: List<androidx.ink.strokes.Stroke>,
    autoFocus: Boolean,
    onAutoFocusConsumed: () -> Unit,
    vm: NodePageViewModel,
    onOpen: () -> Unit,
) {
    when (child.type) {
        // Task, note and heading all go through the SAME composable so that converting between
        // them cannot dispose the text field the caret is sitting in.
        NodeType.INK -> InkBlockRow(child, active, onActivate, inkStrokes, vm, onOpen)
        NodeType.IMAGE -> ImageBlockRow(child, active, onActivate, vm)
        else -> TextualBlockRow(
            child, active, onActivate, onFocusChange, claimCaret, onCaretClaimed, onSplit,
            onMergeBack, chips, childCount, ordinal, pomoCount, autoFocus, onAutoFocusConsumed, vm,
            onOpen,
        )
    }
}

/**
 * A block being carried. Only one at a time, so the screen holds one of these and every row asks
 * whether it is the one that is lifted.
 */
/**
 * Width of the grab strip on the left of every block. Sized for a thumb rather than for the glyph
 * inside it — the icon is only the label; the whole strip, full row height, is the target.
 */
private val BLOCK_GUTTER = 30.dp

/** How far each level of nesting steps in. */
private val NEST_STEP = 20.dp

@androidx.compose.runtime.Stable
private class BlockDrag {
    var id by mutableStateOf<String?>(null)
        private set
    var dy by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    /**
     * Where the finger first went down, in viewport coordinates. The drop target comes from the
     * *finger*, not from the middle of the block being carried: a full-page sketch is thousands of
     * dp tall, so its centre sits far off screen and a centre-based hit test aimed at rows nobody
     * was pointing at.
     */
    var startY by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set

    val pointerY: Float get() = startY + dy

    fun start(blockId: String, fingerY: Float) { id = blockId; dy = 0f; startY = fingerY }
    fun move(y: Float) { dy += y }
    fun stop() { id = null; dy = 0f; startY = 0f }
}

/**
 * Enter and Backspace, the two keys a block editor is actually built on.
 *
 * A soft keyboard does not deliver Enter as a key event to a multi-line field — it inserts a
 * newline into the text — so Enter is detected by watching the value for one and splitting there.
 * Backspace is a key event, but has to be caught in the *preview* pass: the text field consumes
 * DEL itself, so a plain onKeyEvent downstream of it never runs at all. It is claimed only when
 * the block is already empty — at any other caret position it must keep meaning "delete a
 * character", which is what returning false preserves.
 */
/**
 * Block-level markdown: the marker you type at the start of a line, and what the line becomes.
 *
 * Only the *block* shapes are here. Inline emphasis (`**bold**`) is deliberately absent — a block
 * stores its text as a plain string, so there is nowhere to record which characters are bold. That
 * needs a spans column and a migration, not a regex.
 */
private val MARKDOWN_BLOCKS: List<Pair<Regex, String>> = listOf(
    Regex("^# ") to NodeType.HEADING,
    Regex("^## ") to NodeType.HEADING,
    Regex("^[-*+] ") to NodeType.BULLET,
    Regex("""^\d+[.)] """) to NodeType.NUMBERED,
    Regex("""^\[[ xX]?] """) to NodeType.TASK,
)

private class BlockEditing(
    val text: String,
    val type: String,
    val onTextChange: (String) -> Unit,
    val onSplit: (before: String, after: String) -> Unit,
    val onMergeBack: () -> Unit,
    val onBecome: (type: String, rest: String) -> Unit,
) {
    fun onValueChange(new: String) {
        // Markdown first: "- " at the start of a line means the line *is* a bullet, so swallow the
        // marker and change the block rather than leaving the characters in the text. Only fires
        // when text was added, so deleting back through a marker cannot re-trigger it.
        if (new.length > text.length) {
            for ((marker, become) in MARKDOWN_BLOCKS) {
                val hit = marker.find(new) ?: continue
                if (become == type) break
                onBecome(become, new.removeRange(hit.range))
                return
            }
        }
        // Only a newline the user just *added* is an Enter. Blocks written before Enter meant
        // anything can already hold newlines, and splitting on "contains a newline" would tear
        // one of those in half the moment you typed a single character into it.
        if (new.count { it == '\n' } <= text.count { it == '\n' }) {
            onTextChange(new)
            return
        }
        val at = firstDifference(text, new)
        if (at !in new.indices || new[at] != '\n') {
            onTextChange(new)
            return
        }
        val before = new.substring(0, at)
        val after = new.substring(at + 1)
        if (before.isBlank() && after.isBlank()) {
            // Enter on an empty list item leaves the list — the standard way out, and the reason
            // you never have to reach for the toolbar to stop making bullets. On anything else it
            // does nothing, since it would only produce a second empty block.
            if (type == NodeType.BULLET || type == NodeType.NUMBERED) onBecome(NodeType.PARAGRAPH, "")
            return
        }
        // Truncate *this* field too, not just the stored row. The row's text state is keyed on the
        // block id, so it never re-reads from the database — leaving it alone meant the field went
        // on showing the whole string while the new block also held the tail, so pressing Enter at
        // the start of a block appeared to duplicate its text into both.
        onTextChange(before)
        onSplit(before, after)
    }

    private fun firstDifference(a: String, b: String): Int {
        var i = 0
        val n = minOf(a.length, b.length)
        while (i < n && a[i] == b[i]) i++
        return i
    }

    fun onKey(event: KeyEvent): Boolean {
        val isBackspace = event.type == KeyEventType.KeyDown && event.key == Key.Backspace
        if (isBackspace && text.isEmpty()) {
            onMergeBack()
            return true
        }
        return false
    }
}

/**
 * The wash that marks the active block. Faint on purpose — it only has to say "this is the one
 * the handle belongs to", and a block is content, not a selected list item.
 */
@Composable
private fun Modifier.activeBlock(active: Boolean): Modifier {
    val y = Yantra.colors
    return if (active) {
        this.background(y.accent.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
    } else this
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TextualBlockRow(
    child: NodeEntity,
    active: Boolean,
    onActivate: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    claimCaret: Boolean,
    onCaretClaimed: () -> Unit,
    onSplit: (before: String, after: String) -> Unit,
    onMergeBack: () -> Unit,
    chips: List<ChipData>,
    childCount: Int,
    ordinal: Int,
    pomoCount: Int,
    autoFocus: Boolean,
    onAutoFocusConsumed: () -> Unit,
    vm: NodePageViewModel,
    onOpen: () -> Unit,
) {
    val y = Yantra.colors
    val isTask = child.type == NodeType.TASK
    val isHeading = child.type == NodeType.HEADING
    val isBullet = child.type == NodeType.BULLET
    val isNumbered = child.type == NodeType.NUMBERED

    var text by remember(child.id) { mutableStateOf(child.title.orEmpty()) }
    var hasFocus by remember(child.id) { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    // Every task is reachable, whether or not anything is under it yet — the chevron is always
    // there. And the text is always editable in place. Those are two different targets on one row
    // rather than one target with two meanings, which is what makes both feel direct: the words
    // are the words, the arrow is the way in.

    androidx.compose.runtime.LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
            onAutoFocusConsumed()
        }
    }
    androidx.compose.runtime.LaunchedEffect(claimCaret) {
        if (!claimCaret) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        // Always ask at least once: if a blur has not propagated yet then hasFocus is still true,
        // a guarded loop exits immediately, and focus never gets requested at all.
        runCatching { focusRequester.requestFocus() }
        var tries = 0
        while (!hasFocus && tries < 4) {
            kotlinx.coroutines.delay(40)
            runCatching { focusRequester.requestFocus() }
            tries++
        }
        onCaretClaimed()
    }

    val editing = BlockEditing(
        text = text,
        type = child.type,
        onTextChange = { text = it; vm.rename(child.id, it) },
        onSplit = onSplit,
        onMergeBack = onMergeBack,
        onBecome = { become, rest ->
            text = rest
            vm.rename(child.id, rest)
            vm.convert(child, become)
        },
    )

    val titleColor = if (isTask && child.done) y.textDim else y.textPrimary
    val deco = if (isTask && child.done) TextDecoration.LineThrough else TextDecoration.None
    val style: TextStyle = when {
        isHeading -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W800, letterSpacing = (-0.2).sp, color = y.textPrimary)
        isTask -> MaterialTheme.typography.bodyLarge.copy(color = titleColor, textDecoration = deco)
        else -> MaterialTheme.typography.bodyMedium.copy(color = y.textSecondary)
    }
    val placeholder = when {
        isTask -> "New task"
        isHeading -> "Heading"
        isBullet || isNumbered -> "List item"
        else -> "Write something…"
    }
    // Roomier than it was: a row you have to press-and-hold needs to be comfortably bigger than
    // a fingertip, and the extra air also makes the gap that opens during a drag legible.
    val vPad = when {
        isTask -> 10.dp
        isHeading -> 14.dp
        isBullet || isNumbered -> 6.dp
        else -> 9.dp
    }

    Column(Modifier.activeBlock(active).padding(vertical = vPad, horizontal = 2.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (isTask) {
                TaskCheck(
                    done = child.done,
                    onToggle = { vm.setDone(child.id, !child.done) },
                    tint = chips.firstOrNull { it.isPriority }?.color,
                    modifier = Modifier.padding(top = 1.dp),
                )
                Spacer(Modifier.width(13.dp))
            } else if (isBullet || isNumbered) {
                // Right-aligned in a fixed column so "9." and "10." keep their text on the same
                // left edge instead of the list stepping sideways as it grows.
                Text(
                    if (isBullet) "•" else "$ordinal.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = y.textMuted),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(22.dp).padding(top = 1.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            // ONE text field for task, note and heading alike, at one call site. Task and text
            // used to be separate composables, so changing a block's type disposed the field
            // the caret was in — the keyboard dropped every single time and had to be won back
            // by a retry loop. Sharing the call site means the field survives the change
            // outright: only its style and placeholder differ.
            BasicTextField(
                value = text,
                onValueChange = editing::onValueChange,
                textStyle = style,
                cursorBrush = SolidColor(y.accent),
                // **bold**, *italic* and `code` render as you type. The markers stay put and stay
                // visible, only dimmed — see MarkdownEmphasis for why that matters.
                visualTransformation = remember(y.textDim) { MarkdownEmphasis(y.textDim) },
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 1.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent(editing::onKey)
                    .onFocusChanged {
                        hasFocus = it.isFocused
                        onFocusChange(it.isFocused)
                        if (it.isFocused) onActivate()
                    },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(placeholder, style = style, color = y.textMuted.copy(alpha = 0.5f))
                        }
                        inner()
                    }
                },
            )
            // The way in, always available on a task. It carries the child count when there is
            // one, so it doubles as "there is something inside" rather than only "tappable".
            if (isTask) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .focusProperties { canFocus = false }
                        .clickable(onClick = onOpen),
                ) {
                    if (childCount > 0) {
                        Text("$childCount", fontSize = 12.sp, fontWeight = FontWeight.W600, color = y.textMuted)
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open as page",
                        tint = if (childCount > 0) y.textMuted else y.textDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (isTask && (chips.isNotEmpty() || pomoCount > 0)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 35.dp, top = 6.dp),
            ) {
                chips.forEach { PropertyChip(it) }
                if (pomoCount > 0) PomodoroCount(pomoCount)
            }
        }
    }
}

@Composable
private fun InkBlockRow(
    child: NodeEntity,
    active: Boolean,
    onActivate: () -> Unit,
    strokes: List<androidx.ink.strokes.Stroke>,
    vm: NodePageViewModel,
    onOpen: () -> Unit,
) {
    val y = Yantra.colors
    // Page-native: the sketch renders straight onto the page background at (near-)true scale.
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .clickable(onClick = onOpen),
        ) {
            if (strokes.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 10.dp),
                ) {
                    Icon(Icons.Default.Draw, contentDescription = null, tint = y.textMuted.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tap to sketch", color = y.textMuted.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val density = LocalDensity.current
                val screenW = LocalContext.current.resources.displayMetrics.widthPixels.toFloat()
                val previewWPx = with(density) { maxWidth.toPx() }
                // Height tracks the sketch's full content so the block grows and pushes the
                // blocks below it down (rather than overlapping them). Clipped so that even a
                // very tall multi-page sketch never paints past its allotted row height.
                val heightDp = remember(strokes, previewWPx) {
                    val contentPx = inkContentHeight(strokes) * (previewWPx / screenW)
                    with(density) { contentPx.toDp() }
                }.coerceIn(64.dp, 2400.dp)
                Box(Modifier.height(heightDp).clipToBounds()) {
                    InkPreview(
                        strokes = strokes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(heightDp),
                    )
                    Text(
                        (child.title?.takeIf { it.isNotBlank() } ?: "Ink").uppercase(),
                        style = MonoBreadcrumb,
                        color = y.textDim,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageBlockRow(
    child: NodeEntity,
    active: Boolean,
    onActivate: () -> Unit,
    vm: NodePageViewModel,
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 6.dp)) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = { onActivate() }),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            AsyncImage(
                model = child.title,
                contentDescription = "Image block",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The block-type bar. Task / Note / Heading are a segmented selector over the block the caret is
 * in — the current one reads as chosen — so the bar answers "what is this block" rather than
 * pretending to be five different add buttons. Ink and Image sit past a divider because they
 * insert rather than convert.
 */
@Composable
private fun BlockTypeBar(
    modifier: Modifier = Modifier,
    currentType: String?,
    onTask: () -> Unit,
    onText: () -> Unit,
    onHeading: () -> Unit,
    onBullet: () -> Unit,
    onNumbered: () -> Unit,
    onInk: () -> Unit,
    onImage: () -> Unit,
    actOnTask: Boolean = false,
    onIndent: (() -> Unit)? = null,
    onOutdent: (() -> Unit)? = null,
    onProperties: (() -> Unit)? = null,
    onFocusTask: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val y = Yantra.colors
    val scroll = rememberScrollState()
    Row(
        modifier
            .fillMaxWidth()
            .background(y.page)
            .let { if (scroll.maxValue > 0) it.horizontalFadingEdge(36.dp) else it }
            .horizontalScroll(scroll)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeChip("Task", selected = currentType == NodeType.TASK, onClick = onTask)
        TypeChip("Note", selected = currentType == NodeType.PARAGRAPH, onClick = onText)
        TypeChip("Heading", selected = currentType == NodeType.HEADING, onClick = onHeading, icon = Icons.Default.Title)
        TypeChip(
            "Bullet",
            selected = currentType == NodeType.BULLET,
            onClick = onBullet,
            icon = Icons.AutoMirrored.Filled.FormatListBulleted,
        )
        TypeChip(
            "Numbered",
            selected = currentType == NodeType.NUMBERED,
            onClick = onNumbered,
            icon = Icons.Default.FormatListNumbered,
        )
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .height(22.dp)
                .width(1.dp)
                .background(y.hairline),
        )
        val noFocus = Modifier.focusProperties { canFocus = false }
        NeutralChip("Ink", onInk, icon = Icons.Default.Draw, modifier = noFocus)
        NeutralChip("Image", onImage, icon = Icons.Default.Image, modifier = noFocus)
        // What the ⋮ used to hide. Out here they are simply visible, and they only appear once a
        // block is actually selected, so the bar is never showing an action with no subject.
        // Move / indent / outdent are gone from this list on purpose: dragging a block does them.
        if (onDelete != null) {
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .height(22.dp)
                    .width(1.dp)
                    .background(y.hairline),
            )
            if (onOutdent != null) {
                NeutralChip("Outdent", onOutdent, icon = Icons.AutoMirrored.Filled.FormatIndentDecrease, modifier = noFocus)
            }
            if (onIndent != null) {
                NeutralChip("Indent", onIndent, icon = Icons.AutoMirrored.Filled.FormatIndentIncrease, modifier = noFocus)
            }
            if (actOnTask && onProperties != null) {
                NeutralChip("Props", onProperties, icon = Icons.Default.Flag, modifier = noFocus)
            }
            if (onFocusTask != null) {
                NeutralChip("Focus", onFocusTask, icon = Icons.Default.Timer, modifier = noFocus)
            }
            DangerChip("Delete", onDelete, modifier = noFocus)
        }
    }
}

/** Destructive twin of [NeutralChip] — the one chip in the bar that needs to look like a warning. */
@Composable
private fun DangerChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .background(y.overdueChipBg, shape)
            .border(1.dp, y.overdue.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Delete, contentDescription = null, tint = y.overdue, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = y.overdue, fontSize = 13.5.sp, fontWeight = FontWeight.W600)
    }
}

/**
 * A chip in the type bar. Explicitly not focusable: `clickable` makes a node focusable, so tapping
 * one pulled focus out of the block being edited, which closed the keyboard. Converting then had to
 * win it back, and even when it did the drop-and-return read as "the keyboard shut on me". Refusing
 * focus here means the caret never leaves the text in the first place.
 */
@Composable
private fun TypeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .focusProperties { canFocus = false }
            .background(if (selected) y.accentFill else y.tileWarm2, shape)
            .border(1.dp, if (selected) y.accentBorder else y.tileBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) y.accent else y.textSecondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            color = if (selected) y.accent else y.textSecondary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.W600,
        )
    }
}

/**
 * The one blank slot on the page, held open by the UI rather than by a stored empty block. Tapping
 * it creates the Note you were going to type anyway. This is what replaces the column of
 * "Write something…" rows: there is always exactly one place to start, and it costs no data.
 */
@Composable
private fun WriteLine(onClick: () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Write something…",
            style = MaterialTheme.typography.bodyMedium,
            color = y.textMuted.copy(alpha = 0.5f),
        )
    }
}
