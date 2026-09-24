package ie.shoonya.yantra.ui.calendar

import ie.shoonya.yantra.data.db.DueRow
import ie.shoonya.yantra.data.db.EventEntity
import ie.shoonya.yantra.data.format.Links
import ie.shoonya.yantra.data.label.LabelPalette
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
        /** The task this block is time for, when it is a sitting rather than an appointment. */
        val forTaskId: String? = null,
        /**
         * The colour the **block** wears — a stored [LabelPalette] value, swapped for its dark twin
         * at render. Null paints it in the accent.
         *
         * Its own `col:` if the line carries one; failing that, and only for a sitting, the colour
         * of the list the task lives on — a sitting *is* that task, seen as an hour, so it wears
         * what the task wears everywhere else. An appointment nobody coloured has no list to borrow
         * from and stays in the accent, which is what an uncoloured thing looks like here.
         *
         * The workspace is deliberately **not** in this chain any more: it is [workspaceTint], and
         * it is drawn as the spine. One rule cannot answer both "whose day is this in" and "what
         * kind of thing is this" — see [EventTint].
         */
        val tint: Long? = null,
        /**
         * The repository this block came from, as a stored palette value — the **spine**.
         *
         * Null while only one workspace is open, and then the spine falls back to saying what it
         * said before any of this: the accent for something happening, frame ink for a deadline.
         */
        val workspaceTint: Long? = null,
        override val sortKey: Long,
    ) : DayItem

    /**
     * Somebody else's event, read from the phone's calendars — CALENDAR_PLAN.md §5.
     *
     * A separate kind rather than an [Event] with a flag, because the difference is not cosmetic:
     * this one has no node, no file and no id of ours, and **nothing may write to it**. Every
     * gesture that changes a block asks for a node id; giving this one a synthetic one that looked
     * like the others would be inviting exactly the write the whole design forbids.
     *
     * [nodeId] carries the instance id only so a list can key on it, and is deliberately prefixed so
     * anything that treats it as a node id fails loudly rather than writing somewhere strange.
     */
    data class Device(
        override val nodeId: String,
        override val title: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val allDay: Boolean,
        val location: String?,
        /** The owning calendar's own colour, drawn as-is: it is that calendar's identity, not ours. */
        val color: Int?,
        /**
         * The line of yours that links this meeting to a task — CALENDAR_PLAN.md §20.
         *
         * A line **about** their meeting, never a copy of it: there is exactly one block for the
         * pair, and the times it draws at are theirs. Its presence is also what tells the note's own
         * line to stand aside, which is how one meeting stays one block.
         */
        val noteId: String? = null,
        /** The task this meeting is the time for, when one has been made from it — §20. */
        val taskId: String? = null,
        /** What that task is called, when it has been renamed to something other than the meeting. */
        val taskTitle: String? = null,
        /** The identity its sync source gave it, or null when the provider offers none. */
        val uid: String? = null,
        /**
         * Whether the meeting repeats, which decides whether a note names an occurrence.
         *
         * Inferred rather than asked for: the provider expands a rule into instances, so two
         * occurrences sharing a uid in one window is what "repeats" looks like from here.
         */
        val repeating: Boolean = false,
        /** The event, for handing back to the app that owns it. */
        val eventId: Long,
        val beginUtc: Long,
        val endUtc: Long,
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

/**
 * A line of ours that is about somebody else's meeting.
 *
 * [occurrence] is the instance it was written for, present only on a repeat. [remembered] is the
 * time the line itself carries, which is what a one-off has instead — and what lets a line still
 * find its meeting after somebody moved it.
 */
internal data class Linked(
    val nodeId: String,
    val title: String?,
    val occurrence: String?,
    val remembered: String,
) {
    /** How far this line's idea of when is from one of their instances, in minutes. */
    fun gapTo(d: ie.shoonya.yantra.data.device.DeviceEvent, zone: ZoneId): Long =
        gapBetween(occurrence ?: remembered, occurrenceOf(d, zone))
}

/**
 * How far apart two remembered occurrences are, in minutes, for choosing between them.
 *
 * Unparseable either side is infinitely far: a line whose occurrence cannot be read should lose to
 * one that can, rather than win by accident.
 */
internal fun gapBetween(a: String, b: String): Long {
    val left = runCatching { LocalDateTime.parse(a) }.getOrNull() ?: return Long.MAX_VALUE
    val right = runCatching { LocalDateTime.parse(b) }.getOrNull() ?: return Long.MAX_VALUE
    return kotlin.math.abs(java.time.Duration.between(left, right).toMinutes())
}

/**
 * How a line and a meeting are matched — CALENDAR_PLAN.md §19, §25.
 *
 * Identity plus occurrence, because a weekly standup is one UID and fifty-two meetings. Kept for
 * writing a line down; **matching no longer uses it as a key**, for the reason §25 gives.
 */
internal fun externalKey(uid: String, occurrence: String?): String =
    if (occurrence == null) uid else "$uid@$occurrence"

/**
 * When a device event is, in local terms — the one place that decision is made.
 *
 * **All-day is read in UTC, everything else in the reader's zone.** The provider stores an all-day
 * event as UTC midnight to UTC midnight — a date wearing an instant's clothes — so resolving it
 * locally puts a birthday at nine in the morning in Tokyo and at seven the evening *before* in New
 * York. Shared by the drawing and by the cache refresh because those two disagreeing would mean a
 * line being rewritten on every read, for ever.
 */
fun deviceLocalSpan(
    d: ie.shoonya.yantra.data.device.DeviceEvent,
    zone: ZoneId,
): Pair<LocalDateTime, LocalDateTime> {
    val readIn = if (d.allDay) ZoneId.of("UTC") else zone
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(d.beginUtc), readIn) to
        LocalDateTime.ofInstant(Instant.ofEpochMilli(d.endUtc), readIn)
}

/** Which occurrence a device event is, in the form a note writes down. */
internal fun occurrenceOf(
    d: ie.shoonya.yantra.data.device.DeviceEvent,
    zone: ZoneId,
): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(d.beginUtc), zone).toString()

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
        events: List<ie.shoonya.yantra.data.db.EventWithTitle>,
        tasks: List<DueRow>,
        /**
         * Titles by node id. [EventEntity] holds the times and the rule; the words live on the node
         * row beside it, which the calendar reads separately. Passed in rather than looked up, so
         * this stays a function of its arguments.
         */
        titles: Map<String, String>,
        /** Sitting node id → the task it is for. */
        sittingOf: Map<String, String> = emptyMap(),
        /**
         * Occurrences read from the phone's own calendars, already expanded by the provider.
         *
         * They arrive as instants because that is what `Instances` returns, and are put on a day in
         * the reader's zone — which is right: somebody else's meeting happens at a moment, and the
         * question here is which of your days that moment falls in.
         */
        device: List<ie.shoonya.yantra.data.device.DeviceEvent> = emptyList(),
        /**
         * Workspace id → the hue that repository wears, when there is more than one open.
         *
         * Carried straight through to [DayItem.Event.workspaceTint] and drawn as the spine. It used
         * to be the *fallback* for the block's own colour, which meant one rule said two things and
         * a block could not be both somebody's repository and an uncoloured appointment.
         */
        workspaceTints: Map<String, Long> = emptyMap(),
        /**
         * Task id → the colour of the list that task lives on.
         *
         * Only a sitting reads this, and only when its own line says nothing: the block is drawn as
         * the task, so it is coloured as the task. Resolved by the view model from the same query
         * the now player uses, so a task is one colour on both.
         */
        listTints: Map<String, Long> = emptyMap(),
        from: LocalDate,
        toExclusive: LocalDate,
        zone: ZoneId,
    ): CalendarDays {
        val out = HashMap<LocalDate, MutableList<DayItem>>()

        // Notes about somebody else's meetings, by the meeting they are about — CALENDAR_PLAN.md
        // §19. Two things come out of this map, and the second is the important one:
        //
        //  - a device occurrence that has a note carries its node id, so tapping reaches the note;
        //  - and the note's own line is then **not drawn**, because there is one meeting and it
        //    should be one block. Drawing both is the duplicate-and-drift failure this design
        //    exists to avoid: two blocks at the same hour, and the copy staying put the moment the
        //    real meeting moves.
        // Lines of ours about somebody else's meetings — CALENDAR_PLAN.md §25.
        //
        // **Each line chooses its instance; an instance does not choose a line.** That direction is
        // the whole of it. Matching on identity-plus-occurrence deadlocked — the occurrence is the
        // start, the start is what moves, so a line whose remembered time had gone stale could never
        // meet its meeting again and never be corrected, and the day drew the meeting twice for
        // ever. Matching on identity alone went too far the other way: one line about the sixteenth
        // claimed every Monday of a weekly standup.
        //
        // Nearest wins, and only the nearest: a line picks the instance closest to what it
        // remembers, so a meeting moved an hour — or to another day — keeps the page written for it,
        // while the other fifty-one Mondays are left alone.
        val lines: Map<String, List<Linked>> = buildMap<String, MutableList<Linked>> {
            tasks.forEach { t ->
                t.extUid?.let {
                    getOrPut(it) { mutableListOf() } += Linked(
                        nodeId = t.nodeId,
                        title = t.title,
                        occurrence = t.extStart,
                        remembered = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.dueMillis), zone).toString(),
                    )
                }
            }
            events.forEach { e ->
                e.nodeExtUid?.let {
                    getOrPut(it) { mutableListOf() } += Linked(
                        nodeId = e.event.nodeId,
                        title = titles[e.event.nodeId],
                        occurrence = e.nodeExtStart,
                        remembered = e.event.startLocal,
                    )
                }
            }
        }

        val instances = device.filter { it.uid != null }.groupBy { it.uid!! }
        val claimed: Map<Long, Linked> = buildMap {
            lines.forEach { (uid, ours) ->
                val theirs = instances[uid] ?: return@forEach
                ours.forEach { line ->
                    val target = theirs.minByOrNull { line.gapTo(it, zone) } ?: return@forEach
                    val sitting = get(target.instanceId)
                    // Two lines wanting the same instance is a repeat somebody wrote about twice.
                    // The nearer keeps it; the other falls back to drawing its own remembered time.
                    if (sitting == null || line.gapTo(target, zone) < sitting.gapTo(target, zone)) {
                        put(target.instanceId, line)
                    }
                }
            }
        }

        fun noteFor(d: ie.shoonya.yantra.data.device.DeviceEvent): Linked? = claimed[d.instanceId]

        // Which notes have a meeting on screen to be drawn *as*. Computed before anything is
        // emitted, because the note's own line has to know whether to stand aside.
        val annotated = device.mapNotNullTo(HashSet()) { noteFor(it)?.nodeId }

        for (row in events) {
            val e = row.event
            val start = runCatching { LocalDateTime.parse(e.startLocal) }.getOrNull() ?: continue
            val end = runCatching { LocalDateTime.parse(e.endLocal) }.getOrNull() ?: start
            if (e.cancelled) continue        // a cancelled occurrence is an absence, not an entry
            // A note whose meeting is on screen is drawn *as* that meeting, below, so its own line
            // stands aside — one meeting, one block. Its cached times are a fallback for when the
            // meeting cannot be read at all (the permission is off, or it has been deleted), and
            // then it does draw, so notes you wrote never become unreachable.
            if (e.nodeId in annotated) continue

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
                        title = shown(titles[e.nodeId]).ifEmpty { "Event" },
                        start = start,
                        end = end,
                        allDay = e.allDay,
                        location = e.location,
                        repeating = e.rrule != null,
                        cancelled = false,
                        forTaskId = sittingOf[e.nodeId],
                        tint = EventTint.storedOf(e.color)
                            ?: sittingOf[e.nodeId]?.let { listTints[it] },
                        workspaceTint = workspaceTints[e.workspaceId],
                        // All-day first, then by clock. A day reads top to bottom as it happens.
                        sortKey = if (e.allDay) Long.MIN_VALUE else start.toLocalTime().toNanoOfDay(),
                    )
                }
                day = day.plusDays(1)
            }
        }

        // A uid seen more than once in this window is a rule the provider expanded. That is the
        // only evidence of repetition available here — Instances hands back occurrences, not rules.
        val repeats = device.mapNotNull { it.uid }.groupingBy { it }.eachCount()

        for (d in device) {
            val (start, end) = deviceLocalSpan(d, zone)
            val lastDay = when {
                end == start -> start.toLocalDate()
                end.toLocalTime() == java.time.LocalTime.MIDNIGHT -> end.toLocalDate().minusDays(1)
                else -> end.toLocalDate()
            }
            var day = start.toLocalDate()
            while (!day.isAfter(lastDay)) {
                if (!day.isBefore(from) && day.isBefore(toExclusive)) {
                    out.getOrPut(day) { ArrayList() } += DayItem.Device(
                        nodeId = "device:${d.instanceId}",
                        // One node, not two: the line about this meeting *is* the task.
                        noteId = noteFor(d)?.nodeId,
                        taskId = noteFor(d)?.nodeId,
                        taskTitle = noteFor(d)?.title?.takeIf { it.isNotBlank() && it != d.title },
                        uid = d.uid,
                        repeating = (repeats[d.uid] ?: 0) > 1,
                        title = d.title,
                        start = start,
                        end = end,
                        allDay = d.allDay,
                        location = d.location,
                        color = d.color,
                        eventId = d.eventId,
                        beginUtc = d.beginUtc,
                        endUtc = d.endUtc,
                        sortKey = if (d.allDay) Long.MIN_VALUE else start.toLocalTime().toNanoOfDay(),
                    )
                }
                day = day.plusDays(1)
            }
        }

        for (t in tasks) {
            // A task about a meeting that is on screen is drawn *as* that meeting, above — one
            // meeting, one block. Its own due date is what draws when the meeting cannot be read.
            if (t.nodeId in annotated) continue
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.dueMillis), zone)
            val day = at.toLocalDate()
            if (day.isBefore(from) || !day.isBefore(toExclusive)) continue
            out.getOrPut(day) { ArrayList() } += DayItem.Task(
                nodeId = t.nodeId,
                title = shown(t.title).ifEmpty { "Untitled" },
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

    /**
     * A title as a person should read it.
     *
     * A link is stored as `[[Call Bob|^9f1e…]]`, which is the right thing to keep in a file and the
     * wrong thing to put on a block two centimetres wide. Every other surface collapses it — the
     * page, the archive, the stats, the focus screen, the reminder — and the calendar was the one
     * that did not, so a task with a reference on it read as a caret and a UUID on the day, in the
     * rail, and in the month cell.
     *
     * Collapsed without a resolver, so it shows the label the file carries rather than the target's
     * current title. That is what the rest of the app does off the page, and it is the honest
     * answer here: the bucketer is handed the day's rows, not the index, and a link may point into
     * a workspace this device has not added.
     */
    private fun shown(title: String?) = Links.plain(title.orEmpty())
}

/** The six-week grid a month is drawn on: the Monday on or before the 1st, then 42 days. */
fun monthGrid(month: LocalDate): List<LocalDate> {
    val first = month.withDayOfMonth(1)
    // `dayOfWeek.value` is 1 for Monday, so this backs up to the Monday of the first week and is
    // a no-op when the 1st already is one.
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    return (0 until 42).map { start.plusDays(it.toLong()) }
}
