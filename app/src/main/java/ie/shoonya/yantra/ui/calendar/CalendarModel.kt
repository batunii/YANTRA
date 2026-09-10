package ie.shoonya.yantra.ui.calendar

import ie.shoonya.yantra.data.db.DueRow
import ie.shoonya.yantra.data.db.EventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One thing on a day, whatever kind of thing it is.
 *
 * Events and due tasks share a list because they share a question — "what is happening on the
 * eleventh" — and answering it from two lists that the screen then has to interleave is how the two
 * end up sorted differently. See CALENDAR_PLAN.md §6.
 */
sealed interface DayItem {
    val nodeId: String
    val title: String

    /** Where it sorts within the day. All-day things come first, then by time. */
    val sortKey: Long

    data class Event(
        override val nodeId: String,
        override val title: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val allDay: Boolean,
        val location: String?,
        /** True when this is one line of a repeat — the screen marks it, see [CalendarDays]. */
        val repeating: Boolean,
        val cancelled: Boolean,
        override val sortKey: Long,
    ) : DayItem

    data class Task(
        override val nodeId: String,
        override val title: String,
        val at: LocalDateTime,
        val hasTime: Boolean,
        val done: Boolean,
        /** Minutes blocked out for it, or null for a moment. What makes it drawable to scale. */
        val durationMin: Int? = null,
        override val sortKey: Long,
    ) : DayItem
}

/** What a month's worth of days holds, keyed by date. Days with nothing on them are absent. */
typealias CalendarDays = Map<LocalDate, List<DayItem>>

/**
 * Buckets events and due tasks into the days they land on.
 *
 * Pure, and deliberately so: this is where the off-by-one lives — a multi-day event has to appear on
 * every day it covers, an all-day event's exclusive end must not add a phantom day, and a task due
 * at midnight belongs to that day rather than the one before. None of that needs a screen to be
 * checked, and all of it is easy to get wrong once and never notice.
 *
 * **Recurring events appear only on their own first occurrence.** The row holds one span and
 * [ie.shoonya.yantra.data.db.EventDao.inRange] returns every rule regardless of window, so anything
 * outside the visible range is dropped here rather than drawn in the wrong month. Later occurrences
 * arrive with expansion — CALENDAR_PLAN.md §4 — and until then the screen marks a repeating event so
 * the gap is visible rather than silent.
 */
object CalendarBucketer {

    fun bucket(
        events: List<EventEntity>,
        tasks: List<DueRow>,
        /**
         * Titles by node id. [EventEntity] holds the times and the rule; the words live on the node
         * row beside it, which the calendar reads separately. Passed in rather than looked up, so
         * this stays a function of its arguments.
         */
        titles: Map<String, String>,
        from: LocalDate,
        toExclusive: LocalDate,
        zone: ZoneId,
    ): CalendarDays {
        val out = HashMap<LocalDate, MutableList<DayItem>>()

        for (e in events) {
            val start = runCatching { LocalDateTime.parse(e.startLocal) }.getOrNull() ?: continue
            val end = runCatching { LocalDateTime.parse(e.endLocal) }.getOrNull() ?: start
            if (e.cancelled) continue        // a cancelled occurrence is an absence, not an entry

            // The exclusive end means a one-day all-day event ends at 00:00 the next morning, and
            // an hour-long meeting ending at exactly midnight belongs to the day it started.
            val lastDay = when {
                end == start -> start.toLocalDate()
                end.toLocalTime() == java.time.LocalTime.MIDNIGHT -> end.toLocalDate().minusDays(1)
                else -> end.toLocalDate()
            }

            var day = start.toLocalDate()
            while (!day.isAfter(lastDay)) {
                if (!day.isBefore(from) && day.isBefore(toExclusive)) {
                    out.getOrPut(day) { ArrayList() } += DayItem.Event(
                        nodeId = e.nodeId,
                        title = titles[e.nodeId].orEmpty().ifEmpty { "Event" },
                        start = start,
                        end = end,
                        allDay = e.allDay,
                        location = e.location,
                        repeating = e.rrule != null,
                        cancelled = false,
                        // All-day first, then by clock. A day reads top to bottom as it happens.
                        sortKey = if (e.allDay) Long.MIN_VALUE else start.toLocalTime().toNanoOfDay(),
                    )
                }
                day = day.plusDays(1)
            }
        }

        for (t in tasks) {
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.dueMillis), zone)
            val day = at.toLocalDate()
            if (day.isBefore(from) || !day.isBefore(toExclusive)) continue
            out.getOrPut(day) { ArrayList() } += DayItem.Task(
                nodeId = t.nodeId,
                title = t.title.orEmpty().ifEmpty { "Untitled" },
                at = at,
                hasTime = t.hasTime,
                done = t.done,
                durationMin = t.durationMin,
                // An undated-within-the-day task sorts after everything timed, because "today" is
                // weaker than "today at three" and a list that mixes them reads as if it were not.
                sortKey = if (t.hasTime) at.toLocalTime().toNanoOfDay() else Long.MAX_VALUE,
            )
        }

        return out.mapValues { (_, items) ->
            items.sortedWith(compareBy({ it.sortKey }, { it.title }))
        }
    }

}

/** The six-week grid a month is drawn on: the Monday on or before the 1st, then 42 days. */
fun monthGrid(month: LocalDate): List<LocalDate> {
    val first = month.withDayOfMonth(1)
    // `dayOfWeek.value` is 1 for Monday, so this backs up to the Monday of the first week and is
    // a no-op when the 1st already is one.
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    return (0 until 42).map { start.plusDays(it.toLong()) }
}
