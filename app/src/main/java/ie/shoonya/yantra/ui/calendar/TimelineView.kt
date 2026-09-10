package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import java.time.LocalTime

private val HOUR_HEIGHT = 60.dp

/** Where a timeline opens. Early enough to catch a morning, late enough to skip the small hours. */
private const val OPEN_AT_HOUR = 7

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
    modifier: Modifier = Modifier,
) {
    val laid = TimelineLayout.forDay(items, day)
    val scroll = rememberScrollState()

    // Open on the working day rather than at midnight, which is eight hours of nothing.
    //
    // Converted through the density: `scrollTo` counts pixels and `HOUR_HEIGHT.value` is a dp
    // number, so passing it raw scrolled to about three in the morning on a 3x screen — and to a
    // different hour on every different screen, which is the tell.
    val density = LocalDensity.current
    LaunchedEffect(day) { scroll.scrollTo(with(density) { (HOUR_HEIGHT * OPEN_AT_HOUR).roundToPx() }) }

    Column(modifier) {
        AllDayBar(laid.allDay, onOpen)
        Row(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            HourRuler()
            BoxWithConstraints(Modifier.weight(1f)) {
                HourGrid()
                // Underneath the blocks, deliberately. Tapping bare ruler makes something there —
                // the quickest way to block out an hour is to point at the hour — but drawn last it
                // would cover the whole column and swallow every tap meant for a block.
                TapTargets(onEmptyTap)
                NowLine(day)
                laid.blocks.forEach { block ->
                    BlockChip(
                        block = block,
                        laneWidth = maxWidth,
                        onClick = { onOpen(block.item) },
                    )
                }
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

@Composable
private fun BlockChip(
    block: TimedBlock,
    laneWidth: Dp,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val y = Yantra.colors
    val item = block.item
    val isEvent = item is DayItem.Event
    // Always side by side.
    //
    // An earlier version cascaded overlapping blocks once the columns got too narrow to hold a
    // word, which is what a seven-day week on a phone does to them. Showing three days on a phone
    // and seven only where there is room removed that case, and with it the reason for a second
    // layout mode — two staggered blocks read as one smeared block, and their labels ran together.
    val width = laneWidth / block.columns
    val x = width * block.column
    val height = HOUR_HEIGHT * ((block.endMinute - block.startMinute) / 60f)

    // A soft fill and a spine down the left, no outline. An outlined block on an outlined grid is
    // two competing rectangles; the spine is what every calendar uses to say "this one is mine"
    // without drawing a second box around it.
    Row(
        Modifier
            .offset(x = x, y = HOUR_HEIGHT * (block.startMinute / 60f))
            .width(width)
            .height(height)
            .padding(end = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (isEvent) y.accentFill else y.cardBg)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (isEvent) y.accent else y.textDim.copy(alpha = 0.5f)),
        )
        Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(
                item.title,
                fontSize = if (compact) 9.sp else 12.sp,
                fontWeight = FontWeight.W600,
                lineHeight = if (compact) 11.sp else 14.sp,
                color = if (isEvent) y.accentText else y.textPrimary,
                maxLines = if (height > HOUR_HEIGHT) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (item is DayItem.Task && item.done) TextDecoration.LineThrough else null,
            )
            if (!compact && height > HOUR_HEIGHT * 0.7f) {
                Text(
                    "%d:%02d".format(block.startMinute / 60, block.startMinute % 60),
                    fontSize = 10.sp,
                    color = if (isEvent) y.accentText.copy(alpha = 0.7f) else y.textMuted,
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

/** An invisible row per hour, so tapping empty time offers to fill it. */
@Composable
private fun TapTargets(onTap: (LocalTime) -> Unit) {
    Column {
        repeat(24) { hour ->
            Box(
                Modifier
                    .height(HOUR_HEIGHT)
                    .fillMaxWidth()
                    .clickable { onTap(LocalTime.of(hour, 0)) }
                    .background(Color.Transparent),
            )
        }
    }
}
