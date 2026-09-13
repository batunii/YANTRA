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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
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

/**
 * Short, and one line.
 *
 * The full month name wrapped to three lines once the M/W/D switcher joined the bar — a phone is
 * only so wide, and the heading is the part that can give without anything being lost.
 */
private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy")
private val DAY_HEADING = DateTimeFormatter.ofPattern("EEE d MMM")
private val WEEK_END = DateTimeFormatter.ofPattern("d MMM")

/**
 * What is on screen, said out loud.
 *
 * The month name alone was fine while a month was all there was; in the day view it left nothing on
 * screen saying *which* day you were looking at, which is the one thing a day view has to answer.
 */
private fun heading(mode: CalendarMode, month: java.time.YearMonth, selected: LocalDate, days: Int): String =
    when (mode) {
        CalendarMode.MONTH -> month.atDay(1).format(MONTH_LABEL)
        CalendarMode.DAY -> selected.format(DAY_HEADING)
        CalendarMode.WEEK -> {
            val span = TimelineLayout.span(selected, days)
            "${span.first().dayOfMonth}–${span.last().format(WEEK_END)}"
        }
    }
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

/** Where a screen stops being a phone. The usual breakpoint, and where seven columns start to fit. */
private val TABLET_WIDTH = 600.dp
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
    val mode by vm.mode.collectAsStateWithLifecycle()
    val y = Yantra.colors

    // Seven days need room; a phone has not got it. Measured from the window rather than from
    // Configuration.screenWidthDp, which lint asks us not to read in a composable.
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val daysAcross = if (widthDp >= TABLET_WIDTH) 7 else 3
    LaunchedEffect(daysAcross) { vm.setDaysOnScreen(daysAcross) }

    // null = closed. Editing carries the event it opened on; creating carries nothing.
    var sheet by remember { mutableStateOf<EventSheetTarget?>(null) }
    // A range marked on an empty day, waiting to be told what goes in it — CALENDAR_PLAN.md §13B.
    var marked by remember { mutableStateOf<ClosedRange<java.time.LocalDateTime>?>(null) }
    // Somebody else's event, tapped. Read-only, with the two things you can do about it.
    var theirs by remember { mutableStateOf<DayItem.Device?>(null) }
    val scope = rememberCoroutineScope()

    val rail by vm.rail.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val armed by vm.armed.collectAsStateWithLifecycle()
    // Open, on every screen. The rail is what the day view is *for* — a calendar can only draw what
    // already has a date, so the task most in need of a time is the one it cannot show, and a rail
    // you have to go and find does not answer that. An earlier version opened it only on a tablet,
    // which meant the phone showed the same day it always had.
    //
    // The toggle stays, because the full-width day is the right shape for reading a busy one and
    // for the mark-then-pick gesture — see §13B.
    var railOpen by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // How tall an hour is — CALENDAR_PLAN.md §17. Read once from this device's preferences and
    // written back as it changes, because re-pinching on every visit would be worse than no zoom.
    var hourHeight by remember { mutableStateOf(loadHourHeight(context)) }

    // A permission granted in Settings, or a calendar ticked there, cannot reach this screen as a
    // Flow. Asking again on resume is the cheap and correct answer: it is one provider query.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.overlayChanged()
        onPauseOrDispose { }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalHourHeight provides hourHeight) {
    Column(
        Modifier
            .fillMaxSize()
            .background(y.page)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // The header is chrome and spans the screen; everything below it is content and is capped.
        // The switcher lives in the header's actions, not in the row below it. Down there it left
        // the heading about forty pixels and "Thu 10 Sep" came out as "T…"; up here it sits in
        // space the title bar already had spare.
        PageHeader("Calendar", onBack = { nav.popBackStack() }) {
            ModeSwitch(mode = mode, days = daysAcross, onMode = vm::setMode)
            Spacer(Modifier.width(6.dp))
            // Making something is the one thing a calendar is *for* that looking at it does not
            // cover, and it used to live under the month grid — which meant it did not exist in the
            // two views you actually plan in. Here it is on every mode, in the bar the eye already
            // goes to, and it opens on the day you are looking at.
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(y.accentFill)
                    .clickable { sheet = EventSheetTarget(null, null) }
                    .testTag("newEvent"),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", fontSize = 19.sp, fontWeight = FontWeight.W700, color = y.accent)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                // A month is a fixed amount of information and stretching it gives cells the size
                // of playing cards. A timeline is not: an hour with three things in it wants every
                // pixel there is, and the rail beside it wants a quarter of a real width rather
                // than a quarter of 460dp.
                .widthIn(max = if (mode == CalendarMode.MONTH) CONTENT_MAX_WIDTH else Dp.Unspecified),
        ) {
        MonthBar(
            month = month,
            selected = selected,
            mode = mode,
            days = daysAcross,
            railOpen = railOpen,
            onRail = { railOpen = !railOpen },
            onPrev = { vm.step(-1) },
            onNext = { vm.step(1) },
            onToday = { vm.today() },
        )

        when (mode) {
            CalendarMode.MONTH -> MonthGrid(
                month = month,
                selected = selected,
                days = days,
                onSelect = vm::select,
                modifier = Modifier.padding(horizontal = PAGE_MARGIN),
            )
            // One planner, two spans. The multi-day view used to be a picture — nothing on it
            // could be dragged, stretched, tapped out or filled from the rail, which meant that to
            // change anything about Tuesday you first had to go to Tuesday.
            CalendarMode.WEEK, CalendarMode.DAY -> DayWithRail(
                span = if (mode == CalendarMode.DAY) listOf(selected) else TimelineLayout.span(selected, daysAcross),
                day = selected,
                items = items,
                days = days,
                shelves = rail,
                shelf = shelf,
                armed = armed,
                railOpen = railOpen,
                sideBySide = widthDp >= TABLET_WIDTH,
                onShelf = vm::setShelf,
                onArm = vm::arm,
                onOpenTask = { nav.navigate(Routes.node(it)) },
                onOpen = { openItem(it, vm, scope, nav, context, { theirs = it }) { t -> sheet = t } },
                onNewEvent = { onDay, at -> vm.select(onDay); sheet = EventSheetTarget(null, null, at) },
                onMark = { from, to -> vm.select(from.toLocalDate()); marked = from..to },
                onSit = vm::createSitting,
                onSpan = vm::spanTo,
                onSelectDay = vm::select,
                onHourHeight = { hourHeight = it; saveHourHeight(context, it) },
                modifier = Modifier.weight(1f),
            )
        }

        if (mode == CalendarMode.MONTH) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected.format(DAY_LABEL),
                fontSize = 12.sp,
                fontWeight = FontWeight.W700,
                color = y.textDim,
                modifier = Modifier.weight(1f),
            )
        }

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
                    DayRow(item) { openItem(item, vm, scope, nav, context, { theirs = it }) { t -> sheet = t } }
                }
            }
        }
        }
        }
    }

    }

    sheet?.let { target ->
        EventSheet(
            initial = target.event,
            day = selected,
            atTime = target.at,
            length = target.length,
            forTitle = target.forTitle,
            onOpenTask = target.event?.forTaskId?.let { id -> { nav.navigate(Routes.node(id)) } },
            onOpenNotes = target.nodeId?.let { id -> { nav.navigate(Routes.node(id)) } },
            onSave = { vm.save(target.nodeId, it) },
            onDelete = target.nodeId?.let { id -> { vm.delete(id) } },
            onDismiss = { sheet = null },
        )
    }

    theirs?.let { item ->
        DeviceEventSheet(
            item = item,
            onOpenInCalendar = {
                runCatching { context.startActivity(vm.intentFor(item)) }
                theirs = null
            },
            // Straight into the page, because the reason to start a note is that you have
            // something to write. An existing one opens rather than a second being made.
            onNotes = {
                val existing = item.noteId
                if (existing != null) nav.navigate(Routes.node(existing))
                else vm.takeNotesOn(item) { id -> nav.navigate(Routes.node(id)) }
                theirs = null
            },
            onDismiss = { theirs = null },
        )
    }

    marked?.let { range ->
        PickForRange(
            shelves = rail,
            shelf = shelf,
            onShelf = vm::setShelf,
            from = range.start,
            to = range.endInclusive,
            onTask = { task ->
                vm.createSitting(task.nodeId, range.start, java.time.Duration.between(range.start, range.endInclusive))
                marked = null
            },
            onEvent = {
                sheet = EventSheetTarget(
                    null, null, range.start.toLocalTime(),
                    java.time.Duration.between(range.start, range.endInclusive),
                )
                marked = null
            },
            onDismiss = { marked = null },
        )
    }
}

private val MARKED_RANGE = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Mark the time, then say what it is for — CALENDAR_PLAN.md §13B.
 *
 * The inverse of the rail, and it suits the opposite mood: not "where does this task go" but "I have
 * two free hours on Thursday afternoon, what should be in them?". The same four buckets, because a
 * second way of listing the same tasks is a second thing to learn.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PickForRange(
    shelves: Map<RailBucket, List<ie.shoonya.yantra.data.db.RailTask>>,
    shelf: RailBucket,
    onShelf: (RailBucket) -> Unit,
    from: java.time.LocalDateTime,
    to: java.time.LocalDateTime,
    onTask: (ie.shoonya.yantra.data.db.RailTask) -> Unit,
    onEvent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val y = Yantra.colors
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = y.page) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                "${from.format(MARKED_RANGE)}–${to.format(MARKED_RANGE)}",
                fontFamily = YantraMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 1.sp,
                color = y.accent,
            )
            Text(
                "What is this time for?",
                fontSize = 19.sp,
                fontWeight = FontWeight.W700,
                color = y.textPrimary,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            // The same control as the rail, so the two ways in are two moods rather than two
            // interfaces. Armed is null throughout: here a tap *is* the answer, so there is no
            // intermediate state to hold and nothing to un-arm.
            TaskRail(
                shelves = shelves,
                shelf = shelf,
                armed = null,
                onShelf = onShelf,
                onArm = { it?.let(onTask) },
                onOpen = { },
                modifier = Modifier.weight(1f, fill = false).padding(bottom = 4.dp),
            )
            RailDividerHorizontal()
            Text(
                "Something else — make an event",
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
                color = y.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEvent)
                    .padding(vertical = 14.dp),
            )
        }
    }
}

/** What the sheet is open on: an existing event, or nothing at all for a new one. */
private data class EventSheetTarget(
    val nodeId: String?,
    val event: ie.shoonya.yantra.data.format.EventRef?,
    val at: java.time.LocalTime? = null,
    /** The length a drag asked for, if that is how this was opened. */
    val length: java.time.Duration? = null,
    /** The title a sitting borrows, resolved before the sheet opens. */
    val forTitle: String? = null,
)

/**
 * An event opens where it can be changed; a task opens the page it lives on.
 *
 * A task's time is one of many things about it and the rest of them are on its page, so sending a
 * tap to a sheet that could only edit the hour would be the wrong half of the task.
 */
private fun openItem(
    item: DayItem,
    vm: CalendarViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    nav: NavHostController,
    context: android.content.Context,
    onDevice: (DayItem.Device) -> Unit,
    open: (EventSheetTarget) -> Unit,
) {
    // Somebody else's event opens *here*, read-only, with the two things you can actually do about
    // it. It used to hand the occurrence straight to the calendar that owns it, which is one of
    // those two and a poor way to offer it: a tap that throws you into another application is not a
    // choice, and it left no way to act on the thing from inside this one.
    if (item is DayItem.Device) {
        onDevice(item)
        return
    }
    if (item is DayItem.Event) {
        scope.launch {
            val event = vm.eventFor(item.nodeId) ?: return@launch
            // A sitting opens on its own terms, not the task's: the thing you tapped was a piece of
            // time, and moving or giving it back is what you came to do. The task is one row away.
            open(EventSheetTarget(item.nodeId, event, forTitle = event.forTaskId?.let { vm.titleOf(it) }))
        }
    } else {
        nav.navigate(Routes.node(item.nodeId))
    }
}

@Composable
private fun MonthBar(
    month: YearMonth,
    selected: LocalDate,
    mode: CalendarMode,
    days: Int,
    railOpen: Boolean,
    onRail: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val y = Yantra.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            heading(mode, month, selected, days),
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
            color = y.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // On both timelines, because both of them are places you plan. Shown as a state rather
        // than an icon: "Tasks" lit means they are beside you, unlit means the days have the screen.
        if (mode != CalendarMode.MONTH) {
            Text(
                "Tasks",
                fontSize = 12.sp,
                fontWeight = FontWeight.W700,
                color = if (railOpen) y.accentText else y.textMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (railOpen) Modifier.background(y.accentFill) else Modifier)
                    .clickable(onClick = onRail)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("railToggle"),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            "Today",
            fontSize = 12.sp,
            fontWeight = FontWeight.W700,
            color = y.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToday)
                .padding(horizontal = 8.dp, vertical = 6.dp),
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
                is DayItem.Device -> if (item.allDay) "all day" else item.start.format(TIME)
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
                // Named as somebody else's, because there is nothing you can do to it from here and
                // a row that looked like yours would invite the attempt.
                is DayItem.Device -> listOfNotNull("From your calendar", item.location).joinToString(" · ")
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

/** Month, week or day — one pill with three segments, in the header's actions. */
@Composable
private fun ModeSwitch(mode: CalendarMode, days: Int, onMode: (CalendarMode) -> Unit) {
    val y = Yantra.colors
    // One pill with three segments, rather than three loose letters. It reads as a single
    // control with a current state, it is narrower than three separate chips, and every segment
    // is a real touch target — the letters alone were about 22dp wide and flush against each
    // other, so a miss landed on the neighbour rather than on nothing.
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(y.cardBg)
            .padding(2.dp),
    ) {
        CalendarMode.entries.forEach { m ->
            val on = m == mode
            Box(
                Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (on) Modifier.background(y.accentFill) else Modifier)
                    .clickable { onMode(m) },
                contentAlignment = Alignment.Center,
            ) {
                // Sized to its word rather than to a guess: "Month" does not fit a 38dp box,
                // and a segment that clips its own label is worse than a cryptic letter.
                Text(
                    // The segment says what it will actually show. "Week" on a phone that gives you
                    // three days is a label that lies about the button underneath it.
                    if (m == CalendarMode.WEEK && days < 7) "$days days" else m.label,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.W700 else FontWeight.W500,
                    color = if (on) y.accentText else y.textMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }
    }
}
