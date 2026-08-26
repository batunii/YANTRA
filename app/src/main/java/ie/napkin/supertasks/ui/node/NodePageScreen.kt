package ie.napkin.supertasks.ui.node

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.components.ChipData
import ie.napkin.supertasks.ui.components.ConfirmDialog
import ie.napkin.supertasks.ui.components.MarkdownEmphasis
import ie.napkin.supertasks.ui.components.markdownAnnotated
import ie.napkin.supertasks.ui.components.ListGroupRow
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.QuickAddBar
import ie.napkin.supertasks.ui.components.horizontalFadingEdge
import ie.napkin.supertasks.ui.components.NeutralChip
import ie.napkin.supertasks.ui.components.SelectChip
import ie.napkin.supertasks.ui.components.FocusCount
import ie.napkin.supertasks.ui.components.PropertyChip
import ie.napkin.supertasks.ui.components.INK_STRIKE_MS
import ie.napkin.supertasks.ui.components.InkStrike
import ie.napkin.supertasks.ui.components.LocalCompletionTempo
import ie.napkin.supertasks.ui.components.LocalYantraHaptics
import ie.napkin.supertasks.ui.components.TaskState
import ie.napkin.supertasks.ui.components.YantraCheckbox
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.ink.InkPreview
import ie.napkin.supertasks.ui.ink.inkContentHeight
import ie.napkin.supertasks.ui.theme.MonoBreadcrumb
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraMotion
import ie.napkin.supertasks.ui.theme.YantraDisplay
import ie.napkin.supertasks.ui.theme.YantraText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodePageScreen(nav: NavHostController, nodeId: String) {
    val vm: NodePageViewModel = viewModel(key = "node-$nodeId") { NodePageViewModel(container(), nodeId) }
    val node by vm.node.collectAsStateWithLifecycle()
    val crumbs by vm.breadcrumb.collectAsStateWithLifecycle()
    // The page's own blocks — one flat list. Everything on this screen reasons about it: the tally,
    // the ordinals, the drag geometry, the write line. A block's indentation is its own property,
    // so laying the page out never means walking a tree.
    val allBlocks by vm.blocks.collectAsStateWithLifecycle()
    val chips by vm.chips.collectAsStateWithLifecycle()
    val defs by vm.defs.collectAsStateWithLifecycle()
    val ownValues by vm.ownValues.collectAsStateWithLifecycle()
    val allLabels by vm.allLabels.collectAsStateWithLifecycle()
    val listNames by vm.listNames.collectAsStateWithLifecycle()
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
            // Held so this device can keep drawing the full-size original. Not required: the copy
            // that goes into the workspace is what everything falls back to, including this device
            // if the grant is ever revoked.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.addImage(uri, afterId = lastCaretBlockId)
        }
    }

    val current = node
    val isTask = current?.type == NodeType.TASK
    val y = Yantra.colors

    // A list is a list of tasks. Prose, headings, sketches and images are how you describe a task,
    // so they live on the task's own page — a list page neither shows them nor offers to make one.
    // (Anything that predates this rule is gathered onto a "Notes" task by tidyListsToTasksOnly,
    // so the filter can never be the reason something is unreachable.)
    val blocks = remember(allBlocks, isTask) {
        if (isTask) allBlocks else allBlocks.filter { it.type == NodeType.TASK }
    }

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
            onToggleInProgress = { on -> vm.setInProgress(nodeId, on) },
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
                        onCreateAndAttachLabel = { name, colour -> vm.createAndAttachLabel(nodeId, name, colour) },
                        onRecolourLabel = { label, colour -> vm.setLabelColor(label.id, colour) },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            },
        )

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
        // The row under the finger, preferring the one it is actually inside. Resolved from the
        // finger rather than the middle of the block being carried: a full-page sketch has its
        // centre far off screen, and a centre-based hit test aimed at rows nobody pointed at.
        fun rowUnderFinger(): LazyListItemInfo? {
            val id = drag.id ?: return null
            val ids = blocks.mapTo(mutableSetOf()) { it.id }
            val rows = dragRows.filter { it.key in ids && it.key != id }
            val fy = drag.pointerY
            return rows.firstOrNull { fy >= it.offset && fy < it.offset + it.size }
                ?: rows.minByOrNull { kotlin.math.abs((it.offset + it.size / 2f) - fy) }
        }
        val liftedTo = if (liftedId == null) -1
            else blocks.indexOfFirst { it.id == rowUnderFinger()?.key as? String }

        // Vertical only: a block moves up and down among its siblings and never changes level.
        fun commitDrag() {
            val id = drag.id
            val node = blocks.firstOrNull { it.id == id }
            // A lift that never moved is not a reorder. Without this, a long-press-and-release
            // anywhere in a block committed a move: startY is seeded from the block's offset in the
            // LazyColumn, and where that lookup does not resolve it falls back to 0, so the drop
            // target computed from the finger lands on the FIRST row and the block teleports to the
            // top of the list. The guard is right on its own terms too — you picked it up and put it
            // straight back down.
            if (kotlin.math.abs(drag.dy) < 1f) {
                drag.stop()
                return
            }
            if (id != null && node != null) {
                val over = rowUnderFinger()
                val others = blocks.filter { it.id != id }
                val overId = over?.key as? String
                val pos = others.indexOfFirst { it.id == overId }
                if (pos >= 0) {
                    // Direction from the indices rather than the sign of the drag, so a long slow
                    // drag that ends where it started is a no-op instead of a move by one.
                    val from = blocks.indexOfFirst { it.id == id }
                    val to = blocks.indexOfFirst { it.id == overId }
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
                // A run is consecutive numbered lines at the same indentation, so an indented list
                // is its own list and starts again at 1.
                var n = 0
                var atIndent = -1
                blocks.forEach { c ->
                    if (c.type == NodeType.NUMBERED) {
                        n = if (c.indent == atIndent) n + 1 else 1
                        atIndent = c.indent
                        put(c.id, n)
                    } else {
                        n = 0
                        atIndent = -1
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
            // A list card is inset like a card; a document runs to the page edge, with its start
            // inset living inside each block's drag gutter so nothing shifts sideways.
            contentPadding = if (isTask) {
                PaddingValues(start = 2.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            } else {
                PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            },
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
                val lifted = drag.id == child.id
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
                                scaleX = liftScale
                                scaleY = liftScale
                            } else {
                                translationY = slide
                            }
                        }
                        .then(
                            if (lifted) {
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
                        lifted -> 1f
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
                        tint = if (lifted) y.accent else y.textDim,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 4.dp)
                            .size(18.dp)
                            .alpha(gripAlpha),
                    )
                }
                // A list is a card of rows; a task's page is a document, so its blocks sit bare.
                // Prose is not a block you handle, so it is not laid out like one. The gutter
                // exists to hold a drag grip; text and headings have none — only tasks, ink and
                // images can be picked up — so reserving the strip for them just pushed every
                // paragraph a thumb's width off the margin and made a document look like a stack of
                // widgets. They keep the indent, which is theirs.
                Wrapper(
                    grouped = !isTask,
                    inset = (if (draggable) BLOCK_GUTTER else PROSE_MARGIN) + NEST_STEP * child.indent,
                    // Completion supersedes it and the repository clears the flag, so a finished
                    // task never arrives here still lit.
                    started = child.inProgress,
                ) {
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
                    // Complement of `grouped` above: a task's page is a document you type in, a
                    // list is a set of rows you open.
                    editable = isTask,
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
            // The write line is an invitation to a blank page, and nothing else. It shows only when
            // a task's page is genuinely empty; the moment there is anything on the page it goes
            // away, because from then on the page itself tells you where to type — put the caret at
            // the end of the last block and press Enter. A permanent "Write something…" under the
            // last line was a prompt for something you had already started doing.
            //
            // A list never gets one at all: a list captures through the bar at the bottom, the same
            // way a smart list does.
            if (isTask && blocks.isEmpty()) {
                item(key = "write-line") {
                    // A task's page is where you write *about* the task, so the blank it offers is a
                    // note. Notes are never withheld anywhere — the Note chip, "- " markdown, or one
                    // tap on the type bar all still get you one.
                    WriteLine(
                        label = "Write something…",
                        onClick = { vm.addBlock(NodeType.PARAGRAPH, "") { id -> caretTarget = id } },
                    )
                }
            }
            // The rest of the page is writable. Removing the permanent ghost line was right — it
            // was a prompt for something you had already started — but it left the page below the
            // last block inert, and a document where the empty part cannot be typed into is a
            // document that only accepts text through a toolbar. Tapping here does what tapping
            // under the last line does in any editor: puts the caret on a new line. If the page
            // already ends in a blank block, that blank IS the new line, so it is focused instead
            // of another one being made.
            if (isTask) {
                item(key = "page-tail") {
                    val tail = blocks.lastOrNull()
                    val endsBlank = tail != null &&
                        tail.title.isNullOrBlank() &&
                        tail.type in NodeType.TEXTUAL
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(if (blocks.isEmpty()) 120.dp else 220.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (endsBlank) caretTarget = tail.id
                                else vm.addBlock(NodeType.PARAGRAPH, "") { id -> caretTarget = id }
                            },
                    )
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
        // Lists capture with the same bar as a smart list. A task's page has no such bar: you type
        // into it directly, which is what a document is.
        if (!isTask) {
            QuickAddBar(
                modifier = Modifier.navigationBarsPadding().imePadding(),
                labels = allLabels,
                lists = listNames,
                onAdd = { title -> vm.captureTask(title) },
            )
        }
        BlockTypeBar(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding(),
            // Nothing to pick between on a list: every block on it is a task.
            showTypes = isTask,
            currentType = caretBlock?.type?.takeIf { it in textTypes },
            onTask = { setType(NodeType.TASK) },
            onText = { setType(NodeType.PARAGRAPH) },
            onHeading = { setType(NodeType.HEADING) },
            onBullet = { setType(NodeType.BULLET) },
            onNumbered = { setType(NodeType.NUMBERED) },
            onInk = { insertBelow(NodeType.INK) { id -> nav.navigate(Routes.ink(id)) } },
            onImage = { imagePicker.launch(arrayOf("image/*")) },
            actOnTask = actOn?.type == NodeType.TASK,
            // Tab and shift-tab. A line can only go one step deeper than the line above it, so
            // Indent is offered only while there is room, and never on the first block.
            onIndent = actOn?.takeIf { block ->
                val i = blocks.indexOfFirst { it.id == block.id }
                i > 0 && block.indent <= blocks[i - 1].indent
            }?.let { block -> { vm.indent(block) } },
            onOutdent = actOn?.takeIf { it.indent > 0 }?.let { block -> { vm.outdent(block) } },
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
    onToggleInProgress: (Boolean) -> Unit,
    properties: @Composable () -> Unit,
) {
    val y = Yantra.colors
    val crumbCurrent = y.textSecondary
    var menu by remember { mutableStateOf(false) }
    var title by remember(node?.id) { mutableStateOf(node?.title.orEmpty()) }
    Column(
        Modifier
            .fillMaxWidth()
            // Plain rounded edge; see the note on the smart view's band.
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
                    fontFamily = YantraDisplay,
                    fontSize = 16.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.2).sp,
                    color = y.textPrimary,
                    textAlign = TextAlign.Center,
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
                    fontFamily = YantraText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W500,
                    color = y.textMuted,
                    textAlign = TextAlign.Center,
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
                        // The same glyph as the row that led here, so the task looks like itself on
                        // its own page. Frame stays neutral: this is the task's page, not a list, and
                        // priority is reported by its pill below.
                        //
                        // This page has no row to swipe, so the swipe lives on the glyph itself
                        // here. Without it, removing the long-press would have left a task with no
                        // way to be marked in progress from its own page — the one screen where you
                        // are most likely to be deciding to start it.
                        val bandScope = rememberCoroutineScope()
                        val bandDensity = LocalDensity.current
                        val bandSwipe = remember(node.id) { Animatable(0f) }
                        val bandCommit = with(bandDensity) { 56.dp.toPx() }
                        val bandHaptics = LocalYantraHaptics.current
                        var bandArmed by remember(node.id) { mutableStateOf(false) }
                        // Same stale-closure trap as the row swipe — see the note there.
                        val bandInProgress by rememberUpdatedState(node.inProgress)
                        Box(
                            Modifier
                                // Nothing slides here either — the ring traces inside the glyph.
                                .pointerInput(node.id) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            val commit = bandSwipe.value >= bandCommit
                                            bandArmed = false
                                            bandScope.launch {
                                                if (commit) onToggleInProgress(!bandInProgress)
                                                bandSwipe.animateTo(0f, spring(dampingRatio = 0.7f))
                                            }
                                        },
                                        onDragCancel = {
                                            bandArmed = false
                                            bandScope.launch { bandSwipe.animateTo(0f, spring(dampingRatio = 0.7f)) }
                                        },
                                    ) { _, dragAmount ->
                                        val next = (bandSwipe.value + dragAmount).coerceIn(0f, bandCommit * 1.25f)
                                        if (!bandArmed && next >= bandCommit) {
                                            bandArmed = true
                                            bandHaptics?.tick()
                                        } else if (bandArmed && next < bandCommit) {
                                            bandArmed = false
                                        }
                                        bandScope.launch { bandSwipe.snapTo(next) }
                                    }
                                },
                        ) {
                        YantraCheckbox(
                            state = when {
                                node.done -> TaskState.DONE
                                node.inProgress -> TaskState.IN_PROGRESS
                                else -> TaskState.OPEN
                            },
                            taskId = node.id,
                            onComplete = { onToggleDone(true) },
                            onUndo = { onToggleDone(false) },
                            tempo = LocalCompletionTempo.current,
                            haptics = LocalYantraHaptics.current,
                            darkTheme = y.isDark,
                            size = 30.dp,
                            swipeProgress = (bandSwipe.value / bandCommit).coerceIn(0f, 1f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        }
                        Spacer(Modifier.width(12.dp))
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
    editable: Boolean = true,
) {
    when (child.type) {
        // Task, note and heading all go through the SAME composable so that converting between
        // them cannot dispose the text field the caret is sitting in.
        NodeType.INK -> InkBlockRow(child, active, onActivate, inkStrokes, vm, onOpen)
        NodeType.IMAGE -> ImageBlockRow(child, active, onActivate, vm)
        else -> TextualBlockRow(
            child, active, onActivate, onFocusChange, claimCaret, onCaretClaimed, onSplit,
            onMergeBack, chips, childCount, ordinal, pomoCount, autoFocus, onAutoFocusConsumed,
            onRename = { vm.rename(child.id, it) },
            onToggleDone = { vm.setDone(child.id, it) },
            onToggleInProgress = { vm.setInProgress(child.id, it) },
            onBecome = { vm.convert(child, it) },
            onOpen = onOpen,
            editable = editable,
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

/**
 * Where prose starts.
 *
 * Not zero: the drag gutter was doubling as the page margin (the list's own start padding is 2.dp
 * precisely because the gutter supplied the rest), so taking it away put paragraphs flush against
 * the screen edge. This is a margin in its own right, and it sits a little inside the gutter so a
 * task's glyph hangs into it — the way a checklist sits in a document, one left edge for the
 * writing with the marks in the margin beside it.
 */
private val PROSE_MARGIN = 20.dp

/**
 * Puts a block either into the shared list card or bare on the page, without either branch having
 * to know how the other is laid out.
 */
@Composable
private fun Wrapper(
    grouped: Boolean,
    inset: Dp,
    /**
     * Whether this is a task you have said you are on. The card carries the wash on a list, the
     * bare block carries it on a task's page — the same claim, drawn on whatever surface the row
     * happens to have.
     */
    started: Boolean,
    content: @Composable () -> Unit,
) {
    val y = Yantra.colors
    if (grouped) {
        ListGroupRow(started = started) {
            Box(Modifier.padding(start = inset, end = 4.dp)) { content() }
        }
    } else {
        Box(
            Modifier
                .padding(start = inset)
                // After the inset, so the wash lines up with the block and not with the gutter.
                .then(
                    if (started) Modifier.background(y.startedWash, RoundedCornerShape(10.dp))
                    else Modifier
                )
        ) { content() }
    }
}

/** How far each level of nesting steps in. */
private val NEST_STEP = 20.dp

@Stable
private class BlockDrag {
    var id by mutableStateOf<String?>(null)
        private set
    var dy by mutableFloatStateOf(0f)
        private set
    /**
     * Where the finger first went down, in viewport coordinates. The drop target comes from the
     * *finger*, not from the middle of the block being carried: a full-page sketch is thousands of
     * dp tall, so its centre sits far off screen and a centre-based hit test aimed at rows nobody
     * was pointing at.
     */
    var startY by mutableFloatStateOf(0f)
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

/**
 * The one task/note/heading/list row in the app. A list, a smart list and a task's own page all
 * draw their rows through here, so a task looks and behaves the same wherever it is shown — the
 * only thing that varies is which tasks a screen puts in front of it.
 *
 * Takes actions rather than a ViewModel for exactly that reason: it was welded to
 * NodePageViewModel, which is why the smart list had to grow a second, read-only copy of this row
 * that then drifted (tap-to-edit on one screen, tap-to-open on the other).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextualBlockRow(
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
    onRename: (String) -> Unit,
    onToggleDone: (Boolean) -> Unit,
    onToggleInProgress: (Boolean) -> Unit,
    onBecome: (String) -> Unit,
    onOpen: () -> Unit,
    /**
     * Whether the words themselves are editable. On a task's own page they are: the page IS the
     * text, so tapping a line puts the caret in it. On a list they are not — a list is a set of
     * destinations, so the whole row is one target and tapping it opens the task, which is where
     * its text lives. Same row either way; only what a tap means changes.
     */
    editable: Boolean = true,
) {
    val y = Yantra.colors
    val isTask = child.type == NodeType.TASK
    val isHeading = child.type == NodeType.HEADING
    val isBullet = child.type == NodeType.BULLET
    val isNumbered = child.type == NodeType.NUMBERED

    // TextFieldValue, not String: a String-backed field gives no way to say where the caret should
    // land, and merging back into the previous block dropped it at position 0 — you pressed
    // Backspace to join two lines and ended up typing in front of the line you had joined.
    var field by remember(child.id) { mutableStateOf(TextFieldValue(child.title.orEmpty())) }
    val text = field.text
    var hasFocus by remember(child.id) { mutableStateOf(false) }
    // Where the title's glyphs actually landed, so the ink strike can be drawn across the words.
    var titleLayout by remember(child.id) { mutableStateOf<TextLayoutResult?>(null) }
    val focusRequester = remember { FocusRequester() }

    // Every task is reachable, whether or not anything is under it yet — the chevron is always
    // there. And the text is always editable in place. Those are two different targets on one row
    // rather than one target with two meanings, which is what makes both feel direct: the words
    // are the words, the arrow is the way in.

    LaunchedEffect(autoFocus, editable) {
        if (autoFocus && editable) {
            runCatching { focusRequester.requestFocus() }
            onAutoFocusConsumed()
        }
    }
    LaunchedEffect(claimCaret, editable) {
        if (!claimCaret || !editable) return@LaunchedEffect
        // Before focusing, put the caret where the writing stopped. A block claims the caret when
        // the block after it was merged into it, so the end of this text is exactly where the
        // deleted line used to begin — carrying on typing should carry on from there.
        field = field.copy(selection = TextRange(field.text.length))
        withFrameNanos { }
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
        // Ordinary typing has already updated `field` with Compose's own caret, so leave it be.
        // Only when the editor rewrote the text itself — a split truncating this block, a markdown
        // marker being swallowed — is the field replaced, and then the caret goes to the end.
        onTextChange = { new ->
            if (new != field.text) field = TextFieldValue(new, TextRange(new.length))
            onRename(new)
        },
        onSplit = onSplit,
        onMergeBack = onMergeBack,
        onBecome = { become, rest ->
            field = TextFieldValue(rest, TextRange(rest.length))
            onRename(rest)
            onBecome(become)
        },
    )

    val titleColor = if (isTask && child.done) y.textDim else y.textPrimary
    // No strikethrough. A finished task is struck through with the ink strike below — a pen mark in
    // coral, seeded by the task id so a given task's strike is always the same wobble. The font's
    // ruler-straight line said "field disabled"; the strike says someone crossed it off.
    val style: TextStyle = when {
        isHeading -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W800, letterSpacing = (-0.2).sp, color = y.textPrimary)
        isTask -> MaterialTheme.typography.bodyLarge.copy(color = titleColor)
        else -> MaterialTheme.typography.bodyMedium.copy(color = y.textSecondary)
    }
    // Drawn over the title, driven by done-ness. The full choreography's strike timing lives in the
    // glyph; here it is the same one-shot, so a row struck by a tap and a row that arrives already
    // done agree on the mark.
    val strike by animateFloatAsState(
        targetValue = if (isTask && child.done) 1f else 0f,
        animationSpec = tween(if (child.done) INK_STRIKE_MS else 160),
        label = "inkStrike",
    )
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

    // Swipe right on a task to say "I am on this". This replaced a long-press on the glyph: the
    // glyph is a small target and press-and-hold is a gesture you have to be told about, whereas a
    // row that follows your finger and shows the mark it is about to leave explains itself.
    //
    // It toggles: swipe to start a task, swipe again to put it back down. Set-only was tried and
    // is worse — a task marked by accident could then only be cleared by completing it and
    // un-completing it, which is two taps that both write the wrong thing on the way past.
    // Finishing the task also clears the flag, in the same UPDATE (see NodeDao.setDone).
    //
    // The row stays where it is. What moves is the engagement circle inside the glyph, traced by
    // your finger (swipeProgress below) — the gesture is filling in the mark, not shoving the row
    // aside, and the feedback belongs where the meaning is.
    //
    // detectHorizontalDragGestures only claims the pointer after horizontal slop, so vertical
    // scrolling and the row's own tap both still work.
    val swipeScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeX = remember(child.id) { Animatable(0f) }
    val commitAt = with(density) { 72.dp.toPx() }
    val swipeCeiling = commitAt * 1.25f
    val swipeHaptics = LocalYantraHaptics.current
    var armed by remember(child.id) { mutableStateOf(false) }
    // pointerInput keys on child.id, so its gesture block is NOT rebuilt when in_progress changes —
    // it closes over whichever `child` was current when the row first composed. Reading the flag
    // straight from that closure meant every swipe computed !false and wrote "in progress" again,
    // so the state could be entered and never left. rememberUpdatedState is the fix: the gesture
    // keeps its identity, the value it reads stays current.
    val nowInProgress by rememberUpdatedState(child.inProgress)

    Column(
        Modifier
            .then(
                if (!isTask) Modifier else Modifier.pointerInput(child.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val commit = swipeX.value >= commitAt
                            armed = false
                            swipeScope.launch {
                                if (commit) onToggleInProgress(!nowInProgress)
                                swipeX.animateTo(0f, spring(dampingRatio = 0.7f))
                            }
                        },
                        onDragCancel = {
                            armed = false
                            swipeScope.launch { swipeX.animateTo(0f, spring(dampingRatio = 0.7f)) }
                        },
                    ) { _, dragAmount ->
                        // Rightward only. Left is deliberately left free rather than given a second
                        // meaning nobody asked for.
                        val next = (swipeX.value + dragAmount).coerceIn(0f, swipeCeiling)
                        if (!armed && next >= commitAt) {
                            armed = true
                            swipeHaptics?.tick()
                        } else if (armed && next < commitAt) {
                            armed = false
                        }
                        swipeScope.launch { swipeX.snapTo(next) }
                    }
                }
            )
            // Only on a task. The wash says "this is the block the handle belongs to", and prose
            // has no handle — on a paragraph it just made the line you were writing look selected.
            .activeBlock(active && isTask)
            .then(if (editable) Modifier else Modifier.clickable(onClick = onOpen))
            .padding(vertical = vPad, horizontal = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (isTask) {
                YantraCheckbox(
                    state = when {
                        child.done -> TaskState.DONE
                        child.inProgress -> TaskState.IN_PROGRESS
                        else -> TaskState.OPEN
                    },
                    taskId = child.id,
                    onComplete = { onToggleDone(true) },
                    onUndo = { onToggleDone(false) },
                    tempo = LocalCompletionTempo.current,
                    haptics = LocalYantraHaptics.current,
                    darkTheme = y.isDark,
                    size = 26.dp,
                    // Priority draws the enclosure and nothing else. A done task loses it: there is
                    // no urgency left to report, and the law keeps crimson off completion.
                    frameTint = if (child.done) null else chips.firstOrNull { it.isPriority }?.color,
                    swipeProgress = (swipeX.value / commitAt).coerceIn(0f, 1f),
                    modifier = Modifier.padding(top = 1.dp),
                )
                Spacer(Modifier.width(11.dp))
            } else if (isBullet || isNumbered) {
                // Right-aligned in a fixed column so "9." and "10." keep their text on the same
                // left edge instead of the list stepping sideways as it grows.
                Text(
                    if (isBullet) "•" else "$ordinal.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = y.textMuted),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(22.dp).padding(top = 1.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            // ONE text field for task, note and heading alike, at one call site. Task and text
            // used to be separate composables, so changing a block's type disposed the field
            // the caret was in — the keyboard dropped every single time and had to be won back
            // by a retry loop. Sharing the call site means the field survives the change
            // outright: only its style and placeholder differ.
            // The title, with the ink strike over it. Boxed together so the strike can be measured
            // against the words rather than the row: a mark that ran the full width would float off
            // the end of every short title.
            Box(Modifier.weight(1f)) {
            if (!editable) {
                // Read straight from the entity, not from the field's cached text: that cache is
                // keyed on the block id alone, so a title renamed on its own page would come back
                // here stale. Nothing types into this row, so there is no caret to protect.
                val shown = child.title.orEmpty()
                val textMod = Modifier.fillMaxWidth().padding(top = 1.dp)
                if (shown.isBlank()) {
                    Text(placeholder, style = style, color = y.textMuted.copy(alpha = 0.5f), modifier = textMod)
                } else {
                    Text(
                        // A task title is a name and stays literal; prose is prose. See below.
                        if (isTask) AnnotatedString(shown) else markdownAnnotated(shown, y.textDim),
                        style = style,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { titleLayout = it },
                        modifier = textMod,
                    )
                }
            } else BasicTextField(
                value = field,
                onValueChange = { v ->
                    field = v
                    editing.onValueChange(v.text)
                },
                textStyle = style,
                cursorBrush = SolidColor(y.accent),
                // Prose only. **bold**, *italic* and `code` render as you type, markers staying
                // put and merely dimmed — see MarkdownEmphasis for why that matters.
                //
                // A task title is deliberately not prose. It is a name, and it is shown in places
                // that cannot style anything at all: a widget, a notification, the archive, the
                // focus screen. Emphasis there could only ever be dropped or shown raw, so the
                // honest answer is that a title means exactly the characters it contains. That
                // also leaves the punctuation free for capture to spend — `~` names a list.
                visualTransformation =
                    if (isTask) VisualTransformation.None
                    else remember(y.textDim) { MarkdownEmphasis(y.textDim) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent(editing::onKey)
                    .onFocusChanged {
                        hasFocus = it.isFocused
                        onFocusChange(it.isFocused)
                        if (it.isFocused) onActivate()
                    },
                onTextLayout = { titleLayout = it },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(placeholder, style = style, color = y.textMuted.copy(alpha = 0.5f))
                        }
                        inner()
                    }
                },
            )
            // Struck across the first line's actual glyph run. A wrapped title gets its first line
            // marked, which is what you see anyway at maxLines = 2.
            val layout = titleLayout
            if (isTask && strike > 0f && layout != null && layout.lineCount > 0) {
                val density = LocalDensity.current
                val runWidth = layout.getLineRight(0).coerceAtMost(layout.size.width.toFloat())
                val lineTop = layout.getLineTop(0)
                val lineHeight = layout.getLineBottom(0) - lineTop
                InkStrike(
                    taskId = child.id,
                    progress = strike,
                    darkTheme = y.isDark,
                    modifier = Modifier
                        .offset { IntOffset(0, lineTop.roundToInt()) }
                        .width(with(density) { runWidth.toDp() })
                        .height(with(density) { lineHeight.toDp() }),
                )
            }
            }
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
                if (pomoCount > 0) FocusCount(pomoCount)
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

/**
 * A picture.
 *
 * Drawn from the best copy this device has. The workspace holds a downscaled one that reached here
 * through git and is always correct; the device that picked the image also kept the original and
 * prefers it. Falling back costs sharpness and nothing else — never a missing picture, which is what
 * happened before the workspace carried its own copy.
 */
@Composable
private fun ImageBlockRow(
    child: NodeEntity,
    active: Boolean,
    onActivate: () -> Unit,
    vm: NodePageViewModel,
) {
    // The block id is the file name; a value left over from before the copy existed is a
    // `content://` URI, and is still worth drawing on the device that owns it.
    val imageId = child.title.orEmpty()
    var inRepo by remember(imageId) { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(imageId) { inRepo = vm.imageFile(child.id) }

    val model: Any? = remember(imageId, inRepo) {
        vm.originalFor(imageId) ?: inRepo ?: imageId.takeIf { it.startsWith("content://") }
    }

    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 6.dp)) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = { onActivate() }),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            AsyncImage(
                model = model,
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
    showTypes: Boolean,
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
    // On a list with nothing selected the bar has nothing to offer, so it does not sit there as a
    // strip of empty chrome.
    if (!showTypes && onDelete == null) return
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
        val noFocus = Modifier.focusProperties { canFocus = false }
        if (showTypes) {
            SelectChip("Task", selected = currentType == NodeType.TASK, onClick = onTask)
            SelectChip("Note", selected = currentType == NodeType.PARAGRAPH, onClick = onText)
            SelectChip("Heading", selected = currentType == NodeType.HEADING, onClick = onHeading, icon = Icons.Default.Title)
            SelectChip(
                "Bullet",
                selected = currentType == NodeType.BULLET,
                onClick = onBullet,
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
            )
            SelectChip(
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
            NeutralChip("Ink", onInk, icon = Icons.Default.Draw, modifier = noFocus)
            NeutralChip("Image", onImage, icon = Icons.Default.Image, modifier = noFocus)
        }
        // What the ⋮ used to hide. Out here they are simply visible, and they only appear once a
        // block is actually selected, so the bar is never showing an action with no subject.
        // Moving a block is the drag; indenting is a button, because it changes how a line reads
        // rather than where it is.
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

/**
 * The one blank slot on the page, held open by the UI rather than by a stored empty block. Tapping
 * it creates whatever the page is for — a task on a list, a note on a task's own page. This is
 * what replaces the column of blank rows: there is always exactly one place to start, and it costs
 * no data until you type in it.
 */
@Composable
private fun WriteLine(label: String, onClick: () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = y.textMuted.copy(alpha = 0.5f),
        )
    }
}
