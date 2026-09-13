package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Where a timeline opens. Early enough to catch a morning, late enough to skip the small hours. */
private const val OPEN_AT_HOUR = 7

/** Everything snaps to the quarter hour. */
private const val SNAP = 15

/**
 * How much of each end of a block is a handle rather than somewhere to grab and move it.
 *
 * Capped at a third of the block in [BlockChip], so the middle third is always somewhere to take
 * hold of the whole thing — on a half-hour block two 18dp ends would leave nothing to move.
 */
private val RESIZE_GRIP = 18.dp

/** How long a block stays where a finger left it when the file never catches up. */
private const val SETTLE_MS = 1500L

/**
 * Pinch to change how tall an hour is, keeping the hour between your fingers where it is.
 *
 * **Two fingers or nothing.** Written against the raw pointer stream rather than with
 * `detectTransformGestures`, because that one also reports pan — and pan on a timeline is the
 * vertical scroll this sits inside, so it would fight the scroll on every one-finger drag and steal
 * the gestures that move blocks. Here a single pointer is passed through untouched and the scroll,
 * the long-press drags and the taps all carry on exactly as they were.
 *
 * The **anchor** is the point of it. Zooming without one leaves you somewhere else in the day and
 * having to find Tuesday afternoon again; scaling the scroll offset about the focal point keeps the
 * hour you are looking at under the fingers looking at it.
 */
private fun Modifier.pinchToZoom(
    scroll: ScrollState,
    hourHeight: Dp,
    onHeight: (Dp) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zooming = false
        var last = 1f
        do {
            val event = awaitPointerEvent()
            val pointers = event.changes.filter { it.pressed }
            if (pointers.size < 2) {
                // One finger is somebody else's gesture. Nothing is consumed, so it reaches them.
                if (zooming) break
                continue
            }
            val zoom = event.calculateZoom()
            if (zoom == 0f || zoom.isNaN()) continue
            if (!zooming) {
                // A little slack before claiming the gesture, so a two-finger scroll that never
                // spreads is still a scroll.
                last *= zoom
                if (kotlin.math.abs(last - 1f) < 0.06f) continue
                zooming = true
            }
            val focus = event.calculateCentroid(useCurrent = true)
            val before = hourHeight
            val after = (before * zoom).clampedHourHeight()
            if (after != before) {
                // The minute under the centroid, before and after. Scroll by the difference and it
                // has not moved.
                val ratio = after / before
                val atFocus = scroll.value + focus.y
                val delta = (atFocus * ratio - atFocus).toInt()
                onHeight(after)
                // Dispatched rather than awaited: this is inside a pointer callback, and the scroll
                // has to land on the same frame as the size change or the day flickers.
                scroll.dispatchRawDelta(delta.toFloat())
            }
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

private val RULER_WIDTH = 36.dp

/**
 * What is being done to the timeline right now, wherever the finger is.
 *
 * Hoisted above the lanes rather than held inside one, because a multi-day view is several lanes and
 * only one of them can be under a finger at a time. Held per-lane, two columns could each believe
 * they owned the gesture.
 */
private class Editing {
    var drag by mutableStateOf<DragState?>(null)
    /**
     * Where a finger just left a block, kept until the index says the same thing.
     *
     * Letting go used to hand the block straight back to the data, and the data is a file write and
     * a reindex behind — so for a couple of hundred milliseconds the block sprang back to where it
     * had been and then jumped to where you put it. Measured off a screen recording: exactly the
     * quiet timer, every time, on every gesture.
     */
    var held by mutableStateOf<DragState?>(null)
    /** The block a long press picked out. Its boundaries get handles you can actually hit. */
    var selected by mutableStateOf<String?>(null)
    /** A range being dragged out of bare ruler, and which day's ruler it is. */
    var draft by mutableStateOf<Pair<LocalDate, IntRange>?>(null)
}

/**
 * One day's column: the ruling, what is on it, and every gesture that changes it.
 *
 * **The day view and the multi-day view are this, once or three or seven times.** They used to be
 * two composables, and the second was a picture — no dragging, no tapping out a new event, no way to
 * put a task on a Tuesday without first going to Tuesday. Two implementations of "a day, to scale"
 * is also two places for the arithmetic to drift.
 *
 * A block moves only within its own column. Carrying one across to the next day is a different
 * gesture with a different failure mode and is deliberately not in this pass — CALENDAR_PLAN.md §14.
 */
@Composable
private fun DayLane(
    day: LocalDate,
    laid: TimelineDay,
    editing: Editing,
    compact: Boolean,
    onSpan: (String, LocalDateTime, LocalDateTime) -> Unit,
    onOpen: (DayItem) -> Unit,
    onEmptyTap: (LocalDate, LocalTime) -> Unit,
    onCreateRange: (LocalDateTime, LocalDateTime) -> Unit,
    ghost: IntRange?,
    modifier: Modifier = Modifier,
    onLane: ((LaneMetrics) -> Unit)? = null,
) {
    val hourHeight = LocalHourHeight.current
    val hourPx = with(LocalDensity.current) { hourHeight.toPx() }
    fun at(minute: Int): LocalDateTime = day.atStartOfDay().plusMinutes(minute.toLong())
    fun commit(d: DragState) {
        editing.held = d
        onSpan(d.nodeId, at(d.startMinute), at(d.endMinute))
    }

    BoxWithConstraints(
        modifier.then(
            if (onLane == null) Modifier
            // The node measured here is the whole 24-hour content, translated by the scroll — so a
            // point in its local space is an hour directly, with no scroll arithmetic for the
            // caller to get wrong.
            else Modifier.onGloballyPositioned { onLane(LaneMetrics(it, hourPx)) }
        )
    ) {
        HourGrid()
        // Underneath the blocks, deliberately. Tapping bare ruler makes something there — the
        // quickest way to block out an hour is to point at the hour — but drawn last it would cover
        // the whole column and swallow every tap meant for a block.
        TapTargets(
            // Anywhere off a block puts the selection down again. One that survived a tap on the
            // bare day would be a mode you had to remember you were in.
            onTap = { time -> editing.selected = null; onEmptyTap(day, time) },
            draft = editing.draft?.takeIf { it.first == day }?.second,
            onDraft = { editing.draft = it?.let { r -> day to r } },
            onCommit = { range -> onCreateRange(at(range.first), at(range.last)) },
        )
        NowLine(day)
        laid.blocks.forEach { block ->
            BlockChip(
                block = block,
                laneWidth = maxWidth,
                compact = compact,
                drag = editing.drag,
                held = editing.held,
                onDrag = { editing.drag = it },
                onCommit = ::commit,
                // A hold that goes nowhere is not an edit — it is you pointing at the block. That
                // is the cheapest gesture there is for "this one", and it had no meaning of its own.
                onSelect = { editing.selected = it },
                selected = block.item.nodeId == editing.selected,
                onClick = { onOpen(block.item) },
            )
        }
        // Drawn after every block, so a handle is never underneath one of its neighbours.
        laid.blocks.firstOrNull { it.item.nodeId == editing.selected }?.let { picked ->
            val shown = (editing.drag ?: editing.held)?.takeIf { it.nodeId == picked.item.nodeId }
            val lane = maxWidth / picked.columns
            EdgeHandles(
                centreX = lane * picked.column + lane / 2,
                startMinute = shown?.startMinute ?: picked.startMinute,
                endMinute = shown?.endMinute ?: picked.endMinute,
                block = picked,
                onDrag = { editing.drag = it },
                onCommit = ::commit,
            )
        }
        // The block being drawn by a drag on bare ruler, or by a task on its way in from the rail.
        // Same shape either way: what it will be if you let go here.
        (editing.draft?.takeIf { it.first == day }?.second ?: ghost)?.let { DraftBlock(it) }
    }
}

/**
 * Reality catching up is what ends a hold — or, failing that, a timer, because a write that never
 * arrives must not freeze a block in a position the file does not have.
 */
@Composable
private fun SettleHeld(editing: Editing, blocks: List<TimedBlock>) {
    val landed = editing.held?.let { h -> blocks.firstOrNull { it.item.nodeId == h.nodeId } }
    LaunchedEffect(editing.held, landed?.startMinute, landed?.endMinute) {
        val h = editing.held ?: return@LaunchedEffect
        if (landed != null && landed.startMinute == h.startMinute && landed.endMinute == h.endMinute) {
            editing.held = null
            return@LaunchedEffect
        }
        delay(SETTLE_MS)
        editing.held = null
    }
}

/**
 * A day drawn to scale: an hour ruler with blocks laid over it.
 *
 * Height is duration, so an empty afternoon looks empty — which is the whole reason to draw a
 * timeline rather than a list. Overlapping blocks share the width; see [TimelineLayout] for why the
 * sharing is decided per run of overlaps rather than per pair.
 */
@Composable
fun DayTimeline(
    day: LocalDate,
    items: List<DayItem>,
    onOpen: (DayItem) -> Unit,
    onEmptyTap: (LocalTime) -> Unit,
    /**
     * This block now runs from here to here.
     *
     * One callback for all three grabs rather than a move and a resize, because they are one fact
     * about a block said three ways — and two callbacks are two places for the same rule to drift
     * apart, which is how a resize came to be lost while a move survived.
     */
    onSpan: (String, LocalDateTime, LocalDateTime) -> Unit,
    onCreateRange: (LocalDateTime, LocalDateTime) -> Unit,
    /** Hoisted so a drag coming from outside can scroll the day it is being dragged onto. */
    scroll: ScrollState = rememberScrollState(),
    /**
     * Where the hour lane is, published as it moves.
     *
     * A drag that starts in another composable has no way to ask "which hour is under my finger"
     * without this: the lane is inside a scrolling column, so neither its position nor its scroll
     * offset is something the caller can work out for itself.
     */
    onLane: ((LaneMetrics) -> Unit)? = null,
    /** A block being dragged in from outside, in minutes past midnight. Drawn, not committed. */
    ghost: IntRange? = null,
    /** A pinch asking for a different hour height — CALENDAR_PLAN.md §17. */
    onHourHeight: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hourHeight = LocalHourHeight.current
    val laid = TimelineLayout.forDay(items, day)
    val editing = remember(day) { Editing() }
    SettleHeld(editing, laid.blocks)

    // Open on the working day rather than at midnight, which is eight hours of nothing.
    //
    // Converted through the density: `scrollTo` counts pixels and `hourHeight.value` is a dp
    // number, so passing it raw scrolled to about three in the morning on a 3x screen — and to a
    // different hour on every different screen, which is the tell.
    val density = LocalDensity.current
    LaunchedEffect(day) { scroll.scrollTo(with(density) { (hourHeight * OPEN_AT_HOUR).roundToPx() }) }

    Column(modifier) {
        AllDayBar(laid.allDay, onOpen)
        Row(
            Modifier
                .fillMaxWidth()
                .pinchToZoom(scroll, hourHeight, onHourHeight)
                .verticalScroll(scroll),
        ) {
            HourRuler()
            DayLane(
                day = day,
                laid = laid,
                editing = editing,
                compact = false,
                onSpan = onSpan,
                onOpen = onOpen,
                onEmptyTap = { _, time -> onEmptyTap(time) },
                onCreateRange = onCreateRange,
                ghost = ghost,
                onLane = onLane,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Several days sharing one ruler — three on a phone, seven on a tablet.
 *
 * The same lane as the day view, several times over, which is the point: everything you can do to a
 * Tuesday in the day view you can do to the Tuesday column here. It was read-only, and a read-only
 * week is a week you have to leave in order to change anything on it.
 */
@Composable
fun WeekTimeline(
    week: List<LocalDate>,
    days: CalendarDays,
    selected: LocalDate,
    onSelectDay: (LocalDate) -> Unit,
    onOpen: (DayItem) -> Unit,
    onEmptyTap: (LocalDate, LocalTime) -> Unit,
    onSpan: (String, LocalDateTime, LocalDateTime) -> Unit,
    onCreateRange: (LocalDateTime, LocalDateTime) -> Unit,
    onHourHeight: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hourHeight = LocalHourHeight.current
    val y = Yantra.colors
    val laid = week.map { TimelineLayout.forDay(days[it].orEmpty(), it) }
    val sep = y.tileBorder.copy(alpha = 0.45f)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val editing = remember(week.first()) { Editing() }
    SettleHeld(editing, laid.flatMap { it.blocks })
    LaunchedEffect(week.first()) {
        scroll.scrollTo(with(density) { (hourHeight * OPEN_AT_HOUR).roundToPx() })
    }

    // Observable, unlike Locale.getDefault() — the same lint the month grid's weekday letters
    // caught, made again here. A composable that reads the default directly keeps whatever language
    // was current when it first composed.
    val locale = LocalConfiguration.current.locales[0]

    Column(modifier) {
        // Day headings, tappable, so the week is also how you move around it.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(RULER_WIDTH))
            week.forEach { d ->
                val isSel = d == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (isSel) Modifier.background(y.accentFill) else Modifier)
                        .clickable { onSelectDay(d) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                        fontSize = 9.sp, color = y.textDim,
                    )
                    Text(
                        d.dayOfMonth.toString(),
                        fontFamily = YantraMono,
                        fontSize = 12.sp,
                        fontWeight = if (d == LocalDate.now()) FontWeight.W700 else FontWeight.W500,
                        color = if (d == LocalDate.now()) y.accent else y.textPrimary,
                    )
                }
            }
        }
        // One all-day strip across the week, so a conference spanning Tuesday to Thursday reads as
        // one thing rather than three.
        if (laid.any { it.allDay.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Spacer(Modifier.width(RULER_WIDTH))
                laid.forEach { d ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        d.allDay.take(2).forEach { AllDayChip(it, compact = true) { onOpen(it) } }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .pinchToZoom(scroll, hourHeight, onHourHeight)
                .verticalScroll(scroll),
        ) {
            HourRuler()
            week.forEachIndexed { i, d ->
                DayLane(
                    day = d,
                    laid = laid[i],
                    editing = editing,
                    compact = true,
                    onSpan = onSpan,
                    onOpen = onOpen,
                    onEmptyTap = onEmptyTap,
                    onCreateRange = onCreateRange,
                    ghost = null,
                    modifier = Modifier
                        .weight(1f)
                        // A hairline between days, on one side only. Borders on every column drew
                        // two lines between each pair and boxed the week into a table.
                        .drawWithContent {
                            drawContent()
                            if (i > 0) drawLine(
                                sep, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f,
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun HourRuler() {
    val y = Yantra.colors
    val hourHeight = LocalHourHeight.current

    Column(Modifier.width(RULER_WIDTH)) {
        repeat(24) { hour ->
            Box(Modifier.height(hourHeight).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                if (hour > 0) {
                    Text(
                        // No leading zero. "9" is a time; "09" is a field in a form.
                        "$hour",
                        fontSize = 10.sp,
                        color = y.textDim,
                        textAlign = TextAlign.End,
                        // Lifted half a line so the number straddles its own hour rule rather than
                        // floating in the middle of the hour above it.
                        modifier = Modifier.padding(end = 8.dp).offset(y = (-7).dp),
                    )
                }
            }
        }
    }
}

/**
 * The hours, as hairlines.
 *
 * One canvas rather than twenty-four bordered boxes. A `border` draws a *rectangle*, so the old
 * version put a line down both sides of every hour as well as across it — which is why the day read
 * as a spreadsheet rather than as a ruler. A calendar wants one line per hour and nothing else.
 */
@Composable
private fun HourGrid() {
    val y = Yantra.colors
    val hourHeight = LocalHourHeight.current

    val hour = y.tileBorder.copy(alpha = 0.5f)
    val half = y.tileBorder.copy(alpha = 0.18f)
    Canvas(Modifier.fillMaxWidth().height(hourHeight * 24)) {
        val h = size.height / 24f
        for (i in 0..24) {
            val at = h * i
            if (i in 1..23) drawLine(hour, Offset(0f, at), Offset(size.width, at), strokeWidth = 1f)
            // A half-hour hint, faint enough to be felt rather than read — it is what makes a
            // 30-minute block legible as half of the hour it sits in.
            if (i < 24) {
                val mid = at + h / 2f
                drawLine(half, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
            }
        }
    }
}

/** Where you are in the day, drawn only on today — a line on any other day would be a lie. */
@Composable
private fun NowLine(day: LocalDate) {
    if (day != LocalDate.now()) return
    val y = Yantra.colors
    val hourHeight = LocalHourHeight.current

    val minute = LocalTime.now().toSecondOfDay() / 60
    Row(
        Modifier.offset(y = hourHeight * (minute / 60f) - 3.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(y.accent))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(y.accent))
    }
}

/**
 * Where the hour lane is, and how tall an hour is on it.
 *
 * Published by [DayTimeline] so a drag that began somewhere else can work out which hour it is over.
 * The coordinates are the lane's own — a point in that space is minutes, directly — and they are
 * only meaningful while [LayoutCoordinates.isAttached], so check before reading.
 */
data class LaneMetrics(val coords: androidx.compose.ui.layout.LayoutCoordinates, val hourPx: Float)

/** Which part of a block a finger took hold of. */
enum class Grab {
    /** The body: the whole block travels and keeps its length. */
    MOVE,

    /** The top edge: the start moves and the end stays where it is. */
    TOP,

    /** The foot: the end moves and the start stays. */
    BOTTOM,
}

/** What a finger is doing to a block right now, in minutes past midnight. */
data class DragState(
    val nodeId: String,
    val startMinute: Int,
    val endMinute: Int,
    val grab: Grab,
)

/** Snapped to the quarter hour. A block that lands at 14:07 because that is where a thumb was is a block nobody chose. */
private fun snap(minute: Int): Int = ((minute + SNAP / 2) / SNAP) * SNAP

/**
 * One block, drawn to scale, with a handle at each end.
 *
 * **Both boundaries move, not just the foot.** A block has two edges and a person stretching an hour
 * has no reason to prefer one of them — pulling the top back to half past nine is the same thought as
 * pushing the bottom out to eleven, and an earlier version could only do the second. The handles are
 * drawn rather than implied, because an invisible grip is one you find by accident.
 *
 * The grabbable end is capped at a third of the block's height, so the middle third is always
 * somewhere to take hold of the whole thing. Two fixed 18dp ends would swallow a half-hour block
 * entirely and leave it impossible to move.
 */
@Composable
private fun BlockChip(
    block: TimedBlock,
    laneWidth: Dp,
    compact: Boolean = false,
    drag: DragState? = null,
    /** Where the finger left it, still drawn there until the file agrees. See [DayTimeline]. */
    held: DragState? = null,
    onDrag: ((DragState?) -> Unit)? = null,
    onCommit: ((DragState) -> Unit)? = null,
    /** A hold that moved nothing. It means "this one", and puts proper handles on its edges. */
    onSelect: ((String) -> Unit)? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val hourHeight = LocalHourHeight.current
    val y = Yantra.colors
    val item = block.item
    val isEvent = item is DayItem.Event
    // A sitting is neither of the other two and has to read as neither — CALENDAR_PLAN.md §11. An
    // appointment is something the world put in your day, so it wears the accent fill. A due task is
    // a deadline landing at an hour, so it is quiet. A sitting is time you gave to your own work:
    // the body of a task, the spine of an effort.
    val sitting = item is DayItem.Event && item.forTaskId != null
    // Somebody else's, read from the phone's calendars — CALENDAR_PLAN.md §5. **No gesture that
    // writes is offered on one**, and that is enforced here rather than trusted to the handlers:
    // there is no node behind it, so there is nothing a drag could edit even if it wanted to.
    val theirs = item as? DayItem.Device
    // The block's own colour, its workspace's, or the accent — resolved in [CalendarBucketer], so
    // by the time it arrives here it is one word or none. A coloured block replaces the *spine*
    // and tints the wash rather than flooding the fill: a day of solid colour blocks is a chart,
    // and the words on them stop being the thing you read.
    val tint = (item as? DayItem.Event)?.tint?.let {
        androidx.compose.ui.graphics.Color(ie.shoonya.yantra.data.label.LabelPalette.display(it, y.isDark))
    // A device event wears the colour its own calendar gives it, unmapped. That colour is the
    // other app's identity and the whole point of drawing it is that you recognise it.
    } ?: theirs?.color?.let { androidx.compose.ui.graphics.Color(it) }
    val spine = when {
        theirs != null -> (tint ?: y.textDim).copy(alpha = 0.75f)
        tint != null -> tint
        isEvent || sitting -> y.accent
        else -> y.textDim.copy(alpha = 0.5f)
    }
    // Always side by side.
    //
    // An earlier version cascaded overlapping blocks once the columns got too narrow to hold a
    // word, which is what a seven-day week on a phone does to them. Showing three days on a phone
    // and seven only where there is room removed that case, and with it the reason for a second
    // layout mode — two staggered blocks read as one smeared block, and their labels ran together.
    val width = laneWidth / block.columns
    val x = width * block.column
    // While this block is the one being dragged it is drawn where the finger has it rather than
    // where the file still says it is, and it stays there after the release until the file catches
    // up. The write happens once, on release — a drag is dozens of frames and each write is a
    // whole-file rewrite plus a reindex.
    val live = drag?.takeIf { it.nodeId == block.item.nodeId }
    val shown = live ?: held?.takeIf { it.nodeId == block.item.nodeId }
    val startMin = shown?.startMinute ?: block.startMinute
    val endMin = shown?.endMinute ?: block.endMinute
    val height = hourHeight * ((endMin - startMin) / 60f)
    val density = LocalDensity.current
    val minutesPerPx = with(density) { 60f / hourHeight.toPx() }
    val writable = onDrag != null && theirs == null
    // Never more than a third of the block, so the middle is always somewhere to grab the whole.
    val grip = minOf(RESIZE_GRIP, height / 3)
    val gripPx = with(density) { grip.toPx() }
    // Handles are for the day view, where a block is wide enough to aim at and there is a gesture
    // behind them. A week's blocks are a seventh as wide and read-only.
    val handles = !compact && writable && height >= HANDLES_FROM

    // A soft fill and a spine down the left, no outline. An outlined block on an outlined grid is
    // two competing rectangles; the spine is what every calendar uses to say "this one is mine"
    // without drawing a second box around it.
    Box(
        Modifier
            .offset(x = x, y = hourHeight * (startMin / 60f))
            .width(width)
            .height(height)
            // **After** the offset, deliberately. A modifier placed before `offset` describes the
            // element at its *un-offset* position: the block drew in the right place while its node
            // sat at the top of the day, clipped to nothing, so nothing could hit it. Tagged so a
            // UI test can address one block — these gestures cannot be driven from `adb input`.
            .testTag("block:${block.item.nodeId}")
            .padding(end = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    // Fainter for somebody else's: it is a backdrop your own day is drawn against,
                    // and a provider colour at the same weight as yours would out-shout the things
                    // you can actually do something about.
                    theirs != null -> (tint ?: y.textDim).copy(alpha = 0.10f)
                    tint != null -> tint.copy(alpha = 0.16f)
                    isEvent && !sitting -> y.accentFill
                    else -> y.cardBg
                }
            )
            // Picked out, so the two handles on its edges read as belonging to *this* block.
            .then(
                if (!selected) Modifier
                else Modifier.border(1.5.dp, y.accent, RoundedCornerShape(7.dp))
            )
            .then(
                if (!writable) Modifier else Modifier.pointerInput(
                    block.item.nodeId, block.startMinute, block.endMinute,
                ) {
                    // The gesture keeps its own running state.
                    //
                    // Reading the composable's `drag` in here reads whatever it was when this
                    // coroutine was launched — null — however many times it has been updated since.
                    // Every frame of the drag hit `?: return` and nothing ever accumulated, so the
                    // block could be pressed and never moved. The state is published outward for
                    // drawing; it is not read back.
                    var working: DragState? = null
                    // Whether the finger ever actually went anywhere.
                    //
                    // `detectDragGesturesAfterLongPress` fires `onDragStart` the moment the press
                    // ripens — before any movement — and then, if the finger lifts without moving,
                    // ends the gesture through **onDragCancel** rather than onDragEnd. So a hold
                    // and release is a cancel, and "did it move" is the only honest way to tell a
                    // pointing gesture from an abandoned drag.
                    var moved = false

                    // Long press first, because a plain drag inside a scrolling column is a scroll.
                    // The lift is what tells you the block is yours to move.
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            moved = false
                            working = DragState(
                                nodeId = block.item.nodeId,
                                startMinute = block.startMinute,
                                endMinute = block.endMinute,
                                // Either end changes the length from that end; anywhere between
                                // them moves the whole thing.
                                grab = when {
                                    at.y <= gripPx -> Grab.TOP
                                    size.height - at.y <= gripPx -> Grab.BOTTOM
                                    else -> Grab.MOVE
                                },
                            )
                            onDrag(working)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val current = working ?: return@detectDragGesturesAfterLongPress
                            if (amount != Offset.Zero) moved = true
                            val by = (amount.y * minutesPerPx).toInt()
                            working = current.stretched(by)
                            onDrag(working)
                        },
                        onDragEnd = {
                            working?.let { w ->
                                val landed = w.snapped()
                                // Nothing moved, so nothing is being asked of the file. Writing the
                                // same span back would bump modified_at and put an empty diff in
                                // somebody's history for the sake of a press.
                                if (!moved ||
                                    (landed.startMinute == block.startMinute &&
                                        landed.endMinute == block.endMinute)
                                ) {
                                    onSelect?.invoke(block.item.nodeId)
                                } else {
                                    onCommit?.invoke(landed)
                                }
                            }
                            working = null
                            onDrag(null)
                        },
                        // A hold released where it started arrives here, not at onDragEnd. It is
                        // the commonest way anybody will ever pick a block out, so it cannot be
                        // treated as an abandoned gesture.
                        onDragCancel = {
                            if (!moved) onSelect?.invoke(block.item.nodeId)
                            working = null
                            onDrag(null)
                        },
                    )
                }
            )
            .pointerInput(block.item.nodeId) { detectTapGestures { onClick() } },
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(spine),
            )
            Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text(
                    item.title,
                    fontSize = if (compact) 9.sp else 12.sp,
                    fontWeight = FontWeight.W600,
                    lineHeight = if (compact) 11.sp else 14.sp,
                    color = when {
                        // A meeting you have written on is one you have a stake in, so it reads at
                        // full strength rather than as backdrop.
                        theirs?.noteId != null -> y.textPrimary
                        theirs != null -> y.textMuted
                        isEvent && !sitting && tint == null -> y.accentText
                        else -> y.textPrimary
                    },
                    maxLines = if (height > hourHeight) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (item is DayItem.Task && item.done) TextDecoration.LineThrough else null,
                )
                // While dragging, the time is the thing you need to see, so it shows at any size.
                if (live != null || (!compact && height > hourHeight * 0.7f)) {
                    Text(
                        // A meeting keeps its own name — it is called what it is called, and your
                        // Wednesday afternoon should stay recognisable. A task made from it, if it
                        // has since been renamed to something else, says so on the second line.
                        theirs?.taskTitle?.takeIf { live == null }
                            ?: ("%d:%02d".format(startMin / 60, startMin % 60) +
                                if (live != null) "–%d:%02d".format(endMin / 60 % 24, endMin % 60) else ""),
                        fontSize = 10.sp,
                        fontWeight = if (live != null) FontWeight.W700 else FontWeight.W400,
                        color = if (isEvent && !sitting && tint == null) y.accentText.copy(alpha = 0.85f) else y.textMuted,
                    )
                }
            }
        }
        // A mark for a meeting you have notes on — CALENDAR_PLAN.md §19. Small and in the corner,
        // because it is a fact about the block rather than something to press: the whole block
        // already opens the notes.
        if (theirs?.noteId != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(spine),
            )
        }
        // Hidden while selected: the big ones out on the boundaries are doing this job, and two
        // marks per edge would read as two different things you could grab.
        if (handles && !selected) {
            Handle(Alignment.TopCenter, tint = spine, lit = live?.grab == Grab.TOP)
            Handle(Alignment.BottomCenter, tint = spine, lit = live?.grab == Grab.BOTTOM)
        }
    }
}

/** How tall a block has to be before it is worth putting two handles on. */
private val HANDLES_FROM = 34.dp

/** How big a boundary handle is once a block has been picked out. A finger, not a hairline. */
private val EDGE_HANDLE = 34.dp

/**
 * The two boundaries of the selected block, as things you can actually take hold of.
 *
 * Siblings of the blocks rather than children of one, and that is the whole point: a handle drawn
 * inside a block can be no bigger than the block, which is why a half-hour one was still fiddly
 * after the in-block handles arrived. These sit *on* the edges — half above the line and half below
 * — so their size has nothing to do with how long the block is.
 *
 * They drag immediately, with no long press to wait through. A long press is what you pay to prove
 * you did not mean to scroll; aiming at a 34dp puck that only appeared because you selected this
 * block is proof enough.
 */
@Composable
private fun EdgeHandles(
    centreX: Dp,
    startMinute: Int,
    endMinute: Int,
    block: TimedBlock,
    onDrag: (DragState?) -> Unit,
    onCommit: (DragState) -> Unit,
) {
    val hourHeight = LocalHourHeight.current
    val density = LocalDensity.current
    val minutesPerPx = with(density) { 60f / hourHeight.toPx() }

    @Composable
    fun puck(grab: Grab, minute: Int) {
        val y = Yantra.colors
        Box(
            Modifier
                .offset(
                    x = centreX - EDGE_HANDLE / 2,
                    y = hourHeight * (minute / 60f) - EDGE_HANDLE / 2,
                )
                .size(EDGE_HANDLE)
                .testTag("grip:${if (grab == Grab.TOP) "top" else "bottom"}:${block.item.nodeId}")
                .pointerInput(block.item.nodeId, grab, block.startMinute, block.endMinute) {
                    // The gesture keeps its own state for the same reason the block's does: a
                    // lambda inside pointerInput reads what it captured when the coroutine started.
                    var working: DragState? = null
                    detectVerticalDragGestures(
                        onDragStart = {
                            working = DragState(
                                block.item.nodeId, block.startMinute, block.endMinute, grab,
                            )
                            onDrag(working)
                        },
                        onVerticalDrag = { change, dy ->
                            // Consumed, or the column this sits in reads the same movement as a
                            // scroll and takes the gesture away mid-stretch.
                            change.consume()
                            working = working?.stretched((dy * minutesPerPx).toInt())
                            onDrag(working)
                        },
                        onDragEnd = {
                            working?.let { onCommit(it.snapped()) }
                            working = null
                            onDrag(null)
                        },
                        onDragCancel = { working = null; onDrag(null) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // A ring rather than a disc: the boundary has to stay visible through the middle of it,
            // or the handle hides the very edge you are placing.
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(y.page)
                    .border(2.5.dp, y.accent, CircleShape),
            )
        }
    }

    puck(Grab.TOP, startMinute)
    puck(Grab.BOTTOM, endMinute)
}

/**
 * A boundary you can pull.
 *
 * A short bar rather than a circle at the corner: the whole edge moves, so the mark belongs in the
 * middle of it. Faint until it is the one being held, because two bright bars on every block would
 * turn a full day into a ladder.
 */
@Composable
private fun BoxScope.Handle(where: Alignment, tint: Color, lit: Boolean) {
    Box(
        Modifier
            .align(where)
            .padding(vertical = 2.dp)
            .width(if (lit) 34.dp else 26.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            // The block's own colour, not the accent: a coloured block with accent handles reads
            // as two things stuck together rather than one thing with edges.
            .background(tint.copy(alpha = if (lit) 1f else 0.5f)),
    )
}

/** The same block after a finger has moved [by] minutes, whichever part of it was taken hold of. */
private fun DragState.stretched(by: Int): DragState = when (grab) {
    Grab.BOTTOM -> copy(
        endMinute = (endMinute + by)
            .coerceIn(startMinute + TimelineLayout.MIN_BLOCK_MINUTES, TimelineLayout.MINUTES_IN_DAY),
    )
    // A block cannot be pulled through itself: the top stops a minimum short of the foot, which is
    // also what stops it turning inside out and rendering a negative height.
    Grab.TOP -> copy(
        startMinute = (startMinute + by)
            .coerceIn(0, endMinute - TimelineLayout.MIN_BLOCK_MINUTES),
    )
    Grab.MOVE -> {
        val length = endMinute - startMinute
        val s0 = (startMinute + by).coerceIn(0, TimelineLayout.MINUTES_IN_DAY - length)
        copy(startMinute = s0, endMinute = s0 + length)
    }
}

/** Where it lands: the quarter hour, from whichever end was being held. */
private fun DragState.snapped(): DragState = when (grab) {
    Grab.BOTTOM -> copy(
        endMinute = snap(endMinute).coerceAtLeast(startMinute + TimelineLayout.MIN_BLOCK_MINUTES),
    )
    Grab.TOP -> copy(
        startMinute = snap(startMinute).coerceAtMost(endMinute - TimelineLayout.MIN_BLOCK_MINUTES),
    )
    Grab.MOVE -> {
        val length = endMinute - startMinute
        val s0 = snap(startMinute)
        copy(startMinute = s0, endMinute = s0 + length)
    }
}

@Composable
private fun AllDayBar(items: List<DayItem>, onOpen: (DayItem) -> Unit) {
    if (items.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(start = RULER_WIDTH, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.take(4).forEach { AllDayChip(it) { onOpen(it) } }
    }
}

@Composable
private fun AllDayChip(item: DayItem, compact: Boolean = false, onClick: () -> Unit) {
    val y = Yantra.colors
    val isEvent = item is DayItem.Event
    Box(
        Modifier
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (isEvent) y.accentFill else y.cardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            item.title,
            fontSize = if (compact) 8.sp else 10.sp,
            color = if (isEvent) y.accentText else y.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Empty ruler: tap to fill an hour, long-press and drag to draw a range.
 *
 * The tap is the degenerate case of the drag, and both live here rather than on the grid so that a
 * gesture landing on a block is the block's.
 */
@Composable
private fun TapTargets(
    onTap: (LocalTime) -> Unit,
    draft: IntRange?,
    onDraft: (IntRange?) -> Unit,
    onCommit: (IntRange) -> Unit,
) {
    val hourHeight = LocalHourHeight.current
    val density = LocalDensity.current
    val minutesPerPx = with(density) { 60f / hourHeight.toPx() }
    Column(
        Modifier
            .testTag("ruler")
            // Tap and long-press-drag on one surface. Twenty-four `clickable` boxes used to sit
            // inside this, and a child's clickable is hit-tested first — so it swallowed the press
            // and the long press up here never fired. One gesture area, two detectors.
            .fillMaxWidth()
            .height(hourHeight * 24)
            // Drag detector declared first, tap second. Order matters: with the tap detector first
            // it claimed the press and the long press never fired, so the ruler could be tapped and
            // not dragged.
            .pointerInput(Unit) {
            // Its own running state, for the reason the block's gesture gives: the composable's
            // `draft` read in here is the value from when this coroutine started.
            var anchorMin = 0
            var last: Int? = null
            detectDragGesturesAfterLongPress(
                onDragStart = { at ->
                    anchorMin = snap((at.y * minutesPerPx).toInt())
                    last = anchorMin + SNAP
                    onDraft(anchorMin..last!!)
                },
                onDrag = { change, amount ->
                    change.consume()
                    val current = last ?: return@detectDragGesturesAfterLongPress
                    last = (current + amount.y * minutesPerPx).toInt()
                        .coerceIn(anchorMin + SNAP, TimelineLayout.MINUTES_IN_DAY)
                    onDraft(anchorMin..last!!)
                },
                onDragEnd = {
                    last?.let { onCommit(anchorMin..snap(it)) }
                    last = null
                    onDraft(null)
                },
                onDragCancel = { last = null; onDraft(null) },
            )
            }
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    val minute = (at.y * minutesPerPx).toInt().coerceIn(0, TimelineLayout.MINUTES_IN_DAY - 1)
                    onTap(LocalTime.of(minute / 60, 0))
                }
            },
    ) {}
}

/** The outline a drag on empty ruler leaves behind it, before anything is written. */
@Composable
private fun DraftBlock(range: IntRange) {
    val y = Yantra.colors
    val hourHeight = LocalHourHeight.current

    Box(
        Modifier
            .offset(y = hourHeight * (range.first / 60f))
            .fillMaxWidth()
            .height(hourHeight * ((range.last - range.first) / 60f))
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(y.accentFill.copy(alpha = 0.6f))
            .border(1.dp, y.accent, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "%d:%02d–%d:%02d".format(
                range.first / 60, range.first % 60, range.last / 60 % 24, range.last % 60,
            ),
            fontSize = 11.sp,
            fontWeight = FontWeight.W700,
            color = y.accentText,
        )
    }
}
