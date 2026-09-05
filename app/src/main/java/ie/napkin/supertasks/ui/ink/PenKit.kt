package ie.napkin.supertasks.ui.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.data.ink.StrokeCodec
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraMono

/**
 * The three pens the kit holds.
 *
 * Nobody browses a tool library mid-sentence. People keep two or three pens they trust and switch
 * between them without looking, which is what a physical kit is — so this is a kit and not a
 * palette: three slots, always in the same place, each drawn as the mark it makes.
 *
 * **A slot is the only place a width and a colour live.** There is no separate size control and no
 * separate swatch row, because every one of those is a decision taken away from the page and made
 * in a panel instead. Change what a slot holds by holding it.
 */
data class PenSlot(
    val label: String,
    val family: String,
    val color: Long,
    val width: Float,
)

/** What a touch on the canvas currently does. */
enum class InkMode {
    /** The pen in hand. Freehand, and snapped to a shape on lift if recognition is on. */
    DRAW,
    ERASE,
    LASSO,

    /** Dragging out a shape directly — a line, a box, an oval, an arrow. */
    SHAPE,
}

/**
 * Which set of controls the kit has opened, if any.
 *
 * One panel at a time, and it belongs to the thing you tapped. Every tool that has a setting keeps
 * it here rather than in a tray of its own: the eraser's width sits behind the eraser, the shape
 * kinds behind the shape key, and nothing has to be found somewhere it is not.
 */
sealed interface KitPanel {
    /** A pen's own width and ink. */
    data class Slot(val index: Int) : KitPanel
    data object Eraser : KitPanel

    /** The two things the shape key can be doing, and which shape when it is drawing them. */
    data object Shape : KitPanel
}

/**
 * The kit's defaults: a pen and a translucent marker.
 *
 * Two, not three. The handoff's kit held FINE and BOLD as separate slots, which is right for a kit
 * whose slots have fixed widths — but a slot here owns its width and you can change it in two taps,
 * so a second pen that differs only by being thicker is a slot spent on a setting. What the marker
 * offers that a wide pen does not is translucent ink that layers, and that is worth a slot.
 */
fun defaultSlots(inkColor: Long): List<PenSlot> = listOf(
    PenSlot("PEN", StrokeCodec.FAMILY_PRESSURE_PEN, inkColor, 2.6f),
    // The handoff draws this one in teal. Teal is not one of the app's five drawing colours, and
    // adding a sixth to match a swatch would contradict the rule that the ink palette is closed —
    // so it opens on the amber that was already the highlighter's default, and the slot can be held
    // and changed to anything the palette offers.
    PenSlot("MARK", StrokeCodec.FAMILY_HIGHLIGHTER, 0xFFE0A83EL, 9f),
)

/**
 * The kit itself: three slots, a divider, then the tools that are not pens.
 *
 * Fixed to one corner and never moved. [dimmed] fades it while the pen is down so the page can be
 * seen through it, but it stays exactly where it was — the whole point of a kit is that your hand
 * knows where it is without looking, and a control that moves out of the way has to be found again
 * on the way back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PenKit(
    slots: List<PenSlot>,
    active: Int,
    mode: InkMode,
    snap: Boolean,
    dimmed: Boolean,
    folded: Boolean,
    onFold: () -> Unit,
    onSlot: (Int) -> Unit,
    onMode: (InkMode) -> Unit,
    onSnap: () -> Unit,
    panel: KitPanel?,
    onPanel: (KitPanel?) -> Unit,
    leftHanded: Boolean,
    controls: @Composable (KitPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val y = Yantra.colors
    // The panel opens *beside* the kit, on the side away from the hand, so it never covers the
    // slots you are switching between while it is open.
    Row(
        modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!leftHanded) panel?.let { controls(it) }
        KitColumn(
            slots = slots, active = active, mode = mode, snap = snap, dimmed = dimmed,
            folded = folded, onFold = onFold, onSlot = onSlot, onMode = onMode, onSnap = onSnap,
            panel = panel, onPanel = onPanel,
        )
        if (leftHanded) panel?.let { controls(it) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KitColumn(
    slots: List<PenSlot>,
    active: Int,
    mode: InkMode,
    snap: Boolean,
    dimmed: Boolean,
    folded: Boolean,
    onFold: () -> Unit,
    onSlot: (Int) -> Unit,
    onMode: (InkMode) -> Unit,
    onSnap: () -> Unit,
    panel: KitPanel?,
    onPanel: (KitPanel?) -> Unit,
) {
    val y = Yantra.colors
    Column(
        Modifier
            .alpha(if (dimmed) 0.55f else 1f)
            .background(y.cardBg, RoundedCornerShape(26.dp))
            .border(1.dp, y.tileBorder, RoundedCornerShape(26.dp))
            .padding(horizontal = 9.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Folds the kit down to the pen it is holding.
        //
        // Three slots and three tools is a tall column, and on a phone it runs a third of the way
        // up the right edge of the page you are drawing on. Folded, the kit is the one slot that
        // is currently in your hand — still in the same corner, still one tap from everything, and
        // no longer standing on the drawing.
        Box(
            Modifier
                .size(width = 56.dp, height = 26.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onFold),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (folded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (folded) "Show the whole kit" else "Fold the kit",
                tint = y.textDim,
                modifier = Modifier.size(20.dp),
            )
        }

        // Folded, only the pen in hand is drawn. It is still a slot and still switches on a tap —
        // the kit has not become a different control, it has become a shorter one.
        val shown = if (folded) listOf(active) else slots.indices.toList()
        shown.forEach { i ->
            val slot = slots[i]
            val on = mode == InkMode.DRAW && i == active
            Column(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (on) y.accentFill else Color.Transparent)
                    .then(
                        if (on) Modifier.border(1.5.dp, y.accent, RoundedCornerShape(15.dp))
                        else Modifier
                    )
                    .combinedClickable(
                        // First tap picks the pen up. Tapping the one already in your hand opens
                        // what it holds — which is where anyone reaches for it, and is what every
                        // drawing app with a kit does. Holding still works for people who learned
                        // it that way.
                        onClick = {
                            if (on) onPanel(if (panel == KitPanel.Slot(i)) null else KitPanel.Slot(i))
                            else { onSlot(i); onPanel(null) }
                        },
                        onLongClick = { onPanel(KitPanel.Slot(i)) },
                        onLongClickLabel = "Change what ${slot.label} holds",
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SlotStroke(slot, Modifier.size(width = 34.dp, height = 18.dp))
                Text(
                    slot.label,
                    fontFamily = YantraMono,
                    fontSize = 8.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.W700,
                    color = if (on) y.accentText else y.textMuted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }

        if (!folded) {
        Box(Modifier.width(34.dp).height(1.dp).background(y.hairline))

        KitTool(
            glyph = { tint -> LassoGlyph(tint) },
            label = "Lasso",
            on = mode == InkMode.LASSO,
            // The lasso has nothing to set: it is a gesture, and what it catches is the setting.
            onClick = {
                onPanel(null)
                onMode(if (mode == InkMode.LASSO) InkMode.DRAW else InkMode.LASSO)
            },
        )
        KitTool(
            icon = Icons.Default.Category,
            label = when {
                mode == InkMode.SHAPE -> "Drawing shapes"
                snap -> "Shape snapping on"
                else -> "Shapes"
            },
            on = snap || mode == InkMode.SHAPE,
            // The key has two jobs and the panel is where you say which: recognise what you drew,
            // or draw the shape directly. Same two taps as a pen — the first turns it on, the
            // second opens what it is doing — and the panel carries the off switch, which is the
            // thing a bare toggle lost when it briefly grew a panel with no way back.
            onClick = {
                if (snap || mode == InkMode.SHAPE) {
                    onPanel(if (panel == KitPanel.Shape) null else KitPanel.Shape)
                } else {
                    onSnap()
                    onPanel(null)
                }
            },
        )
        // Not in the handoff's kit, which makes erasing a pen-barrel gesture. Kept because that
        // gesture needs a stylus with a button, and this app runs on phones held in one hand with
        // no pen anywhere near them — removing it would take erasing away from most of the people
        // who have it now.
        KitTool(
            glyph = { tint -> EraserGlyph(tint) },
            label = "Eraser",
            on = mode == InkMode.ERASE,
            onClick = {
                if (mode == InkMode.ERASE) onPanel(if (panel == KitPanel.Eraser) null else KitPanel.Eraser)
                else { onMode(InkMode.ERASE); onPanel(null) }
            },
        )
        }
    }
}

/** A loop with a tail — what the gesture looks like, which is what the tool is. */
@Composable
private fun LassoGlyph(tint: Color, modifier: Modifier = Modifier.size(21.dp)) {
    Canvas(modifier) {
        val w = size.width
        val stroke = Stroke(
            width = w * 0.085f,
            cap = StrokeCap.Round,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(w * 0.13f, w * 0.10f),
            ),
        )
        drawOval(
            color = tint,
            topLeft = Offset(w * 0.10f, w * 0.10f),
            size = androidx.compose.ui.geometry.Size(w * 0.80f, w * 0.62f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.50f, w * 0.72f),
            end = Offset(w * 0.62f, w * 0.92f),
            strokeWidth = w * 0.085f,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * An eraser, drawn.
 *
 * It was a backspace key borrowed from the icon set, which is a key that deletes the character
 * behind a caret — nothing on this screen has a caret, and the one person who asked what it was
 * guessed it was a fold control. The app draws its own marks everywhere else it needs one that
 * means something specific; this is one of those.
 *
 * A tilted block with a band across it: the rubber and the sleeve, which is what an eraser looks
 * like to anyone who has held one.
 */
@Composable
private fun EraserGlyph(tint: Color, modifier: Modifier = Modifier.size(21.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        rotate(-32f) {
            val body = androidx.compose.ui.geometry.Rect(
                left = w * 0.20f, top = h * 0.30f, right = w * 0.80f, bottom = h * 0.78f,
            )
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(body.left, body.top),
                size = androidx.compose.ui.geometry.Size(body.width, body.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
                style = Stroke(width = w * 0.085f),
            )
            // The sleeve: the line that makes it an eraser rather than a rounded rectangle.
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(body.left, body.top + body.height * 0.55f),
                end = androidx.compose.ui.geometry.Offset(body.right, body.top + body.height * 0.55f),
                strokeWidth = w * 0.085f,
            )
        }
    }
}

/** A slot drawn as the stroke it makes — the label is a name, the drawing is the answer. */
@Composable
private fun SlotStroke(slot: PenSlot, modifier: Modifier) {
    val color = Color(slot.color.toInt())
    val translucent = slot.family == StrokeCodec.FAMILY_HIGHLIGHTER
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.72f)
            cubicTo(
                size.width * 0.30f, size.height * 0.05f,
                size.width * 0.62f, size.height * 0.98f,
                size.width, size.height * 0.24f,
            )
        }
        drawPath(
            path,
            color = color,
            alpha = if (translucent) 0.4f else 1f,
            // The width is the slot's own, in the same units the brush uses, so the swatch is a
            // sample of the stroke rather than a picture of one.
            style = Stroke(width = slot.width.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun KitTool(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    glyph: (@Composable (Color) -> Unit)? = null,
) {
    val y = Yantra.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) y.accentFill else Color.Transparent)
            .then(if (on) Modifier.border(1.5.dp, y.accent, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onClick)
            // A drawn glyph carries no description of its own, unlike an Icon — so the key says
            // what it is here, once, however it happens to be painted. Without this the two tools
            // the app draws itself were unreachable by anything that reads the screen.
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (on) y.accent else y.textMuted
        if (glyph != null) glyph(tint)
        else Icon(icon!!, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/**
 * Undo and redo, and the one rule about them: **they never fade.**
 *
 * Everything else on this screen dims while the pen is down so the page shows through. These two do
 * not, because they are the controls reached for *mid-stroke* — the moment you want undo is the
 * moment you have just drawn something wrong, which is exactly when the rest of the chrome is at
 * its faintest. They also sit on the opposite edge from the kit, out from under the writing hand.
 */
@Composable
fun UndoPair(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val y = Yantra.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UndoKey(Icons.AutoMirrored.Filled.Undo, "Undo", canUndo, onUndo)
        UndoKey(Icons.AutoMirrored.Filled.Redo, "Redo", canRedo, onRedo)
    }
}

@Composable
private fun UndoKey(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(y.cardBg)
            .border(1.dp, y.tileBorder, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) y.textSecondary else y.textDim.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The three states the shape key can be in. */
enum class ShapeMode { OFF, RECOGNISE, DRAW }
