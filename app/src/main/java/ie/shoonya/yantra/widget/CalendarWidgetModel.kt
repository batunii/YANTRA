package ie.shoonya.yantra.widget

import ie.shoonya.yantra.ui.calendar.CalendarDays
import ie.shoonya.yantra.ui.calendar.DayItem
import ie.shoonya.yantra.ui.calendar.monthGrid
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The three amounts of calendar a home screen can hold.
 *
 * The same three the app has, and deliberately the same three: a widget that showed a fourth shape
 * would be a second calendar to learn. [store] is what goes in Glance state — the enum's own name
 * would do until somebody reorders it, and a widget already on a home screen would then quietly
 * change view.
 */
enum class CalendarWidgetView(val store: String) {
    MONTH("month"),
    THREE_DAY("3day"),
    DAY("day"),
    ;

    /** What "next" means here — a month, three days, or a day. Paging steps by what you are looking at. */
    fun step(from: LocalDate, forward: Int): LocalDate = when (this) {
        MONTH -> from.plusMonths(forward.toLong())
        THREE_DAY -> from.plusDays((forward * DAYS_ACROSS).toLong())
        DAY -> from.plusDays(forward.toLong())
    }

    companion object {
        const val DAYS_ACROSS = 3

        fun of(stored: String?): CalendarWidgetView =
            entries.firstOrNull { it.store == stored } ?: DAY
    }
}

/** One square of the month grid. */
data class WidgetDayCell(
    val date: LocalDate,
    /** False for the neighbouring months' days, which are drawn dim so the grid keeps its shape. */
    val inMonth: Boolean,
    val isToday: Boolean,
    /** How many things land on it, for the marks under the numeral. */
    val count: Int,
)

/** One line of an agenda: an event, somebody else's meeting, or a task that is due. */
data class WidgetAgendaRow(
    /**
     * What tapping it should open, or null when there is nothing of ours behind it.
     *
     * A device event has no node and never gets a synthetic id — see [DayItem.Device]. Those rows
     * open the day instead, which is the honest answer: we can show their meeting and we cannot
     * open it.
     */
    val nodeId: String?,
    val title: String,
    /** `9:30`, `2:00p`, or null for something that takes the whole day. */
    val time: String?,
    /**
     * The same hour as a number of minutes from midnight — null when [time] is.
     *
     * Carried rather than re-read off [time], because [time] is *formatted*: it is `2:00p` in a
     * twelve-hour locale, and the renderer that parsed it back into an hour to decide which line
     * was next read that as two o'clock in the morning. A formatted string is for a person.
     */
    val startMin: Int?,
    val allDay: Boolean,
    val done: Boolean,
    /** A stored [ie.shoonya.yantra.data.label.LabelPalette] value — resolved to a twin at render. */
    val tint: Long?,
    /** Their calendar's own colour, drawn as-is: it is that calendar's identity, not ours. */
    val deviceColor: Int?,
    val isTask: Boolean,
)

/** One day of an agenda — the whole widget in Day view, a third of it in 3-day. */
data class WidgetDayColumn(
    val date: LocalDate,
    /** `FRI` */
    val weekday: String,
    val dayNumber: String,
    val isToday: Boolean,
    val rows: List<WidgetAgendaRow>,
    /** How many more there were than would fit, so the column can say `+2` rather than lie. */
    val more: Int,
)

/**
 * When the quiet ends: the first thing after the days on screen.
 *
 * The empty day is this widget's most common state, and "Nothing on." answers half a question. The
 * half that is worth the line is *when does that stop being true* — so an empty Friday says what
 * Tuesday holds, which is the fact a person is actually reaching for.
 */
data class WidgetNextUp(
    val date: LocalDate,
    /** `Tue 22` — formatted here, never in a composable, because a composable cannot see a Locale. */
    val label: String,
    val title: String,
)

/** Everything one render of the widget needs, with no Glance or Compose type in sight. */
data class CalendarWidgetData(
    val heading: String,
    /** The word above the heading — the container the heading names a position inside. */
    val eyebrow: String,
    /** The month grid — empty in the agenda views. */
    val cells: List<WidgetDayCell>,
    /** The day columns — one in Day, three in 3-day, and the selected day alone under a month. */
    val columns: List<WidgetDayColumn>,
    /** The first thing after the days on screen, for an empty day to point at. */
    val nextUp: WidgetNextUp?,
    /** False until the first real read lands, so the widget can say "…" instead of "nothing on". */
    val ready: Boolean,
)

/**
 * The time as a newspaper listing prints it, not as a clock does.
 *
 * A right-aligned gutter already carries the column; the leading zero on `09:30` is a character
 * that changes nothing, and in a 12-hour locale `9:30 AM` is three. So: `9:30` and `14:00` where
 * the day has twenty-four hours, `9:30a` and `2:00p` where it has twelve.
 *
 * Which of those a locale uses is read from the locale's own short-time pattern rather than from
 * the phone's 24-hour setting, because this object may not touch a Context — see [CalendarBucketer]
 * for the same rule and the same reason.
 */
private fun timeIn(locale: Locale): DateTimeFormatter {
    val pattern = java.time.format.DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        null, java.time.format.FormatStyle.SHORT, java.time.chrono.IsoChronology.INSTANCE, locale,
    )
    return if (pattern.contains('a') || pattern.contains('h')) {
        DateTimeFormatter.ofPattern("h:mm", locale)
    } else {
        DateTimeFormatter.ofPattern("H:mm", locale)
    }
}

/** The one-letter half-day suffix a 12-hour locale needs, and nothing where it does not. */
private fun meridiem(locale: Locale, hour: Int): String {
    val pattern = java.time.format.DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        null, java.time.format.FormatStyle.SHORT, java.time.chrono.IsoChronology.INSTANCE, locale,
    )
    if (!pattern.contains('a') && !pattern.contains('h')) return ""
    return if (hour < 12) "a" else "p"
}

/**
 * Turning a bucketed month into the shapes a widget draws.
 *
 * Pure, and for the reason [ie.shoonya.yantra.ui.calendar.CalendarBucketer] is: the awkward parts
 * here are the window a view asks for and what happens at its edges — a 3-day view that starts on
 * the 30th, a month grid that has to reach into both neighbours, an event that began yesterday and
 * has no honest start time on today's column. None of that needs a launcher to be checked, and all
 * of it is easy to get wrong once and never see, because a widget is the one surface nobody is
 * looking at when it is wrong.
 */
object CalendarWidgetShape {

    /**
     * The days a view covers, as `[from, toExclusive)`.
     *
     * The month view asks for the whole **grid**, not the month: the trailing days of the previous
     * month are on screen, and a cell drawn without its marks reads as an empty day rather than as
     * one this query did not ask about.
     */
    fun window(view: CalendarWidgetView, anchor: LocalDate): Pair<LocalDate, LocalDate> =
        when (view) {
            CalendarWidgetView.MONTH -> {
                val grid = monthGrid(anchor.withDayOfMonth(1))
                grid.first() to grid.last().plusDays(1)
            }
            // The agenda views read past their own days, so an empty one can say when the quiet
            // ends. A fortnight and a bit: far enough that "nothing in the next two weeks" is a
            // true and useful sentence, and still a cheaper query than the 42 days a month asks
            // for every time it is drawn.
            CalendarWidgetView.THREE_DAY ->
                anchor to anchor.plusDays(CalendarWidgetView.DAYS_ACROSS + LOOKAHEAD)
            CalendarWidgetView.DAY -> anchor to anchor.plusDays(1 + LOOKAHEAD)
        }

    /** How far past the visible days the agenda views read, for [WidgetNextUp]. */
    const val LOOKAHEAD = 15L

    /**
     * The masthead, in two lines: the container, then the position inside it.
     *
     * The same form in all three views, because the masthead is the one thing a reader has to
     * learn and switching view must not re-design it. The eyebrow is quiet and uppercase; the
     * heading is the only bold thing on the surface.
     */
    fun eyebrow(
        view: CalendarWidgetView,
        anchor: LocalDate,
        today: LocalDate,
        locale: Locale,
    ): String = when (view) {
        // The year, always. It is the one constant that earns its line in a month view, and a
        // masthead that changes shape between views is worth less than a quiet repeated year.
        CalendarWidgetView.MONTH -> anchor.year.toString()
        CalendarWidgetView.DAY ->
            if (anchor == today) "TODAY"
            else anchor.dayOfWeek.getDisplayName(TextStyle.FULL, locale).uppercase(locale)
        CalendarWidgetView.THREE_DAY -> {
            val last = anchor.plusDays((CalendarWidgetView.DAYS_ACROSS - 1).toLong())
            val first = anchor.month.getDisplayName(TextStyle.FULL, locale).uppercase(locale)
            if (last.month == anchor.month) first
            else "$first – ${last.month.getDisplayName(TextStyle.SHORT, locale).uppercase(locale)}"
        }
    }

    /** What the header says: the month, the day, or the span the three days cover. */
    fun heading(
        view: CalendarWidgetView,
        anchor: LocalDate,
        today: LocalDate,
        locale: Locale,
        compact: Boolean = false,
    ): String {
        val monthOf = { d: LocalDate ->
            d.month.getDisplayName(if (compact) TextStyle.SHORT else TextStyle.FULL, locale)
        }
        return when (view) {
            CalendarWidgetView.MONTH -> monthOf(anchor)
            CalendarWidgetView.DAY ->
                "${anchor.dayOfMonth} ${monthOf(anchor)}" +
                    // The year only when it is not the one you are living in.
                    if (anchor.year != today.year) " ${anchor.year}" else ""
            CalendarWidgetView.THREE_DAY -> {
                val last = anchor.plusDays((CalendarWidgetView.DAYS_ACROSS - 1).toLong())
                // Name the month once when both ends share it, twice when they do not —
                // "30 Sep – 2 Oct" is the one case where repeating it is the only way to be clear.
                if (last.month == anchor.month) "${anchor.dayOfMonth} – ${last.dayOfMonth}"
                else "${anchor.dayOfMonth} ${monthOf(anchor)} – ${last.dayOfMonth} ${monthOf(last)}"
            }
        }
    }

    /** The first thing strictly after [after], for an empty day to point at. */
    fun nextUp(days: CalendarDays, after: LocalDate, locale: Locale): WidgetNextUp? {
        val label = java.time.format.DateTimeFormatter.ofPattern("EEE d", locale)
        return days.keys.filter { it > after }.sorted().firstNotNullOfOrNull { date ->
            days[date].orEmpty().sortedBy { it.sortKey }
                .firstOrNull { !cancelled(it) }
                ?.let { WidgetNextUp(date, date.format(label), it.title) }
        }
    }

    /** The 42 squares of a month, marked with today and a count. */
    fun cells(
        anchor: LocalDate,
        today: LocalDate,
        days: CalendarDays,
    ): List<WidgetDayCell> {
        val month = anchor.withDayOfMonth(1)
        return monthGrid(month).map { d ->
            WidgetDayCell(
                date = d,
                inMonth = d.month == month.month && d.year == month.year,
                isToday = d == today,
                // Cancelled events are still in the bucket because the day view strikes them
                // through; a mark under a numeral cannot be struck through, so they are not counted.
                count = days[d]?.count { !cancelled(it) } ?: 0,
            )
        }
    }

    /**
     * The agenda columns for a view.
     *
     * [limit] is how many lines one column has room for. What overflows is counted rather than
     * dropped — `+3` is a small thing to draw and the difference between a quiet day and a full one.
     */
    fun columns(
        view: CalendarWidgetView,
        anchor: LocalDate,
        today: LocalDate,
        days: CalendarDays,
        locale: Locale,
        limit: Int,
    ): List<WidgetDayColumn> {
        val dates = when (view) {
            // A month draws a grid and nothing under it, so it asks for no columns at all.
            CalendarWidgetView.MONTH -> emptyList()
            CalendarWidgetView.DAY -> listOf(anchor)
            CalendarWidgetView.THREE_DAY ->
                (0 until CalendarWidgetView.DAYS_ACROSS).map { anchor.plusDays(it.toLong()) }
        }
        return dates.map { date ->
            val all = rows(days[date].orEmpty(), date, locale)
            WidgetDayColumn(
                date = date,
                weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                dayNumber = date.dayOfMonth.toString(),
                isToday = date == today,
                rows = all.take(limit),
                more = (all.size - limit).coerceAtLeast(0),
            )
        }
    }

    /**
     * One day's things, in the order a day happens.
     *
     * All-day first, then by time — the same order [DayItem.sortKey] already gives, sorted here
     * again because the bucket is keyed by day and nothing downstream has promised to keep it.
     */
    fun rows(
        items: List<DayItem>,
        day: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): List<WidgetAgendaRow> = items.sortedBy { it.sortKey }.map { item -> row(item, day, locale) }

    private fun row(item: DayItem, day: LocalDate, locale: Locale): WidgetAgendaRow = when (item) {
        is DayItem.Event -> WidgetAgendaRow(
            nodeId = item.forTaskId ?: item.nodeId,
            title = item.title,
            time = startOn(item.allDay, item.start.toLocalDate(), item.start.toLocalTime(), day, locale),
            startMin = minutesOn(item.allDay, item.start.toLocalDate(), item.start.toLocalTime(), day),
            allDay = item.allDay,
            done = item.cancelled,
            tint = item.tint,
            deviceColor = null,
            isTask = false,
        )
        is DayItem.Device -> WidgetAgendaRow(
            // Their meeting opens whatever line of ours is about it, and nothing at all when there
            // is none — see [DayItem.Device].
            nodeId = item.taskId ?: item.noteId,
            title = item.taskTitle ?: item.title,
            time = startOn(item.allDay, item.start.toLocalDate(), item.start.toLocalTime(), day, locale),
            startMin = minutesOn(item.allDay, item.start.toLocalDate(), item.start.toLocalTime(), day),
            allDay = item.allDay,
            done = false,
            tint = null,
            deviceColor = item.color,
            isTask = false,
        )
        is DayItem.Task -> WidgetAgendaRow(
            nodeId = item.nodeId,
            title = item.title,
            // A task with no time is not an all-day thing — it is a thing owed by the end of the
            // day — so it says so rather than sitting in the all-day band pretending to be an event.
            time = if (item.hasTime) clock(item.at.toLocalTime(), locale) else null,
            startMin = if (item.hasTime) item.at.toLocalTime().let { it.hour * 60 + it.minute } else null,
            allDay = false,
            done = item.done,
            tint = null,
            deviceColor = null,
            isTask = true,
        )
    }

    /**
     * The start time to print on [day]'s line.
     *
     * Null when there is none worth printing: an all-day thing has no hour, and something that
     * began yesterday has no honest start on today — printing its real start would put `22:00` at
     * the top of a morning.
     */
    private fun startOn(
        allDay: Boolean,
        startDay: LocalDate,
        start: LocalTime,
        day: LocalDate,
        locale: Locale,
    ): String? = when {
        allDay -> null
        startDay != day -> null
        else -> clock(start, locale)
    }

    /** The same test [startOn] makes, answered as a number. */
    private fun minutesOn(
        allDay: Boolean,
        startDay: LocalDate,
        start: LocalTime,
        day: LocalDate,
    ): Int? = if (allDay || startDay != day) null else start.hour * 60 + start.minute

    /** One time, in the gutter's form. */
    private fun clock(at: LocalTime, locale: Locale): String =
        at.format(timeIn(locale)) + meridiem(locale, at.hour)

    private fun cancelled(item: DayItem): Boolean =
        item is DayItem.Event && item.cancelled
}
