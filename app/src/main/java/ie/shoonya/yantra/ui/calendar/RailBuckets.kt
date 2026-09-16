package ie.shoonya.yantra.ui.calendar

import ie.shoonya.yantra.data.db.RailTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The four shelves the task rail pages between — CALENDAR_PLAN.md §13.
 *
 * Buckets rather than a search box, because the rail answers "what should go in this day", and the
 * useful cuts of that question are few and known. A search box answers "where is that thing I am
 * already thinking of", which is a different need and one the app already has elsewhere.
 */
enum class RailBucket(val label: String) {
    /** Due today. What you already said you would do. */
    TODAY("Today"),

    /** A deadline inside the next few days. What is about to become today's problem. */
    SOON("Soon"),

    /**
     * No due date and no deadline. The backlog.
     *
     * The reason the rail exists at all: a task with no date is invisible on a calendar, and it is
     * exactly the task most in need of being given a time.
     */
    UNDATED("Undated"),

    /** Dated, but none of the above — scheduled further out, or overdue and not yet faced. */
    OTHER("Other"),
}

/** How far ahead a deadline still counts as [RailBucket.SOON]. */
const val SOON_DAYS = 3L

/**
 * Which shelf a task belongs on today.
 *
 * Order matters and is deliberate: **today beats soon, and soon beats everything else**, so a task
 * due today with a deadline on Friday appears once, under Today, where you would look for it. A task
 * can only be on one shelf — a rail where things appear twice is a rail you cannot count.
 *
 * Overdue goes to [RailBucket.OTHER] rather than Today. It is tempting to promote it, but "due last
 * Tuesday" is not a claim about today, and quietly relabelling it as today's work is how a planner
 * starts deciding things on your behalf.
 */
fun bucketOf(task: RailTask, today: LocalDate, zone: ZoneId): RailBucket {
    val due = task.dueMillis?.toLocalDate(zone)
    val deadline = task.deadlineMillis?.toLocalDate(zone)
    return when {
        due == today -> RailBucket.TODAY
        deadline != null && !deadline.isBefore(today) &&
            deadline <= today.plusDays(SOON_DAYS) -> RailBucket.SOON
        due == null && deadline == null -> RailBucket.UNDATED
        else -> RailBucket.OTHER
    }
}

/**
 * Every shelf, in order, with its tasks — including the empty ones.
 *
 * Empty shelves are kept so the rail's tabs do not move about as the day goes on. A control whose
 * buttons change position depending on your data is one you cannot learn.
 */
fun railShelves(
    tasks: List<RailTask>,
    today: LocalDate,
    zone: ZoneId,
): Map<RailBucket, List<RailTask>> {
    val byBucket = tasks.groupBy { bucketOf(it, today, zone) }
    return RailBucket.entries.associateWith { bucket ->
        byBucket[bucket].orEmpty().sortedWith(
            // Soonest first where there is a date to sort by, then alphabetically, so the order is
            // stable between openings rather than following whatever was last edited.
            compareBy(
                { it.dueMillis ?: it.deadlineMillis ?: Long.MAX_VALUE },
                { it.title.orEmpty().lowercase() },
            )
        )
    }
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
