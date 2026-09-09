package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ie.shoonya.yantra.ui.container
import ie.shoonya.yantra.ui.Routes
import ie.shoonya.yantra.ui.components.NavCircle
import ie.shoonya.yantra.ui.components.PAGE_MARGIN
import ie.shoonya.yantra.ui.components.PageHeader
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY_LABEL = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * How wide the calendar gets before it stops growing.
 *
 * A month is a fixed amount of information and a seven-column grid stretched across a tablet gives
 * cells the size of playing cards. Capping the whole column — header, grid and day list together —
 * rather than the grid alone is what keeps them in one line of sight: an earlier version centred the
 * grid and left everything else against the left margin, which read as two unrelated screens.
 *
 * 48dp a cell is the metric `DueSheet`'s picker already uses, so the two read as the same calendar.
 */
private val CONTENT_MAX_WIDTH = 460.dp
private val CELL_HEIGHT = 52.dp

/**
 * The month, and what is on the day you tapped — CALENDAR_PLAN.md §6.
 *
 * Events and due tasks share one list, because they answer the same question and interleaving two
 * lists in the view is how the two end up sorted differently. The bucketing that decides which day
 * a thing lands on is [CalendarBucketer], kept out of here so the off-by-ones can be tested.
 */
@Composable
fun CalendarScreen(nav: NavHostController) {
    val vm: CalendarViewModel = viewModel { CalendarViewModel(container()) }
    val month by vm.month.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val items by vm.selectedItems.collectAsStateWithLifecycle()
    val y = Yantra.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // The header is chrome and spans the screen; everything below it is content and is capped.
        PageHeader("Calendar", onBack = { nav.popBackStack() })

        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = CONTENT_MAX_WIDTH),
        ) {
        MonthBar(
            month = month,
            onPrev = { vm.show(month.minusMonths(1)) },
            onNext = { vm.show(month.plusMonths(1)) },
            onToday = { vm.today() },
        )

        MonthGrid(
            month = month,
            selected = selected,
            days = days,
            onSelect = vm::select,
            modifier = Modifier.padding(horizontal = PAGE_MARGIN),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            selected.format(DAY_LABEL),
            fontSize = 12.sp,
            fontWeight = FontWeight.W700,
            color = y.textDim,
            modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 6.dp),
        )

        if (items.isEmpty()) {
            Text(
                "Nothing on this day.",
                fontSize = 14.sp,
                color = y.textMuted,
                modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 12.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.nodeId + it.sortKey }) { item ->
                    DayRow(item) { nav.navigate(Routes.node(item.nodeId)) }
                }
            }
        }
        }
    }
}

@Composable
private fun MonthBar(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            month.atDay(1).format(MONTH_LABEL),
            fontSize = 17.sp,
            fontWeight = FontWeight.W700,
            color = y.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Today",
            fontSize = 12.sp,
            fontWeight = FontWeight.W700,
            color = y.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToday)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        NavCircle(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", onPrev, iconSize = 18.dp)
        Spacer(Modifier.width(6.dp))
        NavCircle(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", onNext, iconSize = 18.dp)
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    days: CalendarDays,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val y = Yantra.colors
    val grid = monthGrid(month.atDay(1))
    val today = LocalDate.now()

    // Capped and centred rather than stretched. A seven-column grid across a 2560px tablet gives
    // 350px cells, which pushed the day list clean off the bottom of the screen — the grid has a
    // natural size and more room than that is room for something else. 48dp per cell is the metric
    // DueSheet's picker already uses (CALENDAR_WIDTH), so the two read as the same calendar.
    Column(modifier.fillMaxWidth()) {
        // Read from the configuration rather than Locale.getDefault(), which a composable cannot
        // observe: the letters would keep whatever language was current when the screen was first
        // composed and quietly disagree with the rest of the app after a locale change.
        val locale = LocalConfiguration.current.locales[0]
        Row(Modifier.fillMaxWidth()) {
            // Monday-first, matching monthGrid. Taken from the locale rather than hardcoded so the
            // letters are right in every language the app is read in.
            grid.take(7).forEach { d ->
                Text(
                    d.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W700,
                    color = y.textDim,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = YearMonth.from(day) == month,
                        isToday = day == today,
                        isSelected = day == selected,
                        count = days[day]?.size ?: 0,
                        onClick = { onSelect(day) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val y = Yantra.colors
    Box(
        modifier
            // Fixed height, not a square: the width is whatever a seventh of the grid comes to, and
            // tying height to it made the cells grow without limit on a wide screen.
            .height(CELL_HEIGHT)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (isSelected) Modifier.background(y.accentFill) else Modifier)
            .then(if (isToday && !isSelected) Modifier.border(1.dp, y.accentBorder, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.dayOfMonth.toString(),
                fontFamily = YantraMono,
                fontSize = 13.sp,
                fontWeight = if (isToday || isSelected) FontWeight.W700 else FontWeight.W500,
                // Days from the neighbouring months are shown rather than blanked, so the grid keeps
                // its shape, but dimmed so the month you are looking at is the one that reads.
                color = when {
                    isSelected -> y.accentText
                    !inMonth -> y.textDim.copy(alpha = 0.45f)
                    isToday -> y.accent
                    else -> y.textPrimary
                },
            )
            Spacer(Modifier.height(2.dp))
            // A dot means "something here", and up to three mean "more than one thing" without
            // asking anyone to read a number off a 40dp square.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(minOf(count, 3)) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) y.accentText else y.accent),
                    )
                }
                if (count == 0) Spacer(Modifier.size(4.dp))
            }
        }
    }
}

@Composable
private fun DayRow(item: DayItem, onOpen: () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(y.cardBg)
            .border(1.dp, y.tileBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (item) {
                is DayItem.Event -> if (item.allDay) "all day" else item.start.format(TIME)
                is DayItem.Task -> if (item.hasTime) item.at.format(TIME) else "due"
            },
            fontFamily = YantraMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.W700,
            color = if (item is DayItem.Event) y.accent else y.textDim,
            modifier = Modifier.width(52.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 14.sp,
                color = y.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A finished task reads as finished here too, rather than looking like something
                // still ahead of you.
                textDecoration = if (item is DayItem.Task && item.done)
                    androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            val sub = when (item) {
                is DayItem.Event -> listOfNotNull(
                    item.location,
                    if (!item.allDay && item.end != item.start) "until ${item.end.format(TIME)}" else null,
                ).joinToString(" · ")
                is DayItem.Task -> ""
            }
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 11.sp, color = y.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Marked because only the first occurrence is drawn until expansion lands, so a repeat that
        // showed once would otherwise look like a one-off somebody mistyped.
        if (item is DayItem.Event && item.repeating) {
            Icon(
                Icons.Default.Repeat,
                contentDescription = "Repeats",
                tint = y.textDim,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
