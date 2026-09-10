package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.background
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

private val HOUR_HEIGHT = 52.dp

/** Where a timeline opens. Early enough to catch a morning, late enough to skip the small hours. */
private const val OPEN_AT_HOUR = 7
private val RULER_WIDTH = 42.dp

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
                HourLines()
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
                        .border(width = 0.5.dp, color = y.tileBorder.copy(alpha = 0.4f)),
                ) {
                    HourLines()
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
                        "%02d".format(hour),
                        fontFamily = YantraMono,
                        fontSize = 9.sp,
                        color = y.textDim,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(end = 6.dp).offset(y = (-5).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HourLines() {
    val y = Yantra.colors
    Column {
        repeat(24) {
            Box(
                Modifier
                    .height(HOUR_HEIGHT)
                    .fillMaxWidth()
                    .border(width = 0.5.dp, color = y.tileBorder.copy(alpha = 0.35f)),
            )
        }
    }
}

/** Where you are in the day, drawn only on today — a line on any other day would be a lie. */
@Composable
private fun NowLine(day: LocalDate) {
    if (day != LocalDate.now()) return
    val y = Yantra.colors
    val minute = LocalTime.now().toSecondOfDay() / 60
    Box(
        Modifier
            .offset(y = HOUR_HEIGHT * (minute / 60f))
            .fillMaxWidth()
            .height(1.5.dp)
            .background(y.accent),
    )
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
    val width = laneWidth / block.columns
    val height = HOUR_HEIGHT * ((block.endMinute - block.startMinute) / 60f)

    Box(
        Modifier
            .offset(x = width * block.column, y = HOUR_HEIGHT * (block.startMinute / 60f))
            .width(width)
            .height(height)
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            // An event is filled; a task is outlined. The two are equally real on the timeline and
            // one of them can still be ticked off, so they should not look identical.
            .background(if (isEvent) y.accentFill else y.cardBg)
            .border(1.dp, if (isEvent) y.accentBorder else y.tileBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Column {
            Text(
                item.title,
                fontSize = if (compact) 8.sp else 11.sp,
                fontWeight = FontWeight.W600,
                color = if (isEvent) y.accentText else y.textPrimary,
                maxLines = if (height > HOUR_HEIGHT) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (item is DayItem.Task && item.done) TextDecoration.LineThrough else null,
            )
            if (!compact && height > HOUR_HEIGHT * 0.6f) {
                Text(
                    "%02d:%02d".format(block.startMinute / 60, block.startMinute % 60),
                    fontFamily = YantraMono,
                    fontSize = 9.sp,
                    color = if (isEvent) y.accentText.copy(alpha = 0.75f) else y.textMuted,
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
