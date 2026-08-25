package ie.napkin.supertasks.ui.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import ie.napkin.supertasks.data.ink.ShapeKind
import ie.napkin.supertasks.data.ink.StrokeCodec
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.ChipSize
import ie.napkin.supertasks.ui.components.SelectChip
import ie.napkin.supertasks.ui.components.ButtonTone
import ie.napkin.supertasks.ui.components.YantraButton
import ie.napkin.supertasks.ui.theme.Yantra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
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

    private val unsaved: Boolean get() = edits != savedAt

    val node: StateFlow<NodeEntity?> =
        nodes.observe(nodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val strokes: StateFlow<List<StrokeItem>> =
        held.map { list -> list.map { it.item } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Follow the file, but only while the screen has nothing of its own. Once something is drawn
        // the screen is ahead of disk, and adopting disk would rub it out.
        viewModelScope.launch {
            ink.strokes(nodeId).collect { rows ->
                if (unsaved) return@collect
                held.value = withContext(Dispatchers.Default) {
                    rows.mapNotNull { row ->
                        runCatching { Held(StrokeItem(row.id, StrokeCodec.decode(row.data)), row.data) }
                            .getOrNull()
                    }
                }
            }
        }
    }

    fun save(stroke: Stroke, family: String) {
        val data = StrokeCodec.encode(stroke, family)
        // A local id, because this stroke has no place in the file yet. The eraser works off the
        // list the screen is holding, so it never needs the positional id the index would give it.
        held.update { it + Held(StrokeItem("live-${live++}", stroke), data) }
        touch()
    }

    fun erase(id: String) {
        held.update { list -> list.filterNot { it.item.id == id } }
        touch()
    }

    fun undo() {
        held.update { it.dropLast(1) }
        touch()
    }

    fun clear() {
        held.value = emptyList()
        touch()
    }

    fun rename(title: String) { viewModelScope.launch { nodes.rename(nodeId, title) } }

    /** Writes now rather than waiting out the pause — the app going to the background. */
    fun flushNow() {
        flush?.cancel()
        if (unsaved) write(appScope)
    }

    private fun touch() {
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

enum class InkTool { PEN, MARKER, HIGHLIGHTER, SHAPES, ERASER }

private val Presets = listOf(
    0xFFE06A43L, // coral
    0xFF6FA8E4L, // blue
    0xFF4E9478L, // green
    0xFFE0A83EL, // amber
    0xFFC56A94L, // pink
    0xFF8B6BA8L, // purple
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkScreen(nav: NavHostController, nodeId: String) {
    val vm: InkViewModel = viewModel(key = "ink-$nodeId") { InkViewModel(container(), nodeId) }
    val strokes by vm.strokes.collectAsStateWithLifecycle()
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

    var tool by remember { mutableStateOf(InkTool.PEN) }
    var penColor by remember(dark) { mutableLongStateOf(InkTheme.defaultPen(dark)) }
    var markerColor by remember { mutableLongStateOf(0xFFE06A43L) }
    var hlColor by remember { mutableLongStateOf(0xFFE0A83EL) }
    var shapeColor by remember(dark) { mutableLongStateOf(InkTheme.defaultPen(dark)) }
    var penSize by remember { mutableFloatStateOf(4f) }
    var markerSize by remember { mutableFloatStateOf(10f) }
    var hlSize by remember { mutableFloatStateOf(7f) }
    var shapeSize by remember { mutableFloatStateOf(4f) }
    var eraserSize by remember { mutableFloatStateOf(22f) }
    var shapeKind by remember { mutableStateOf(ShapeKind.LINE) }
    var snap by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(listOf<Long>()) }
    var colorSheet by remember { mutableStateOf(false) }

    var page by remember { mutableStateOf(1) }
    var pageCount by remember { mutableStateOf(1) }
    var stylusMode by remember { mutableStateOf(false) }
    var canvasRef by remember { mutableStateOf<InkCanvas?>(null) }

    fun familyName() = when (tool) {
        InkTool.MARKER -> StrokeCodec.FAMILY_MARKER
        InkTool.HIGHLIGHTER -> StrokeCodec.FAMILY_HIGHLIGHTER
        else -> StrokeCodec.FAMILY_PRESSURE_PEN // pen + shapes
    }
    fun curColor() = when (tool) {
        InkTool.PEN -> penColor; InkTool.MARKER -> markerColor
        InkTool.HIGHLIGHTER -> hlColor; InkTool.SHAPES -> shapeColor; InkTool.ERASER -> penColor
    }
    fun setCurColor(c: Long) {
        when (tool) {
            InkTool.PEN -> penColor = c; InkTool.MARKER -> markerColor = c
            InkTool.HIGHLIGHTER -> hlColor = c; InkTool.SHAPES -> shapeColor = c; InkTool.ERASER -> {}
        }
        recents = (listOf(c) + recents).distinct().take(6)
    }
    fun curSize() = when (tool) {
        InkTool.PEN -> penSize; InkTool.MARKER -> markerSize; InkTool.HIGHLIGHTER -> hlSize
        InkTool.SHAPES -> shapeSize; InkTool.ERASER -> eraserSize
    }
    fun setCurSize(s: Float) {
        when (tool) {
            InkTool.PEN -> penSize = s; InkTool.MARKER -> markerSize = s; InkTool.HIGHLIGHTER -> hlSize = s
            InkTool.SHAPES -> shapeSize = s; InkTool.ERASER -> eraserSize = s
        }
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
                        if (title.isEmpty()) Text("Untitled sketch", fontSize = 17.sp, fontWeight = FontWeight.W700, color = y.textMuted.copy(alpha = 0.6f))
                        inner()
                    }
                },
            )
            Text(
                if (stylusMode) "Pen draws" else "1 finger draws",
                fontSize = 11.sp, color = y.textDim,
            )
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                InkCanvas(ctx).apply {
                    onViewportChanged = { p, c -> page = p; pageCount = c }
                    onStylusModeChanged = { stylusMode = it }
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
                canvas.tool = when (tool) {
                    InkTool.SHAPES -> EditorTool.SHAPE
                    InkTool.ERASER -> EditorTool.ERASE
                    else -> EditorTool.DRAW
                }
                canvas.shapeKind = shapeKind
                canvas.recognizeShapes = snap && (tool == InkTool.PEN || tool == InkTool.MARKER)
                canvas.eraserRadius = with(density) { eraserSize.dp.toPx() }
                val f = familyName(); val c = curColor(); val s = curSize()
                canvas.brushProvider = {
                    StrokeCodec.brush(f, c, if (tool == InkTool.HIGHLIGHTER) s * 3f else s)
                }
                canvas.onStrokeFinished = { stroke -> vm.save(stroke, f) }
                canvas.onErase = { id -> vm.erase(id) }
                canvas.setStrokeItems(InkTheme.displayItems(strokes, dark))
            },
        )

        ToolTray(
            tool = tool, onTool = { tool = it },
            color = curColor(), onColor = { setCurColor(it) }, onCustomColor = { colorSheet = true },
            recents = recents,
            size = curSize(), sizeRange = if (tool == InkTool.ERASER) 12f..64f else 1f..24f, onSize = { setCurSize(it) },
            shapeKind = shapeKind, onShapeKind = { shapeKind = it },
            snap = snap, onSnap = { snap = it },
            page = page, pageCount = pageCount,
            onUndo = vm::undo, onClear = vm::clear, onNextPage = { canvasRef?.scrollByPages(1) },
        )
    }

    if (colorSheet) {
        ColorPickerSheet(
            initial = curColor(),
            onDismiss = { colorSheet = false },
            onPick = { setCurColor(it); colorSheet = false },
        )
    }
}

@Composable
private fun ToolTray(
    tool: InkTool, onTool: (InkTool) -> Unit,
    color: Long, onColor: (Long) -> Unit, onCustomColor: () -> Unit, recents: List<Long>,
    size: Float, sizeRange: ClosedFloatingPointRange<Float>, onSize: (Float) -> Unit,
    shapeKind: ShapeKind, onShapeKind: (ShapeKind) -> Unit,
    snap: Boolean, onSnap: (Boolean) -> Unit,
    page: Int, pageCount: Int,
    onUndo: () -> Unit, onClear: () -> Unit, onNextPage: () -> Unit,
) {
    val y = Yantra.colors
    val trayBg = y.railBg
    Column(
        Modifier.fillMaxWidth().background(trayBg).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // tools + actions
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InkTool.entries.forEach { t ->
                    SelectChip(labelFor(t), selected = tool == t, size = ChipSize.Small) { onTool(t) }
                }
            }
            TrayIconBtn(onUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = y.textSecondary, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.width(6.dp))
            TrayIconBtn(onClear) { Icon(Icons.Default.DeleteOutline, "Clear", tint = y.textSecondary, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.background(y.tileWarm2, RoundedCornerShape(10.dp)).clickable(enabled = page < pageCount, onClick = onNextPage)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) { Text("$page/$pageCount", fontSize = 11.sp, fontWeight = FontWeight.W700, color = y.textMuted) }
        }

        // contextual settings
        if (tool == InkTool.SHAPES) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shapeLabels.forEach { (k, label) -> SelectChip(label, selected = shapeKind == k, size = ChipSize.Small) { onShapeKind(k) } }
            }
        }
        if (tool != InkTool.ERASER) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Presets.forEach { c -> Swatch(Color(c), selected = color == c) { onColor(c) } }
                recents.filter { it !in Presets }.take(2).forEach { c -> Swatch(Color(c), selected = color == c) { onColor(c) } }
                // custom color opener
                Box(
                    Modifier.size(26.dp).border(1.dp, y.textMuted, CircleShape).clickable(onClick = onCustomColor),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, "Custom color", tint = y.textMuted, modifier = Modifier.size(15.dp)) }
            }
        }

        // size + (snap for freehand)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (tool == InkTool.ERASER) "Eraser" else "Size", fontSize = 11.sp, color = y.textMuted, modifier = Modifier.width(52.dp))
            Slider(
                value = size, onValueChange = onSize, valueRange = sizeRange,
                colors = SliderDefaults.colors(thumbColor = y.accent, activeTrackColor = y.accent, inactiveTrackColor = y.tileWarm),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text("${size.roundToInt()}", fontSize = 11.sp, color = y.textDim, modifier = Modifier.width(24.dp))
        }
        if (tool == InkTool.PEN || tool == InkTool.MARKER) {
            SelectChip(if (snap) "Snap to shapes: on" else "Snap to shapes: off", selected = snap, size = ChipSize.Small) { onSnap(!snap) }
        }
    }
}

private fun labelFor(t: InkTool) = when (t) {
    InkTool.PEN -> "Pen"; InkTool.MARKER -> "Marker"; InkTool.HIGHLIGHTER -> "Highlighter"
    InkTool.SHAPES -> "Shapes"; InkTool.ERASER -> "Eraser"
}

private val shapeLabels = listOf(
    ShapeKind.LINE to "Line", ShapeKind.RECTANGLE to "Box",
    ShapeKind.ELLIPSE to "Oval", ShapeKind.ARROW to "Arrow",
)


@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (selected) 28.dp else 26.dp)
            .then(if (selected) Modifier.border(2.dp, color, CircleShape) else Modifier)
            .padding(if (selected) 3.dp else 0.dp)
            .background(color, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun TrayIconBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    val y = Yantra.colors
    Box(
        Modifier.size(32.dp).background(y.tileWarm2, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
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
