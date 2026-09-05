package ie.napkin.supertasks.ui.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.ink.ShapeKind
import ie.napkin.supertasks.data.format.Links
import ie.napkin.supertasks.domain.FocusTimer
import ie.napkin.supertasks.data.ink.StrokeCodec
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.ChipSize
import ie.napkin.supertasks.ui.components.SelectChip
import ie.napkin.supertasks.ui.components.ButtonTone
import ie.napkin.supertasks.ui.components.YantraButton
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle

/**
 * A drawing session.
 *
 * **The screen owns the strokes while you are drawing, not the database.** It used to be the other
 * way round: each finished stroke was written to its sidecar, the whole workspace index was rebuilt,
 * and the canvas re-rendered from whatever Room emitted afterwards. Drawing quickly meant a stroke
 * leaving the live canvas and not coming back until a full rebuild had run — so it blinked out and
 * returned, several times over, in the order they were drawn.
 *
 * Now a stroke lands in [held] the instant it is finished and is drawn from there. The file is
 * written after a short pause, once, with the whole list. That removes the flicker, removes the
 * read-modify-write that was losing strokes outright, and turns eight index rebuilds into one.
 *
 * The file is still the truth — it is just not asked a question mid-sentence. What is deliberately
 * *not* done is waiting for the session to end: a screen that only saves when you leave loses
 * everything if Android kills the app while you are drawing, and ink is the one thing here that
 * cannot be retyped.
 */
class InkViewModel(
    container: AppContainer,
    val nodeId: String,
) : ViewModel() {
    private val ink = container.ink
    private val nodes = container.nodes
    private val timer = container.timer

    /** Outlives this view model, so the last stroke survives leaving the screen. */
    private val appScope = container.appScope

    /** What to draw, and the bytes that would be written for it. */
    private data class Held(val item: StrokeItem, val data: ByteArray)

    private val held = MutableStateFlow<List<Held>>(emptyList())

    /**
     * Bumped on every edit and captured across a write.
     *
     * Without it a stroke drawn *during* a save would be marked saved by that save finishing, and
     * the next thing the file said would quietly undraw it.
     */
    private var edits = 0
    private var savedAt = 0
    private var flush: Job? = null
    private var live = 0

    /**
     * Whether anything has been drawn on this screen, ever — and therefore whether the file still
     * has anything to tell it.
     *
     * This used to be [unsaved], which is a different question and the wrong one. `unsaved` goes
     * false again after every write, so the door it guards reopened between strokes: draw once, let
     * it save, draw again, and the *first* write's index rebuild could still be in flight. When that
     * older emission arrived it found a clean flag, was adopted, and quietly undrew the second
     * stroke. Whether it landed before or after the second save was a matter of milliseconds, which
     * is why the stroke came back sometimes and not others, and why a cold launch — where the first
     * rebuild is the slow one — made it far likelier.
     *
     * Sticky is also what the rule always said out loud: follow the file only while the screen has
     * nothing of its own. Once something is drawn here, this screen is the author, and no arrival
     * from disk can be newer than what is on it.
     */
    private var drawnHere = false

    private val unsaved: Boolean get() = edits != savedAt

    val node: StateFlow<NodeEntity?> =
        nodes.observe(nodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val strokes: StateFlow<List<StrokeItem>> =
        held.map { list -> list.map { it.item } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Follow the file, but only until something is drawn here — see [drawnHere]. After that the
        // screen is the author and every arrival from disk is older than what is on it, including
        // the ones this screen caused itself.
        viewModelScope.launch {
            ink.strokes(nodeId).collect { rows ->
                if (drawnHere) return@collect
                held.value = withContext(Dispatchers.Default) {
                    rows.mapNotNull { row ->
                        runCatching { Held(StrokeItem(row.id, StrokeCodec.decode(row.data)), row.data) }
                            .getOrNull()
                    }
                }
            }
        }
    }

    /**
     * What to call this sketch before anyone names it.
     *
     * "Untitled sketch" told you the one thing you already knew — that you had not named it — on
     * every sketch, so the header said the same nothing on all of them. A sketch is always on some
     * page, and being *this page's second drawing* is a real answer: `get milk · ink 2`.
     *
     * A placeholder, not a title. Nothing is written to the file, so a sketch that has never been
     * named still has no name in the format, and renaming the task it sits under renames what its
     * sketches are called without touching them.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val placeholder: StateFlow<String> =
        node.flatMapLatest { self ->
            val parent = self?.parentId
            if (parent == null) flowOf("Sketch")
            else combine(nodes.observe(parent), nodes.children(parent)) { owner, kids ->
                val inks = kids.filter { it.type == NodeType.INK }
                val at = inks.indexOfFirst { it.id == nodeId }
                val ordinal = (if (at < 0) inks.size else at) + 1
                val name = Links.plain(owner?.title.orEmpty()).trim()
                if (name.isBlank()) "Ink $ordinal" else "$name · ink $ordinal"
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Sketch")

    /**
     * The running session, when it belongs to the task this sketch sits under.
     *
     * A sketch is not a thing you can time — the ledger records sessions against tasks — so the
     * question this answers is "is the task I am drawing *for* on the clock", and the answer is
     * read-only here. Stopping stays on the focus screen: one place to stop means stopping is
     * always deliberate, which is what makes the ledger worth reading.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val liveOnOwner: StateFlow<FocusTimer.State?> =
        node.flatMapLatest { self ->
            val owner = self?.parentId
            if (owner == null) flowOf(null)
            else timer.state.map { live -> live?.takeIf { it.nodeId == owner } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun save(stroke: Stroke, family: String) {
        val data = StrokeCodec.encode(stroke, family)
        // A local id, because this stroke has no place in the file yet. The eraser works off the
        // list the screen is holding, so it never needs the positional id the index would give it.
        held.update { it + Held(StrokeItem("live-${live++}", stroke), data) }
        redoable.value = emptyList()
        touch()
    }

    fun erase(id: String) {
        held.update { list -> list.filterNot { it.item.id == id } }
        redoable.value = emptyList()
        touch()
    }

    /**
     * What undo took off, waiting to be put back.
     *
     * Cleared by anything that draws or erases, because a redo stack that survives a new stroke is
     * offering to reinstate something into a page that has moved on since — you would press it
     * expecting the last thing back and get a stroke from two minutes ago landing on top.
     */
    private val redoable = MutableStateFlow<List<Held>>(emptyList())

    val canUndo: StateFlow<Boolean> =
        held.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val canRedo: StateFlow<Boolean> =
        redoable.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun undo() {
        val list = held.value
        if (list.isEmpty()) return
        redoable.value = redoable.value + list.last()
        held.value = list.dropLast(1)
        touch()
    }

    /**
     * Puts the last undone stroke back.
     *
     * It goes on the end, which is where it came from: strokes are drawn in list order, so
     * reinstating one anywhere else would change what covers what.
     */
    fun redo() {
        val stack = redoable.value
        if (stack.isEmpty()) return
        held.value = held.value + stack.last()
        redoable.value = stack.dropLast(1)
        touch()
    }

    fun eraseAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val gone = ids.toSet()
        held.update { list -> list.filterNot { it.item.id in gone } }
        redoable.value = emptyList()
        touch()
    }

    /**
     * Shifts a lasso'd group by [dx], [dy].
     *
     * Rewrites the strokes rather than remembering an offset beside them: a stroke's position is
     * its points, and anything else would be a second place a stroke can be, which the file format
     * has no room for and the eraser would not know about.
     */
    fun moveStrokes(ids: Collection<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        val moving = ids.toSet()
        held.update { list ->
            list.map { h ->
                if (h.item.id !in moving) h
                else {
                    val data = StrokeCodec.translate(h.data, dx, dy)
                    Held(StrokeItem(h.item.id, StrokeCodec.decode(data)), data)
                }
            }
        }
        redoable.value = emptyList()
        touch()
    }

    fun clear() {
        held.value = emptyList()
        redoable.value = emptyList()
        touch()
    }

    fun rename(title: String) { viewModelScope.launch { nodes.rename(nodeId, title) } }

    /** Writes now rather than waiting out the pause — the app going to the background. */
    fun flushNow() {
        flush?.cancel()
        if (unsaved) write(appScope)
    }

    private fun touch() {
        drawnHere = true
        edits++
        flush?.cancel()
        flush = viewModelScope.launch {
            delay(QUIET_MS)
            write(viewModelScope)
        }
    }

    private fun write(scope: CoroutineScope) {
        val at = edits
        val snapshot = held.value.map { it.data }
        scope.launch {
            ink.replace(nodeId, snapshot)
            // Only clean if nothing was drawn while that was in flight.
            if (at == edits) savedAt = at
        }
    }

    override fun onCleared() {
        // The view model's scope dies with it, so the last stroke has to leave on a scope that does
        // not. Backing out of the screen is exactly when an unsaved stroke would be lost.
        flushNow()
    }

    private companion object {
        /**
         * Long enough to sit inside a burst of strokes, short enough that any real pause persists.
         * This is not the git commit cadence — that is [ie.napkin.supertasks.data.sync.CommitPolicy],
         * and it is measured in seconds because a commit is a much bigger thing than a file write.
         */
        const val QUIET_MS = 900L
    }
}

/**
 * The five drawing colours, which are colours and mean nothing else.
 *
 * Coral used to sit at the front of this list. That was a copy of the accent made before the accent
 * could be changed, and it stayed a copy afterwards: pick Jade and the first swatch went on offering
 * coral — a hue with no remaining part in the app, presented as though it were the house colour. See
 * [inkPresets], which puts the live accent there instead.
 */
private val DrawingColors = listOf(
    0xFF6FA8E4L, // blue
    0xFF4E9478L, // green
    0xFFE0A83EL, // amber
    0xFFC56A94L, // pink
    0xFF8B6BA8L, // purple
)

/**
 * The swatch row: whatever the app currently calls its own ink, then five colours that are only
 * colours.
 *
 * The accent leads because a drawing inside a task belongs to the same document as the task, and
 * the first thing offered should be the hue the rest of the app is already drawn in.
 *
 * It is resolved per theme, so the swatch is the accent as it looks on *this* paper — the light and
 * dark inks of an accent are not the same value, and offering the other one would hand you a colour
 * that does not appear anywhere on screen.
 *
 * Only the swatch follows the accent. A stroke stores the colour it was drawn with, so changing
 * accent afterwards repaints nothing: a drawing is a drawing, not a themed surface.
 *
 * Nothing is dropped for being close to the accent. A first attempt filtered near-duplicates, and
 * the arithmetic said coral and amber were the same colour — they are the two warm swatches the
 * original palette deliberately carried side by side. A wrong heuristic that silently removes a
 * colour someone wanted is worse than two swatches that happen to be neighbours.
 */
private fun inkPresets(accent: Color): List<Long> =
    listOf(accent.toArgb().toLong() and 0xFFFFFFFFL) + DrawingColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkScreen(nav: NavHostController, nodeId: String) {
    val vm: InkViewModel = viewModel(key = "ink-$nodeId") { InkViewModel(container(), nodeId) }
    val strokes by vm.strokes.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val placeholder by vm.placeholder.collectAsStateWithLifecycle()
    val liveOwner by vm.liveOnOwner.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val node by vm.node.collectAsStateWithLifecycle()

    // The pause before writing is short, but "the app went away" should not have to wait it out —
    // that window is exactly when Android is most likely to kill the process.
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { vm.flushNow() }
    }
    val y = Yantra.colors
    // See InkPreview: the ink layer follows the app's theme, not the phone's.
    val dark = y.isDark
    val density = LocalDensity.current

    val defaultInk = InkTheme.defaultPen(dark)
    // The kit, and the one slot it is currently holding. Keyed on the paper so the two plain pens
    // open in an ink that is legible on it — a graphite default on a near-black page is a pen that
    // draws nothing.
    var slots by remember(dark) { mutableStateOf(defaultSlots(defaultInk)) }
    var active by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(InkMode.DRAW) }
    var panel by remember { mutableStateOf<KitPanel?>(null) }
    var eraserSize by remember { mutableFloatStateOf(22f) }
    var snap by remember { mutableStateOf(false) }
    var shapeKind by remember { mutableStateOf(ShapeKind.LINE) }
    var recents by remember { mutableStateOf(listOf<Long>()) }
    var colorSheet by remember { mutableStateOf(false) }
    /**
     * Which edge the kit sits on.
     *
     * The kit belongs under the writing hand and the undo pair opposite it, so a left-hander needs
     * them the other way round or the kit is exactly where the wrist is. One switch, on the screen
     * it affects, rather than a preference buried a settings page away from the only place it can
     * be judged.
     */
    var leftHanded by rememberSaveable { mutableStateOf(false) }
    var kitFolded by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectionAt by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    var page by remember { mutableStateOf(1) }
    var pageCount by remember { mutableStateOf(1) }
    var stylusMode by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    var canvasRef by remember { mutableStateOf<InkCanvas?>(null) }

    val slot = slots[active]
    fun setSlot(i: Int, change: (PenSlot) -> PenSlot) {
        slots = slots.mapIndexed { at, s -> if (at == i) change(s) else s }
    }

    var title by remember(node?.id) { mutableStateOf(node?.title.orEmpty()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page)
            .statusBarsPadding(),
    ) {
        // header: back · editable name · mode hint
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavCircle(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                onClick = { nav.popBackStack() },
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = title,
                onValueChange = { title = it; vm.rename(it) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.W700, color = y.textPrimary),
                cursorBrush = SolidColor(y.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) Text(placeholder, fontSize = 17.sp, fontWeight = FontWeight.W700, color = y.textMuted.copy(alpha = 0.6f))
                        inner()
                    }
                },
            )
            // The clock takes the corner while a session runs. The input hint is a standing
            // nicety and the elapsed time is the news, and there is room for exactly one of them.
            val live = liveOwner
            if (live != null) {
                val shown = if (live.isOpen) live.elapsedSecs else live.remainingSecs
                // The same pill as the task page's band, and it goes to the same place. A clock on
                // screen that cannot be tapped is a clock you have to navigate back out of the
                // sketch to reach, which is the one thing you were avoiding by drawing here.
                Row(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(y.accentFill)
                        .border(1.dp, y.accentBorder, RoundedCornerShape(14.dp))
                        .clickable { nav.navigate(Routes.FOCUS_CURRENT) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = "Open the running session",
                        tint = y.accent,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        "%d:%02d".format(shown / 60, shown % 60),
                        fontFamily = YantraMono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 0.5.sp,
                        color = y.accentText,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            } else {
                Text(
                    if (stylusMode) "Pen draws" else "1 finger draws",
                    fontSize = 11.sp, color = y.textDim,
                )
            }
        }

        // The page and everything that floats over it. The kit and the undo pair are *on* the
        // canvas rather than in a tray beneath it, because a tray takes a strip of paper away for
        // as long as the screen is open and the page is the reason you are here.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    InkCanvas(ctx).apply {
                        onViewportChanged = { p, c -> page = p; pageCount = c }
                        onStylusModeChanged = { stylusMode = it }
                        onDrawingChanged = { drawing = it }
                        onLassoSelection = { ids, cx, bottom ->
                            selection = ids
                            selectionAt = Offset(cx, bottom)
                        }
                        onMoveSelection = { ids, dx, dy -> vm.moveStrokes(ids, dx, dy) }
                        canvasRef = this
                    }
                },
                update = { canvas ->
                    canvas.darkTheme = dark
                    canvas.surface(
                        paper = y.inkPaper.toArgb(),
                        separator = y.inkPageSep.toArgb(),
                        pageLabel = y.textDim.toArgb(),
                    )
                    canvas.lasso(y.accent.toArgb())
                    canvas.shapeKind = shapeKind
                    canvas.tool = when (mode) {
                        InkMode.LASSO -> EditorTool.LASSO
                        InkMode.ERASE -> EditorTool.ERASE
                        // Dragging a shape out directly, as the old Shapes tool did.
                        InkMode.SHAPE -> EditorTool.SHAPE
                        // Always DRAW. Snapping is not a tool — it is something that happens to a
                        // stroke you drew with the pen, and the recogniser only ever sees strokes
                        // that came down this path. Sending the canvas to EditorTool.SHAPE when
                        // snapping was on took every stroke somewhere the recogniser never runs,
                        // so the feature was on and did nothing.
                        InkMode.DRAW -> EditorTool.DRAW
                    }
                    // Recognition belongs to the freehand pen. While you are dragging a shape out
                    // on purpose there is nothing to recognise.
                    canvas.recognizeShapes = snap && mode == InkMode.DRAW
                    canvas.eraserRadius = with(density) { eraserSize.dp.toPx() }
                    val f = slot.family
                    val w = if (f == StrokeCodec.FAMILY_HIGHLIGHTER) slot.width * 3f else slot.width
                    canvas.brushProvider = { StrokeCodec.brush(f, slot.color, w) }
                    canvas.onStrokeFinished = { stroke -> vm.save(stroke, f) }
                    canvas.onErase = { id -> vm.erase(id) }
                    canvas.setStrokeItems(InkTheme.displayItems(strokes, dark))
                },
            )

            // Strokes fade out under the controls; the controls do not. Above the ink and below
            // the kit, so writing that runs to the bottom of the screen stops competing with the
            // things sitting on top of it without either of them moving.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, y.page)),
                    ),
            )

            PenKit(
                slots = slots,
                active = active,
                mode = mode,
                snap = snap,
                // Everything except undo dims while the pen is down.
                dimmed = drawing,
                folded = kitFolded,
                onFold = { kitFolded = !kitFolded },
                onSlot = { active = it; mode = InkMode.DRAW },
                onMode = { mode = it },
                onSnap = { snap = !snap },
                panel = panel,
                onPanel = { panel = it },
                leftHanded = leftHanded,
                controls = { open ->
                    KitControls(
                        panel = open,
                        slot = (open as? KitPanel.Slot)?.let { slots[it.index] },
                        recents = recents,
                        eraserSize = eraserSize,
                        snap = snap,
                        drawingShapes = mode == InkMode.SHAPE,
                        shapeKind = shapeKind,
                        leftHanded = leftHanded,
                        onSlotChange = { next -> (open as? KitPanel.Slot)?.let { setSlot(it.index) { next } } },
                        onEraserSize = { eraserSize = it },
                        onShapeMode = { picked ->
                            when (picked) {
                                ShapeMode.OFF -> { snap = false; if (mode == InkMode.SHAPE) mode = InkMode.DRAW; panel = null }
                                ShapeMode.RECOGNISE -> { snap = true; if (mode == InkMode.SHAPE) mode = InkMode.DRAW }
                                ShapeMode.DRAW -> { snap = false; mode = InkMode.SHAPE }
                            }
                        },
                        onShapeKind = { shapeKind = it },
                        onCustomColor = { colorSheet = true },
                        onHanded = { leftHanded = !leftHanded },
                    )
                },
                modifier = Modifier
                    .align(if (leftHanded) Alignment.BottomStart else Alignment.BottomEnd)
                    .padding(22.dp),
            )

            // The bar that acts on a catch, placed under it rather than in a corner: it is about
            // *those* strokes, and a menu that appears somewhere fixed reads as a menu about the
            // screen. Clamped so a selection near an edge still gets a reachable bar.
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    at = selectionAt,
                    bounds = canvasSize,
                    onDelete = {
                        vm.eraseAll(selection)
                        selection = emptyList()
                        canvasRef?.clearSelection()
                    },
                    onDismiss = {
                        selection = emptyList()
                        canvasRef?.clearSelection()
                    },
                )
            }

            UndoPair(
                canUndo = canUndo,
                canRedo = canRedo,
                onUndo = vm::undo,
                onRedo = vm::redo,
                modifier = Modifier
                    .align(if (leftHanded) Alignment.BottomEnd else Alignment.BottomStart)
                    .padding(18.dp),
            )
        }
    }

    if (colorSheet) {
        ColorPickerSheet(
            initial = slot.color,
            onDismiss = { colorSheet = false },
            onPick = { picked ->
                (panel as? KitPanel.Slot)?.let { at -> setSlot(at.index) { it.copy(color = picked) } }
                recents = (listOf(picked) + recents).distinct().take(6)
                colorSheet = false
            },
        )
    }
}

/**
 * The controls behind whatever was tapped twice.
 *
 * The kit opens sideways rather than into a sheet. A sheet covers the drawing, has to be dismissed,
 * and puts the thing you are adjusting behind the thing adjusting it — which is the wrong way round
 * when the only way to judge a pen width is to look at the stroke next to it. This sits beside the
 * kit, on the hand-free side, and closes on the same tap that opened it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitControls(
    panel: KitPanel,
    slot: PenSlot?,
    recents: List<Long>,
    eraserSize: Float,
    snap: Boolean,
    drawingShapes: Boolean,
    shapeKind: ShapeKind,
    leftHanded: Boolean,
    onSlotChange: (PenSlot) -> Unit,
    onEraserSize: (Float) -> Unit,
    onShapeMode: (ShapeMode) -> Unit,
    onShapeKind: (ShapeKind) -> Unit,
    onCustomColor: () -> Unit,
    onHanded: () -> Unit,
) {
    val y = Yantra.colors
    Column(
        Modifier
            // Clear of the undo pair, which shares this edge and must never be covered — those are
            // the two keys reached for mid-stroke, and a panel sitting on them is a panel that
            // costs you the thing you opened it to fix.
            .padding(bottom = 78.dp)
            .width(232.dp)
            .background(y.cardBg, RoundedCornerShape(22.dp))
            .border(1.dp, y.tileBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (panel) {
            is KitPanel.Slot -> {
                if (slot == null) return@Column
                SectionLabel(slot.label)
                WidthRow(slot.width, 1f..24f) { onSlotChange(slot.copy(width = it)) }
                Text("Ink", fontSize = 11.sp, color = y.textMuted, modifier = Modifier.padding(top = 6.dp))
                InkSwatches(
                    current = slot.color,
                    recents = recents,
                    onPick = { onSlotChange(slot.copy(color = it)) },
                    onCustom = onCustomColor,
                )
            }
            KitPanel.Eraser -> {
                SectionLabel("Eraser")
                WidthRow(eraserSize, 12f..64f, onEraserSize)
            }
            KitPanel.Shape -> {
                SectionLabel("Shapes")
                // Two jobs, said plainly, with off in the same row so it is never missing.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("Off", selected = !snap && !drawingShapes, size = ChipSize.Small) {
                        onShapeMode(ShapeMode.OFF)
                    }
                    SelectChip("Recognise", selected = snap, size = ChipSize.Small) {
                        onShapeMode(ShapeMode.RECOGNISE)
                    }
                    SelectChip("Draw", selected = drawingShapes, size = ChipSize.Small) {
                        onShapeMode(ShapeMode.DRAW)
                    }
                }
                Text(
                    if (drawingShapes) "Drag to place the shape."
                    else "Draw freehand; it settles into a shape when you lift.",
                    fontSize = 11.5.sp,
                    color = y.textDim,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (drawingShapes) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        SHAPE_NAMES.forEach { (kind, label) ->
                            SelectChip(label, selected = shapeKind == kind, size = ChipSize.Small) {
                                onShapeKind(kind)
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onHanded)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (leftHanded) "Kit on the left" else "Kit on the right",
                fontSize = 12.5.sp,
                color = y.textMuted,
                modifier = Modifier.weight(1f),
            )
            Text("Swap", fontSize = 12.5.sp, fontWeight = FontWeight.W700, color = y.accentText)
        }
    }
}

/** A width, shown as the line it draws rather than as a number nobody can picture. */
@Composable
private fun WidthRow(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val y = Yantra.colors
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        drawLine(
            color = y.textSecondary,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = value.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = y.accent,
            activeTrackColor = y.accent,
            inactiveTrackColor = y.tileWarm,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InkSwatches(
    current: Long,
    recents: List<Long>,
    onPick: (Long) -> Unit,
    onCustom: () -> Unit,
) {
    val y = Yantra.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        (inkPresets(y.accent) + recents).distinct().take(8).forEach { c ->
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(c.toInt()))
                    .then(if (c == current) Modifier.border(2.dp, y.accent, CircleShape) else Modifier)
                    .clickable { onPick(c) },
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .border(1.dp, y.tileBorder, CircleShape)
                .clickable(onClick = onCustom),
            contentAlignment = Alignment.Center,
        ) { Text("+", fontSize = 14.sp, color = y.textMuted) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerSheet(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val y = Yantra.colors
    val hsv = remember {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toInt(), out)
        out
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val current = Color.hsv(hue, sat, value)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = y.railBg) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Custom color", fontSize = 16.sp, fontWeight = FontWeight.W800, color = y.textPrimary)

            // saturation / value square
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    .pointerInput(Unit) {
                        fun set(o: Offset) {
                            sat = (o.x / size.width).coerceIn(0f, 1f)
                            value = (1f - o.y / size.height).coerceIn(0f, 1f)
                        }
                        detectTapGestures { set(it) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            sat = (change.position.x / size.width).coerceIn(0f, 1f)
                            value = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        }
                    },
            )

            // hue slider
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            (0..6).map { Color.hsv(it * 60f, 1f, 1f) }
                        )
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { hue = (it.x / size.width).coerceIn(0f, 1f) * 360f }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ -> hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f }
                    },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(current, RoundedCornerShape(10.dp)).border(1.dp, y.tileBorder, RoundedCornerShape(10.dp)))
                Spacer(Modifier.weight(1f))
                YantraButton(
                    label = "Use colour",
                    tone = ButtonTone.Soft,
                    onClick = { onPick(current.toArgb().toLong() and 0xFFFFFFFFL) },
                )
            }
        }
    }
}

/**
 * What a lasso'd group can do.
 *
 * Short on purpose. Moving is the thing you actually want from a selection and it is not a button —
 * you drag the strokes, which is why the bar says so rather than offering a control for it. Delete
 * is the one action that needs a target to press.
 *
 * There was a "To task" here, from the handoff, where it sat beside a "To text" that recognised the
 * writing and named the task. Without recognition it could only mint an untitled task and hide the
 * strokes a page below it, so pressing it looked like nothing had happened — it had simply happened
 * somewhere you were not.
 */
@Composable
private fun BoxScope.SelectionBar(
    count: Int,
    at: Offset,
    bounds: IntSize,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val y = Yantra.colors
    val density = LocalDensity.current
    Row(
        Modifier
            .align(Alignment.TopStart)
            .offset {
                // Under the catch, then pulled back inside the screen. A bar half off the edge is
                // a bar you cannot press, and the selection it belongs to is often near one.
                val w = with(density) { 320.dp.toPx() }
                val gap = with(density) { 16.dp.toPx() }
                val barH = with(density) { 62.dp.toPx() }
                IntOffset(
                    (at.x - w / 2f).coerceIn(0f, (bounds.width - w).coerceAtLeast(0f)).toInt(),
                    (at.y + gap).coerceIn(0f, (bounds.height - barH).coerceAtLeast(0f)).toInt(),
                )
            }
            .background(y.cardBg, RoundedCornerShape(15.dp))
            .border(1.dp, y.tileBorder, RoundedCornerShape(15.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "$count",
            fontFamily = YantraMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.W700,
            color = y.textDim,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            "drag to move",
            fontSize = 12.sp,
            color = y.textMuted,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        BarAction("Delete", danger = true, onClick = onDelete)
        BarAction("Cancel", onClick = onDismiss)
    }
}

@Composable
private fun BarAction(
    label: String,
    accent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val y = Yantra.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (accent) y.accentFill else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.W700,
            color = when {
                accent -> y.accentText
                danger -> y.overdue
                else -> y.textMuted
            },
        )
    }
}

private val SHAPE_NAMES = listOf(
    ShapeKind.LINE to "Line", ShapeKind.RECTANGLE to "Box",
    ShapeKind.ELLIPSE to "Oval", ShapeKind.ARROW to "Arrow",
)
