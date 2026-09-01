package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ie.napkin.supertasks.domain.RunningTask
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraDisplay
import ie.napkin.supertasks.ui.theme.YantraMono
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.absoluteValue

/**
 * The task you are on, for the few places that draw it.
 *
 * A composition local rather than a parameter threaded through every screen, because this is
 * deliberately the one thing that looks the same everywhere — a row on a smart list, a line on a
 * page, and the bar itself all read the same state, and none of them should be able to be given a
 * different one by whoever wired the screen up.
 *
 * It carries the flow rather than the value so that a per-second tick recomposes only what is
 * actually showing a clock. Handing down the unwrapped state would put every list row in the app on
 * a one-second recomposition loop to render nothing.
 */
val LocalNow = staticCompositionLocalOf<StateFlow<List<RunningTask.Now>>> { MutableStateFlow(emptyList()) }

/** `MM:SS`, or `H:MM:SS` once there is an hour to report. Sessions do run that long. */
fun elapsedLabel(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Which tasks are on the go.
 *
 * Changes only when the set changes — never on the tick. That distinction is the whole reason this
 * exists separately from [ElapsedSlot]: every task row in the app asks this question, and a value
 * that changed every second would put the entire list on a one-second recomposition loop to answer
 * "still not me".
 */
@Composable
fun startedTaskIds(): Set<String> {
    val flow = LocalNow.current
    val ids by remember(flow) {
        flow.map { list -> list.map { it.nodeId }.toSet() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = flow.value.map { it.nodeId }.toSet())
    return ids
}

/**
 * Which task has a clock actually running on it, or null.
 *
 * Distinct from [startedTaskIds], and the distinction matters in the trailing slot: a task you have
 * picked up but not timed has nothing to put there, so its schedule chip must stay. Stable across
 * the tick, for the same reason.
 */
@Composable
fun timingTaskId(): String? {
    val flow = LocalNow.current
    val id by remember(flow) {
        flow.map { list -> list.firstOrNull { it.hasSession }?.nodeId }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = flow.value.firstOrNull { it.hasSession }?.nodeId)
    return id
}

/**
 * The trailing slot on the running row: elapsed time, in the accent.
 *
 * Only while a session is actually running. A task you have merely said you are on has no elapsed
 * time to report — the ring and the wash already say what is true about it, and a clock reading
 * 0:00 would claim a measurement nobody started. This is also what a device that only received the
 * flag through sync shows: the claim travelled, the stopwatch did not.
 */
@Composable
fun ElapsedSlot(nodeId: String, modifier: Modifier = Modifier) {
    val y = Yantra.colors
    val now by LocalNow.current.collectAsStateWithLifecycle()
    val elapsed = now.firstOrNull { it.nodeId == nodeId }?.elapsedSecs ?: return
    Text(
        elapsedLabel(elapsed),
        fontFamily = YantraMono,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.W700,
        color = y.accent,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * The glyph's running state, drawn once and not animated: the neutral bhupura with the ring closed
 * inside it.
 *
 * [YantraCheckbox] is the interactive one, and it carries a swipe, three animatables and a
 * completion choreography. None of that belongs on a bar that is only reporting; this is the same
 * two layers of the design language with nothing behind them.
 */
@Composable
fun RunningGlyph(
    /** The gate. Neutral, like every other frame in the app — structure is not a hue. */
    frameTint: Color,
    /** The ring inside it. The accent, because being on something is your own effort. */
    ringTint: Color,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        // Proportional, not the checkbox's flat 1.6dp. That figure is tuned against a 30dp row
        // glyph; carried onto a 22dp one it is half again as heavy relative to the shape, and the
        // ring thickens into the frame until the two read as one blob rather than a mark inside a
        // gate. Scaling it keeps the drawing the same drawing at any size.
        val stroke = Stroke(s * 1.6f / 30f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(bhupuraPath(s), frameTint, style = stroke)
        val r = s * 7.5f / 28f
        drawPath(
            Path().apply { addOval(Rect(center - Offset(r, r), center + Offset(r, r))) },
            ringTint,
            style = stroke,
        )
    }
}

/**
 * The player: a thin bar at the very bottom holding whatever you are on.
 *
 * Shaped as the header's reflection — full bleed, rounded at the top where the band is rounded at
 * the bottom — so the screen is a sheet of paper held between two folds. Much thinner than the
 * header, because the header names where you are and this only reports what is running.
 *
 * **Two targets, two meanings, and the split is the point.** The button starts an *open* stopwatch
 * right here, because "start counting" is a control you press in passing and it promises nothing
 * about how long. The body opens the focus screen, where you commit to a length. Those are the two
 * instruments [ie.napkin.supertasks.domain.FocusTimer] already has; the player is just the first one
 * finally getting a control of its own instead of a three-step trip through a screen.
 *
 * **Only the running task wears the accent.** The colour law gives it to effort, and a task you have
 * merely picked up is not that yet — paint the whole bar coral and the one task actually counting
 * has nothing left to distinguish it.
 */
@Composable
fun NowPlayer(
    stack: List<RunningTask.Now>,
    onOpen: (RunningTask.Now) -> Unit,
    onToggleClock: (RunningTask.Now) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stack.isEmpty()) return
    val y = Yantra.colors
    val density = LocalDensity.current

    /**
     * Which task is showing, held **by id rather than by position**.
     *
     * The list re-sorts whenever a clock starts or stops — the timed task is dealt to the front —
     * so an index is a pointer into a list that moves underneath it. Held positionally, pressing
     * play reordered the stack and slot 2 quietly became a different task: the button then acted on
     * whatever had slid under it, which is how a press meant to start one task stopped another.
     * An id cannot drift. If the task leaves the stack entirely, the front of it is the honest
     * fallback.
     */
    var selected by remember { mutableStateOf<String?>(null) }
    val index = stack.indexOfFirst { it.nodeId == selected }.takeIf { it >= 0 } ?: 0
    val current = stack[index]
    val live = current.hasSession

    // Live drag offset, read only inside graphicsLayer — a draw-phase read, so swiping the player
    // relayouts nothing and recomposes nothing.
    var dragX by remember { mutableFloatStateOf(0f) }
    var barW by remember { mutableIntStateOf(0) }
    val commit = with(density) { 56.dp.toPx() }

    val dragState = rememberDraggableState { delta ->
        dragX = (dragX + delta).coerceIn(-commit * 1.8f, commit * 1.8f)
    }
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)

    Row(
        modifier
            .fillMaxWidth()
            .onSizeChanged { barW = it.width }
            .background(if (live) y.accent else y.band, shape)
            .then(
                if (stack.size < 2) Modifier else Modifier.draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        // A flick counts even if it did not travel far — waiting for the full
                        // distance makes a bar feel stuck to anyone who flicks rather than drags.
                        val fwd = dragX <= -commit || velocity <= -700f
                        val back = dragX >= commit || velocity >= 700f
                        val out = (barW.takeIf { it > 0 } ?: 1000).toFloat()
                        if (fwd || back) {
                            // Out the way it was going, then the next one in from the other side.
                            // Two halves of one movement rather than a jump: the bar never shows a
                            // card arriving at a position it did not travel to.
                            animate(
                                dragX, if (fwd) -out else out, initialVelocity = velocity,
                                animationSpec = tween(130, easing = LinearOutSlowInEasing),
                            ) { v, _ -> dragX = v }
                            val next = ((index + if (fwd) 1 else -1) % stack.size + stack.size) % stack.size
                            selected = stack[next].nodeId
                            dragX = if (fwd) out else -out
                            animate(
                                dragX, 0f,
                                animationSpec = tween(170, easing = FastOutSlowInEasing),
                            ) { v, _ -> dragX = v }
                        } else {
                            animate(
                                dragX, 0f, initialVelocity = velocity,
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = 900f),
                            ) { v, _ -> dragX = v }
                        }
                    },
                )
            )
            .padding(start = 18.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .graphicsLayer {
                    translationX = dragX
                    alpha = 1f - (dragX.absoluteValue / (commit * 2.4f)).coerceIn(0f, 0.85f)
                }
                .clickable(onClick = { onOpen(current) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RunningGlyph(
                frameTint = if (live) y.onAccent else y.checkOutline,
                ringTint = if (live) y.onAccent else y.accent,
                size = 20.dp,
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    current.title.ifBlank { "Untitled" },
                    fontFamily = YantraDisplay,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.W700,
                    color = if (live) y.onAccent else y.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(current.elapsedSecs?.let { "RUNNING · ${elapsedLabel(it)}" } ?: "ON THE GO")
                        if (stack.size > 1) append("  ·  ${index + 1}/${stack.size}")
                    },
                    fontFamily = YantraMono,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.2.sp,
                    color = if (live) y.onAccent.copy(alpha = 0.78f) else y.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TransportKey(live = live, onClick = { onToggleClock(current) })
    }
}

/**
 * The one control: play, or stop.
 *
 * A filled triangle and a filled square, drawn rather than iconised — they are two of the most
 * recognisable shapes there are, and the icon set's versions arrive with their own padding and
 * optical centre that would not agree with a 20dp glyph sitting beside them.
 */
@Composable
private fun TransportKey(live: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    val tint = if (live) y.onAccent else y.accent
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (live) y.onAccent.copy(alpha = 0.16f) else y.accentFill)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (live) "Stop the clock" else "Start the clock" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            if (live) {
                drawRoundRect(tint, cornerRadius = CornerRadius(size.minDimension * 0.16f))
            } else {
                val w = size.width
                val h = size.height
                // Nudged right so the triangle looks centred rather than measuring centred.
                drawPath(
                    Path().apply {
                        moveTo(w * 0.12f, 0f)
                        lineTo(w, h / 2f)
                        lineTo(w * 0.12f, h)
                        close()
                    },
                    tint,
                )
            }
        }
    }
}

/**
 * The bottom of a screen that can capture: the field, and under it the player when something is on
 * the go.
 *
 * **Capture is always open.** It used to hide behind a key whenever anything was running, which made
 * the bottom of the screen mean two different things depending on state, and put a tap in front of
 * the highest-frequency action in the app for no reason but that something else wanted the space.
 * Writing something down should never cost a mode.
 *
 * The player sits *below* the field, at the very edge — it is the outermost thing, the reflection of
 * the header at the other end of the sheet, and the field stays where the thumb already expects it.
 *
 * **Both stand down while the keyboard is up.** A bar over the line you are typing is worse than no
 * bar: what is running is a thing you can check in a moment, and what you are writing is a thing you
 * lose. A running session stays visible on the lock screen and in the widget meanwhile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BottomBar(
    onOpenNow: (RunningTask.Now) -> Unit,
    onToggleClock: (RunningTask.Now) -> Unit,
    modifier: Modifier = Modifier,
    /** False where the screen has no business showing it — a task's own page. */
    showNow: Boolean = true,
    capture: @Composable (bottomPadding: Dp) -> Unit,
) {
    val stack by LocalNow.current.collectAsStateWithLifecycle()
    val shown = if (showNow && !WindowInsets.isImeVisible) stack else emptyList()
    Column(modifier.fillMaxWidth()) {
        // The field keeps its own breathing room at the screen edge, and gives most of it back when
        // the player is underneath to catch it.
        capture(if (shown.isEmpty()) 22.dp else 10.dp)
        if (shown.isNotEmpty()) {
            NowPlayer(stack = shown, onOpen = onOpenNow, onToggleClock = onToggleClock)
        }
    }
}

/**
 * The offer made when you start a focus while one is already running.
 *
 * This is the one exclusivity the app has left. Several tasks can be on the go at once — that is
 * what the deck is for — but a focus session measures attention, and there is one of that. So a
 * second start has to take the clock from the first, which closes that session as interrupted in a
 * ledger someone will read later. Doing it silently is how a day's record ends up holding sessions
 * the person does not remember ending.
 *
 * Nothing is offered here except switching. A second clock is not on the table, which is the point.
 */
@Composable
fun SwitchHereDialog(
    runningTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val y = Yantra.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A focus is already running") },
        text = {
            Text(
                "“${runningTitle.ifBlank { "Untitled" }}” has the clock. Starting this one stops " +
                    "that session — the time it has already taken still counts.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "SWITCH HERE",
                    fontFamily = YantraMono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.4.sp,
                    color = y.accent,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Leave it running") } },
    )
}
