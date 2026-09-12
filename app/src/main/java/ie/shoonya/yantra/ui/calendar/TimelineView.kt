package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.BoxWithConstraints
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

private val HOUR_HEIGHT = 60.dp

/** Where a timeline opens. Early enough to catch a morning, late enough to skip the small hours. */
private const val OPEN_AT_HOUR = 7

/** Everything snaps to the quarter hour. */
private const val SNAP = 15

/** How much of a block's foot is a resize grip rather than somewhere to grab and move it. */
private val RESIZE_GRIP = 18.dp

private val RULER_WIDTH = 36.dp

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
    onMove: (String, LocalDateTime) -> Unit,
    onResize: (String, LocalDateTime) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    val laid = TimelineLayout.forDay(items, day)
    var drag by remember(day) { mutableStateOf<DragState?>(null) }
    var draft by remember(day) { mutableStateOf<IntRange?>(null) }

    // Open on the working day rather than at midnight, which is eight hours of nothing.
    //
    // Converted through the density: `scrollTo` counts pixels and `HOUR_HEIGHT.value` is a dp
    // number, so passing it raw scrolled to about three in the morning on a 3x screen — and to a
    // different hour on every different screen, which is the tell.
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR_HEIGHT.toPx() }
    LaunchedEffect(day) { scroll.scrollTo(with(density) { (HOUR_HEIGHT * OPEN_AT_HOUR).roundToPx() }) }

    Column(modifier) {
        AllDayBar(laid.allDay, onOpen)
        Row(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            HourRuler()
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .then(
                        if (onLane == null) Modifier
                        // The node measured here is the whole 24-hour content, translated by the
                        // scroll — so a point in its local space is an hour directly, with no
                        // scroll arithmetic for the caller to get wrong.
                        else Modifier.onGloballyPositioned {
                            onLane(LaneMetrics(it, hourPx))
                        }
                    ),
            ) {
                HourGrid()
                // Underneath the blocks, deliberately. Tapping bare ruler makes something there —
                // the quickest way to block out an hour is to point at the hour — but drawn last it
                // would cover the whole column and swallow every tap meant for a block.
                TapTargets(
                    onTap = onEmptyTap,
                    draft = draft,
                    onDraft = { draft = it },
                    onCommit = { range ->
                        onCreateRange(
                            day.atStartOfDay().plusMinutes(range.first.toLong()),
                            day.atStartOfDay().plusMinutes(range.last.toLong()),
                        )
                    },
                )
                NowLine(day)
                laid.blocks.forEach { block ->
                    BlockChip(
                        block = block,
                        laneWidth = maxWidth,
                        drag = drag,
                        onDrag = { drag = it },
                        onCommit = { d ->
                            val at = day.atStartOfDay().plusMinutes(d.startMinute.toLong())
                            if (d.resizing) onResize(d.nodeId, day.atStartOfDay().plusMinutes(d.endMinute.toLong()))
                            else onMove(d.nodeId, at)
                        },
                        onClick = { onOpen(block.item) },
                    )
                }
                // The block being drawn by a drag on empty ruler, or by a task on its way in
                // from the rail. Same shape either way: what it will be if you let go here.
                (draft ?: ghost)?.let { DraftBlock(it) }
            }
        }
    }
}

/** Seven days sharing one ruler. The same blocks, a seventh as wide. */
@Composable
fun WeekTimeline(
    week: List<LocalDate>,
    days: CalendarDays,
    selected: LocalDate,
    onSelectDay: (LocalDate) -> Unit,
    onOpen: (DayItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val y = Yantra.colors
    val laid = week.map { TimelineLayout.forDay(days[it].orEmpty(), it) }
    val sep = y.tileBorder.copy(alpha = 0.45f)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(week.first()) {
        scroll.scrollTo(with(density) { (HOUR_HEIGHT * OPEN_AT_HOUR).roundToPx() })
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
        Row(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            HourRuler()
            week.forEachIndexed { i, d ->
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        // A hairline between days, on one side only. Borders on every column drew
                        // two lines between each pair and boxed the week into a table.
                        .drawWithContent {
                            drawContent()
                            if (i > 0) drawLine(
                                sep, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f,
                            )
                        },
                ) {
                    HourGrid()
                    NowLine(d)
                    laid[i].blocks.forEach { block ->
                        BlockChip(block, maxWidth, compact = true) { onOpen(block.item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HourRuler() {
    val y = Yantra.colors
    Column(Modifier.width(RULER_WIDTH)) {
        repeat(24) { hour ->
            Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
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
    val hour = y.tileBorder.copy(alpha = 0.5f)
    val half = y.tileBorder.copy(alpha = 0.18f)
    Canvas(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24)) {
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
    val minute = LocalTime.now().toSecondOfDay() / 60
    Row(
        Modifier.offset(y = HOUR_HEIGHT * (minute / 60f) - 3.dp).fillMaxWidth(),
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

/** What a finger is doing to a block right now, in minutes past midnight. */
data class DragState(val nodeId: String, val startMinute: Int, val endMinute: Int, val resizing: Boolean)

/** Snapped to the quarter hour. A block that lands at 14:07 because that is where a thumb was is a block nobody chose. */
private fun snap(minute: Int): Int = ((minute + SNAP / 2) / SNAP) * SNAP

@Composable
private fun BlockChip(
    block: TimedBlock,
    laneWidth: Dp,
    compact: Boolean = false,
    drag: DragState? = null,
    onDrag: ((DragState?) -> Unit)? = null,
    onCommit: ((DragState) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val y = Yantra.colors
    val item = block.item
    val isEvent = item is DayItem.Event
    // A sitting is neither of the other two and has to read as neither — CALENDAR_PLAN.md §11. An
    // appointment is something the world put in your day, so it wears the accent fill. A due task is
    // a deadline landing at an hour, so it is quiet. A sitting is time you gave to your own work:
    // the body of a task, the spine of an effort.
    val sitting = item is DayItem.Event && item.forTaskId != null
    // Always side by side.
    //
    // An earlier version cascaded overlapping blocks once the columns got too narrow to hold a
    // word, which is what a seven-day week on a phone does to them. Showing three days on a phone
    // and seven only where there is room removed that case, and with it the reason for a second
    // layout mode — two staggered blocks read as one smeared block, and their labels ran together.
    val width = laneWidth / block.columns
    val x = width * block.column
    // While this block is the one being dragged, it is drawn where the finger has it rather than
    // where the file still says it is. The write happens once, on release — a drag is dozens of
    // frames and each write is a whole-file rewrite plus a reindex.
    val live = drag?.takeIf { it.nodeId == block.item.nodeId }
    val startMin = live?.startMinute ?: block.startMinute
    val endMin = live?.endMinute ?: block.endMinute
    val height = HOUR_HEIGHT * ((endMin - startMin) / 60f)
    val density = LocalDensity.current
    val minutesPerPx = with(density) { 60f / HOUR_HEIGHT.toPx() }

    // A soft fill and a spine down the left, no outline. An outlined block on an outlined grid is
    // two competing rectangles; the spine is what every calendar uses to say "this one is mine"
    // without drawing a second box around it.
    Row(
        Modifier
            .offset(x = x, y = HOUR_HEIGHT * (startMin / 60f))
            .width(width)
            .height(height)
            // **After** the offset, deliberately. A modifier placed before `offset` describes the
            // element at its *un-offset* position: the block drew in the right place while its node
            // sat at the top of the day, clipped to nothing, so nothing could hit it. Tagged so a
            // UI test can address one block — these gestures cannot be driven from `adb input`.
            .testTag("block:${block.item.nodeId}")
            .padding(end = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (isEvent && !sitting) y.accentFill else y.cardBg)
            .then(
                if (onDrag == null) Modifier else Modifier.pointerInput(block.item.nodeId, block.startMinute) {
                    // The gesture keeps its own running state.
                    //
                    // Reading the composable's `drag` in here reads whatever it was when this
                    // coroutine was launched — null — however many times it has been updated since.
                    // Every frame of the drag hit `?: return` and nothing ever accumulated, so the
                    // block could be pressed and never moved. The state is published outward for
                    // drawing; it is not read back.
                    var working: DragState? = null

                    // Long press first, because a plain drag inside a scrolling column is a scroll.
                    // The lift is what tells you the block is yours to move.
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            val fromBottom = size.height - at.y
                            working = DragState(
                                nodeId = block.item.nodeId,
                                startMinute = block.startMinute,
                                endMinute = block.endMinute,
                                // Grabbing the foot of a block changes its length; grabbing
                                // anywhere else moves the whole thing.
                                resizing = fromBottom < RESIZE_GRIP.toPx(),
                            )
                            onDrag(working)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val current = working ?: return@detectDragGesturesAfterLongPress
                            val by = (amount.y * minutesPerPx).toInt()
                            working = if (current.resizing) current.copy(
                                endMinute = (current.endMinute + by)
                                    .coerceIn(current.startMinute + TimelineLayout.MIN_BLOCK_MINUTES, TimelineLayout.MINUTES_IN_DAY)
                            ) else {
                                val length = current.endMinute - current.startMinute
                                val s0 = (current.startMinute + by).coerceIn(0, TimelineLayout.MINUTES_IN_DAY - length)
                                current.copy(startMinute = s0, endMinute = s0 + length)
                            }
                            onDrag(working)
                        },
                        onDragEnd = {
                            working?.let { current ->
                                onCommit?.invoke(
                                    if (current.resizing) current.copy(endMinute = snap(current.endMinute))
                                    else {
                                        val length = current.endMinute - current.startMinute
                                        val s0 = snap(current.startMinute)
                                        current.copy(startMinute = s0, endMinute = s0 + length)
                                    }
                                )
                            }
                            working = null
                            onDrag(null)
                        },
                        onDragCancel = { working = null; onDrag(null) },
                    )
                }
            )
            .pointerInput(block.item.nodeId) { detectTapGestures { onClick() } },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (isEvent || sitting) y.accent else y.textDim.copy(alpha = 0.5f)),
        )
        Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(
                item.title,
                fontSize = if (compact) 9.sp else 12.sp,
                fontWeight = FontWeight.W600,
                lineHeight = if (compact) 11.sp else 14.sp,
                color = if (isEvent && !sitting) y.accentText else y.textPrimary,
                maxLines = if (height > HOUR_HEIGHT) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (item is DayItem.Task && item.done) TextDecoration.LineThrough else null,
            )
            // While dragging, the time is the thing you need to see, so it shows at any size.
            if (live != null || (!compact && height > HOUR_HEIGHT * 0.7f)) {
                Text(
                    "%d:%02d".format(startMin / 60, startMin % 60) +
                        if (live != null) "–%d:%02d".format(endMin / 60 % 24, endMin % 60) else "",
                    fontSize = 10.sp,
                    fontWeight = if (live != null) FontWeight.W700 else FontWeight.W400,
                    color = if (isEvent && !sitting) y.accentText.copy(alpha = 0.85f) else y.textMuted,
                )
            }
        }
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
    val density = LocalDensity.current
    val minutesPerPx = with(density) { 60f / HOUR_HEIGHT.toPx() }
    Column(
        Modifier
            .testTag("ruler")
            // Tap and long-press-drag on one surface. Twenty-four `clickable` boxes used to sit
            // inside this, and a child's clickable is hit-tested first — so it swallowed the press
            // and the long press up here never fired. One gesture area, two detectors.
            .fillMaxWidth()
            .height(HOUR_HEIGHT * 24)
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
    Box(
        Modifier
            .offset(y = HOUR_HEIGHT * (range.first / 60f))
            .fillMaxWidth()
            .height(HOUR_HEIGHT * ((range.last - range.first) / 60f))
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
