package ie.shoonya.yantra.ui.ink

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.data.ink.ShapeKind
import ie.shoonya.yantra.ui.components.YantraIcon
import ie.shoonya.yantra.ui.components.YantraMark
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import ie.shoonya.yantra.ui.theme.YantraRadius
import ie.shoonya.yantra.ui.theme.YantraType

/**
 * The kit, docked along an edge of a tablet's screen.
 *
 * The floating kit was designed for a phone: one column in a corner, folded out of the way, with a
 * pen's width and ink behind a second tap because there is no room to show them. A tablet has the
 * room, so the dock shows everything at once — undo, the pens, the tools, and the settings of
 * whichever one is in hand. Picking a pen *is* opening it; changing a width is one tap, not two;
 * and nothing opens over the page, because the dock takes its own strip beside it instead.
 *
 * Horizontal along the top or bottom, vertical down either side. Where it sits, and whether it docks
 * at all, is in the menu at its end.
 */
@Composable
fun InkDock(
    edge: KitDock,
    slots: List<PenSlot>,
    active: Int,
    /** What the tool keys show as on — the held tool while the pen's button is down. */
    shownMode: InkMode,
    snap: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSlot: (Int) -> Unit,
    onLasso: () -> Unit,
    onShapes: () -> Unit,
    onEraser: () -> Unit,
    /** The settings of the tool in hand, laid out along the dock: a row, or a column for a rail. */
    controls: @Composable (vertical: Boolean) -> Unit,
    penButton: PenButton?,
    onPenButton: (PenButton) -> Unit,
    onEdge: (KitDock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val y = Yantra.colors
    val vertical = !edge.horizontal
    val menu: @Composable () -> Unit = {
        DockMenu(edge = edge, penButton = penButton, onEdge = onEdge, onPenButton = onPenButton)
    }
    val keys: @Composable () -> Unit = {
        DockKey(YantraMark.Undo, "Undo", enabled = canUndo, onClick = onUndo)
        DockKey(YantraMark.Redo, "Redo", enabled = canRedo, onClick = onRedo)
        DockDivider(vertical = !vertical)
        slots.forEachIndexed { i, slot ->
            DockSlot(slot, on = shownMode == InkMode.DRAW && i == active, onClick = { onSlot(i) })
        }
        DockDivider(vertical = !vertical)
        KitTool(mark = YantraMark.Lasso, label = "Lasso", on = shownMode == InkMode.LASSO, onClick = onLasso)
        KitTool(
            mark = YantraMark.Shapes,
            label = if (shownMode == InkMode.SHAPE) "Drawing shapes" else if (snap) "Shape snapping on" else "Shapes",
            on = snap || shownMode == InkMode.SHAPE,
            onClick = onShapes,
        )
        KitTool(mark = YantraMark.Eraser, label = "Eraser", on = shownMode == InkMode.ERASE, onClick = onEraser)
        DockDivider(vertical = !vertical)
    }

    if (!vertical) {
        // One thin row: the keys, then the settings of the one in hand, scrolling sideways only if
        // a narrow screen runs out — the keys themselves never scroll away.
        Row(
            modifier
                .fillMaxWidth()
                .background(y.cardBg)
                .border(1.dp, y.tileBorder)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            keys()
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) { controls(false) }
            menu()
        }
    } else {
        // A rail, not a sidebar: one column no wider than its keys, so the page keeps nearly all of
        // the width it had.
        Column(
            modifier
                .width(64.dp)
                .fillMaxHeight()
                .background(y.cardBg)
                .border(1.dp, y.tileBorder)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            menu()
            keys()
            controls(true)
        }
    }
}

/**
 * The settings of the tool in hand, as the dock shows them: a few widths to tap and the inks, in a
 * line along the dock. A slider needs length the dock does not have without growing a second row
 * or a sidebar, and a width is picked, not dialled — nobody draws at 7.3.
 */
@Composable
fun DockControls(
    panel: KitPanel?,
    slot: PenSlot?,
    recents: List<Long>,
    eraserSize: Float,
    snap: Boolean,
    drawingShapes: Boolean,
    shapeKind: ShapeKind,
    vertical: Boolean,
    onSlotChange: (PenSlot) -> Unit,
    onEraserSize: (Float) -> Unit,
    onShapeMode: (ShapeMode) -> Unit,
    onShapeKind: (ShapeKind) -> Unit,
    onCustomColor: () -> Unit,
) {
    val y = Yantra.colors
    val line: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (vertical) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
        else Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
    when (panel) {
        null -> Unit
        is KitPanel.Slot -> if (slot != null) line {
            val widths = if (slot.family == ie.shoonya.yantra.data.ink.StrokeCodec.FAMILY_HIGHLIGHTER) HIGHLIGHT_WIDTHS else PEN_WIDTHS
            widths.forEach { w ->
                WidthDot(w, dotSize = (4f + w * 0.8f).coerceAtMost(18f), on = nearest(widths, slot.width) == w) {
                    onSlotChange(slot.copy(width = w))
                }
            }
            DockDivider(vertical = !vertical)
            (inkPresets(y.accent) + recents).distinct().take(8).forEach { c ->
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(c.toInt()))
                        .then(if (c == slot.color) Modifier.border(2.dp, y.accent, CircleShape) else Modifier)
                        .clickable { onSlotChange(slot.copy(color = c)) },
                )
            }
            Box(
                Modifier.size(26.dp).clip(CircleShape).border(1.dp, y.tileBorder, CircleShape).clickable(onClick = onCustomColor),
                contentAlignment = Alignment.Center,
            ) { Text("+", fontSize = YantraType.body, color = y.textMuted) }
        }
        KitPanel.Eraser -> line {
            ERASER_WIDTHS.forEach { w ->
                WidthDot(w, dotSize = w * 0.4f, on = nearest(ERASER_WIDTHS, eraserSize) == w) { onEraserSize(w) }
            }
        }
        KitPanel.Shape -> line {
            DockWord("Off", on = !snap && !drawingShapes) { onShapeMode(ShapeMode.OFF) }
            DockWord("Auto", on = snap) { onShapeMode(ShapeMode.RECOGNISE) }
            DockWord("Draw", on = drawingShapes) { onShapeMode(ShapeMode.DRAW) }
            if (drawingShapes) {
                DockDivider(vertical = !vertical)
                SHAPE_NAMES.forEach { (kind, label) -> DockWord(label, on = shapeKind == kind) { onShapeKind(kind) } }
            }
        }
    }
}

private val PEN_WIDTHS = listOf(1.5f, 2.6f, 5f, 9f)
private val HIGHLIGHT_WIDTHS = listOf(5f, 9f, 14f, 20f)
private val ERASER_WIDTHS = listOf(14f, 22f, 36f, 56f)

private fun nearest(options: List<Float>, value: Float): Float = options.minBy { kotlin.math.abs(it - value) }

/** A width as the dot it makes, lit when it is the one in use. */
@Composable
private fun WidthDot(width: Float, dotSize: Float, on: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(YantraRadius.control))
            .background(if (on) y.accentFill else Color.Transparent)
            .then(if (on) Modifier.border(1.dp, y.accent, RoundedCornerShape(YantraRadius.control)) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Width ${"%.1f".format(width)}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(dotSize.coerceIn(3f, 22f).dp).clip(CircleShape).background(if (on) y.accent else y.textSecondary))
    }
}

/** A short word to tap — Off, Auto, Draw, Line — for the settings that are choices, not sizes. */
@Composable
private fun DockWord(label: String, on: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(YantraRadius.control))
            .background(if (on) y.accentFill else Color.Transparent)
            .then(if (on) Modifier.border(1.dp, y.accent, RoundedCornerShape(YantraRadius.control)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = YantraType.caption, fontWeight = FontWeight.W700, color = if (on) y.accentText else y.textMuted)
    }
}

/** Undo or redo, sized for the dock. They never fade, like the floating pair. */
@Composable
private fun DockKey(mark: YantraMark, label: String, enabled: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(YantraRadius.card))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        YantraIcon(
            mark,
            contentDescription = label,
            tint = if (enabled) y.textSecondary else y.textDim.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A pen in the dock: the stroke it makes and its name, lit when it is the one in hand. */
@Composable
private fun DockSlot(slot: PenSlot, on: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Column(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(YantraRadius.card))
            .background(if (on) y.accentFill else Color.Transparent)
            .then(if (on) Modifier.border(1.5.dp, y.accent, RoundedCornerShape(YantraRadius.card)) else Modifier)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SlotStroke(slot, Modifier.size(width = 30.dp, height = 14.dp))
        Text(
            slot.label,
            fontFamily = YantraMono,
            fontSize = YantraType.dense,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.W700,
            color = if (on) y.accentText else y.textMuted,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun DockDivider(vertical: Boolean) {
    val y = Yantra.colors
    Box(
        if (vertical) Modifier.width(1.dp).height(32.dp).background(y.hairline)
        else Modifier.width(32.dp).height(1.dp).background(y.hairline)
    )
}

/** Where the kit sits, and what the pen's button holds — the dock's own settings, at its end. */
@Composable
private fun DockMenu(
    edge: KitDock,
    penButton: PenButton?,
    onEdge: (KitDock) -> Unit,
    onPenButton: (PenButton) -> Unit,
) {
    val y = Yantra.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(YantraRadius.card))
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            YantraIcon(YantraMark.More, contentDescription = "Where the kit sits", tint = y.textMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            KitDock.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            fontWeight = if (option == edge) FontWeight.W700 else FontWeight.Normal,
                            color = if (option == edge) y.accentText else y.textPrimary,
                        )
                    },
                    onClick = { open = false; onEdge(option) },
                )
            }
            if (penButton != null) {
                PenButton.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Pen button, held: ${option.label}",
                                fontWeight = if (option == penButton) FontWeight.W700 else FontWeight.Normal,
                                color = if (option == penButton) y.accentText else y.textPrimary,
                            )
                        },
                        onClick = { open = false; onPenButton(option) },
                    )
                }
            }
        }
    }
}
