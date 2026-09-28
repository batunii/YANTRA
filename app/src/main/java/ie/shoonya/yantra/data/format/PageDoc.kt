package ie.shoonya.yantra.data.format

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A page, as it exists in the repo — see GIT_WORKSPACES_PLAN.md §2.
 *
 * The shape follows from one observation: **a task is a line on its parent's page, and its page is
 * a separate document.** Title, status, indent and position belong to the line; this document holds
 * what the chevron opens. Nothing is stored in both places, so nothing can drift out of agreement.
 *
 * Ordering is line position. There is no `rank` here — the index regenerates one on import, which
 * means reordering rewrites a file rather than renaming anything, and git merges it as a text edit.
 */
data class PageDoc(
    val id: String,
    val type: String,                 // ie.shoonya.yantra.data.db.NodeType
    val parent: String?,
    /**
     * Authoritative **only when [parent] is null**.
     *
     * A task's title belongs to its line on the parent's page, not to its own file — that is what
     * "nothing is stored twice" means. A top-level list or group has no line anywhere, so its name
     * has to live here. A title found on a parented page is kept (nothing unrecognised is dropped)
     * but the line wins, and the app does not write one.
     */
    val title: String?,
    /** [ie.shoonya.yantra.data.db.SystemKey] — `today`, `inbox`. Must survive the round trip. */
    val systemKey: String? = null,
    val modifiedAt: Instant,
    val device: String?,
    val blocks: List<Block>,
    /**
     * Frontmatter keys this version does not understand, in file order.
     *
     * Kept so a page written by a newer app survives a round-trip through an older one. Dropping
     * them silently is how "it worked on my phone and lost a field on my laptop" happens.
     */
    val unknownKeys: Map<String, String> = emptyMap(),
    /**
     * The emoji this list wears instead of its drawn mark, or null to keep the mark.
     *
     * In the file for the same reason as [color]: it is a choice somebody made, and choices live
     * where the tasks do — a list given an icon on the phone has it on the laptop, and a dropped
     * database costs nothing. See [ie.shoonya.yantra.data.format.ListIcon] for what one is allowed
     * to be.
     *
     * Kept apart from [color] rather than folded into one "appearance" field, because they are
     * genuinely independent: an emoji carries its own colours, so a list can have a mark and a
     * colour, an emoji and a colour, or neither, and every combination means something.
     */
    val icon: String? = null,
    /**
     * The colour this list wears, as a palette name.
     *
     * In the file, because it is a choice somebody made and the file is where choices live: a list
     * coloured on the phone is the same colour on the laptop, and `rm -rf` on the database costs
     * nothing. See [ie.shoonya.yantra.data.db.NodeEntity.color] for why it is a name.
     *
     * Last in the list, because PageDoc is built positionally in places.
     */
    val color: String? = null,
    /**
     * Whether this list sits at the top of Home, or `null` when the file has never said.
     *
     * Nullable rather than defaulting to false, because "never said" and "said no" have to be told
     * apart: every list written before pinning existed says nothing, and the section used to be
     * "every ungrouped smart list". Reading absence as *false* would have emptied Home's Pinned
     * section for everybody on upgrade. Absence means the old rule; a value means somebody chose.
     */
    val pinned: Boolean? = null,
)

/** How a task line renders its glyph. Mirrors `done` + `in_progress`, which are never both set. */
enum class TaskStatus { OPEN, IN_PROGRESS, DONE }

/** All-day means a calendar date; timed means an exact instant. The distinction is `hasTime`. */
sealed interface DueValue {
    data class AllDay(val date: LocalDate) : DueValue
    data class At(val instant: Instant) : DueValue
}

/**
 * When a task is for, how long it is expected to take, and whether to say anything beforehand.
 *
 * [reminders] is how long before the due moment to say something, in minutes, one per reminder.
 *
 * [duration] is what makes a task drawable on a timeline beside an event — the "time blocking" every
 * calendar app means by the phrase: a task from 14:00 to 15:00 is a block an hour tall, not a dot.
 * It is null for an all-day task and for one that is merely *at* a time, because a moment and a span
 * are different claims and only one of them can be drawn to scale. Blocks overlap freely; nothing
 * here reserves anything.
 */
data class DueSpec(
    val value: DueValue,
    /**
     * Minutes *before* the due moment, one per reminder. Negative means after; empty means none.
     *
     * A list rather than a single offset, because one warning is not always the right number of
     * warnings: half an hour before is useful for getting to a thing, and a day before is what
     * stops you from having nothing ready when you get there. They answer different questions and
     * neither replaces the other.
     *
     * **Kept sorted, largest first, and distinct.** The order is the order they fire in, so it is
     * the order a person reads them in — and two devices holding the same task have to produce the
     * same bytes or every sync is a diff about nothing. [of] is the only way one should be built.
     */
    val reminders: List<Int> = emptyList(),
    val duration: java.time.Duration? = null,
) {
    /** The first reminder that will fire, for the places that only need to know there is one. */
    val firstReminder: Int? get() = reminders.firstOrNull()

    companion object {
        /** Canonical order and no repeats — the two things that make the bytes stable. */
        fun reminders(offsets: Iterable<Int>): List<Int> = offsets.distinct().sortedDescending()
    }
}

/**
 * One line of a page.
 *
 * [raw] is the source text this block was parsed from, and the emitter prefers it over re-rendering
 * — but only after checking that re-parsing it still yields this exact block. That is what lets a
 * hand-edited file survive the app touching a different line, without anyone having to remember to
 * clear [raw] when they change something.
 */
sealed interface Block {
    val indent: Int
    val raw: String?
}


data class Prose(val text: String, override val indent: Int = 0, override val raw: String? = null) : Block

data class Heading(val text: String, override val indent: Int = 0, override val raw: String? = null) : Block

data class Bullet(val text: String, override val indent: Int = 0, override val raw: String? = null) : Block

/** The ordinal is positional and recomputed on render, so it is deliberately not stored. */
data class Numbered(val text: String, override val indent: Int = 0, override val raw: String? = null) : Block

/**
 * A task on this page. Its own page, if it has one, is the file named by [id].
 *
 * Unrecognised trailing words stay in [title] rather than being extracted into a bag of extras:
 * a token this version cannot read is, as far as it is concerned, part of what the line says, and
 * leaving it in the title means it is written back exactly as it arrived.
 */
data class TaskRef(
    val id: String,
    val title: String,
    val status: TaskStatus = TaskStatus.OPEN,
    override val indent: Int = 0,
    val due: DueSpec? = null,
    val deadline: LocalDate? = null,
    val priority: String? = null,
    val labels: List<String> = emptyList(),
    val assignee: String? = null,
    /**
     * The day this was finished. Absent unless [status] is [TaskStatus.DONE].
     *
     * Nothing recorded this before, which meant the app could not answer "how long has this been
     * done" — and so could not archive on a threshold, could not show what you finished this week,
     * and treated a task completed this morning exactly like one completed last year. A date rather
     * than an instant: the question is always which day, and a full timestamp on every finished line
     * would be noise in a file people read.
     */
    val doneAt: LocalDate? = null,
    /**
     * The meeting **in somebody else's calendar** this task is about — CALENDAR_PLAN.md §22.
     *
     * A task that carries one is a task *about* a meeting, not a copy of it: the day draws one block
     * at the meeting's hours, the due date follows it when it moves, and the meeting's own details
     * are read live rather than written here. What the file keeps is the minimum needed to find it
     * again — a title, a time and this identity.
     */
    val external: ExternalRef? = null,
    override val raw: String? = null,
) : Block

/** Strokes live in `<id>.ink`, the same StrokeCodec blob the database holds today. */
data class InkRef(val id: String, override val indent: Int = 0, override val raw: String? = null) : Block

data class ImageRef(val uri: String, override val indent: Int = 0, override val raw: String? = null) : Block

/**
 * When an event happens.
 *
 * **Local date-time and a zone, deliberately not an [Instant].** An instant is a point on the
 * timeline, which is right for "remind me at this moment" and wrong for anything that repeats: a
 * standup at 09:00 every weekday is 09:00 *local*, and expanding a rule from instants moves it by an
 * hour at every DST boundary while the wall clock stays put. See CALENDAR_PLAN.md §2.1.
 *
 * [zone] being null means **floating** — "09:00 wherever you are". A birthday and a personal
 * reminder are floating; a meeting with someone in another country is not. `CalendarContract` draws
 * the same distinction, so this is not an invention.
 *
 * [end] is **exclusive**, so a duration is `end - start` with no off-by-one. All-day events are
 * written in the file with an *inclusive* last date, because that is what somebody reading the line
 * means by "the 11th to the 13th"; the codec converts, and [PageCodec] is where that seam lives.
 */
data class EventTime(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val zone: ZoneId? = null,
    val allDay: Boolean = false,
) {
    val duration: Duration get() = Duration.between(start, end)

    /** True for a moment rather than a span — a reminder-shaped event. */
    val isInstantaneous: Boolean get() = start == end
}

/**
 * The occurrence of a repeating event that this line replaces.
 *
 * [originalStart] identifies *which* occurrence, and is the start the rule would have produced —
 * not where the override moved it to. Null means "the occurrence starting at this line's own start",
 * which is the common case for a cancellation and keeps that line short.
 */
data class SeriesRef(val id: String, val originalStart: LocalDateTime? = null)

/**
 * The event **in somebody else's calendar** that this line is a note about — CALENDAR_PLAN.md §19.
 *
 * Written `ext:<uid>`, or `ext:<uid>@<occurrence start>` for one occurrence of a repeating one, the
 * same `id@start` grammar [SeriesRef] uses.
 *
 * [uid] is the identity the *sync source* gave the event — its iCalendar UID, or failing that the
 * id the account assigned it. Deliberately **not** the provider's local row id: that is a number
 * this device made up, different on your other phone and gone after a reinstall, so a file carrying
 * one would be claiming a relationship it cannot honour anywhere else. A UID is the same everywhere
 * the event is, which is what makes it safe to write into a repository.
 *
 * A line carrying this is an **annotation, not an event**. It holds a cached title and time so the
 * file reads sensibly and so something still draws when the calendar permission is off or the
 * meeting has gone — but wherever the provider can be read, the provider is the truth and this is
 * the thing that follows it.
 */
data class ExternalRef(val uid: String, val occurrence: LocalDateTime? = null)

/**
 * Something that happens, as opposed to something to be done.
 *
 * An event has a span and no done state — it is not finished, it simply passes. That is why it is
 * not a [TaskRef] with extra fields and not a checkbox variant: there is no box to tick.
 *
 * Written `@ <when> <title> ^<id> <tokens…>`. The marker is `@ ` rather than `* ` because a leading
 * asterisk is a bullet in every markdown editor there is, and a bullet somebody types by hand must
 * not become a meeting — the same argument [PageCodec] already makes about the checkbox.
 *
 * [cancelled] only means anything alongside [series]: it is how one occurrence of a repeat is
 * removed without rewriting the series line, which two devices cancelling two different days would
 * otherwise collide on. See CALENDAR_PLAN.md §2.2.
 */
data class EventRef(
    val id: String,
    val title: String,
    val time: EventTime,
    /**
     * Whose calendar this meeting came off, when it came off one at all.
     *
     * An event node made by tapping a meeting is a note about *your* day: it exists because your
     * phone's calendar has that meeting on it, and the times and the title are read live from the
     * calendar that owns it. Somebody else pulling the repository has no such calendar entry — the
     * line would be a meeting they cannot see, cannot open and did not put there.
     *
     * So it is stamped with the login of the person whose calendar it is, and a workspace only
     * indexes the ones it can claim. The file still syncs, deliberately: it follows you to your own
     * second device, and the day it turns out somebody *should* see it, nothing has to be
     * recovered. Null means nobody claimed it, which is every event written before this and every
     * event somebody typed by hand — those belong to the list, and everyone sees them.
     */
    val author: String? = null,
    /** RFC 5545 subset — see CALENDAR_PLAN.md §4. Stored verbatim, including rules we cannot expand. */
    val rrule: String? = null,
    /**
     * The task this block is time set aside for — a **sitting**. See CALENDAR_PLAN.md §11.
     *
     * A sitting is an ordinary event with a referent, which is why it is a token here rather than a
     * block type of its own: it wants everything an event already has. It carries no [title]; it
     * draws with the task's, because storing the name twice would give you two places to rename it
     * from and one of them would go stale.
     */
    val forTaskId: String? = null,
    /**
     * Somebody else's event that this line annotates — CALENDAR_PLAN.md §19.
     *
     * Non-null makes this line a **note about** a meeting rather than a meeting of your own. The
     * calendar draws one block for the pair, using their times, and tapping it reaches these notes.
     */
    val external: ExternalRef? = null,
    /**
     * What colour it wears, by name — `col:teal`.
     *
     * A **name**, not a value, so the same word can be a slightly different ink on paper and at
     * night. Storing the hex would freeze whichever theme happened to be on when it was chosen, and
     * a light-mode colour on a dark ground is the one that goes muddy.
     *
     * Null means the workspace's colour, which is itself allowed to be nothing — see
     * CALENDAR_PLAN.md §16. A word this build does not recognise is kept as written rather than
     * dropped: an unknown colour should paint nothing, not lose somebody's line.
     */
    val color: String? = null,
    val series: SeriesRef? = null,
    val cancelled: Boolean = false,
    val location: String? = null,
    /** Minutes *before* the start; negative means after. Null is no reminder. Matches [DueSpec]. */
    val reminderMin: Int? = null,
    val labels: List<String> = emptyList(),
    /**
     * Who is involved, as `@name` — the same strings [TaskRef.assignee] uses, and carrying no more
     * than a name. Nothing is sent to anybody; this is a note about who, not an invitation.
     */
    val attendees: List<String> = emptyList(),
    val priority: String? = null,
    override val indent: Int = 0,
    override val raw: String? = null,
) : Block

