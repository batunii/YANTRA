package ie.shoonya.yantra.ui.calendar

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One block on a timeline: what it is, and where it sits once overlaps are resolved.
 *
 * [column] and [columns] are a fraction of the day's width — block *n* of *m* across. A thing alone
 * in its hour is `0 of 1` and spans the lot; two at once are `0 of 2` and `1 of 2`, side by side.
 */
data class TimedBlock(
    val item: DayItem,
    val startMinute: Int,
    val endMinute: Int,
    val column: Int,
    val columns: Int,
)

/** What a day looks like once split into the bar at the top and the timeline underneath. */
data class TimelineDay(
    /** All-day, cross-day, and anything with no time of its own. Drawn as chips above the ruler. */
    val allDay: List<DayItem>,
    val blocks: List<TimedBlock>,
)

/**
 * Turns a day's items into blocks that do not sit on top of each other.
 *
 * **Overlaps share the width rather than hiding each other.** Nothing here reserves time — two
 * things genuinely can happen at once, and a calendar that refused to draw the second one would be
 * lying about the clash rather than showing it. This is the same arrangement every calendar makes:
 * find each run of mutually-overlapping items, and give that run as many columns as its busiest
 * moment needs.
 *
 * The grouping is by **cluster**, not by pair. Two blocks that do not touch each other can still
 * need separate columns because a third overlaps both, so widths are decided per connected run —
 * deciding them pairwise gives a layout that jumps as you scroll past the middle item.
 */
object TimelineLayout {

    const val MINUTES_IN_DAY = 24 * 60

    /** Nothing shorter than this gets drawn thinner — a five-minute block still needs to be tappable. */
    const val MIN_BLOCK_MINUTES = 20

    fun forDay(items: List<DayItem>, day: java.time.LocalDate): TimelineDay {
        val allDay = ArrayList<DayItem>()
        val timed = ArrayList<Triple<DayItem, Int, Int>>()

        for (item in items) {
            val span = spanOf(item, day)
            if (span == null) allDay += item else timed += Triple(item, span.first, span.second)
        }

        // Start first, then longest, so the thing that began earliest takes the leftmost column and
        // the eye can follow a day down its left edge.
        timed.sortWith(compareBy({ it.second }, { -(it.third - it.second) }))

        val blocks = ArrayList<TimedBlock>()
        var i = 0
        while (i < timed.size) {
            // One cluster: keep taking while anything still overlaps the run so far.
            var clusterEnd = timed[i].third
            var j = i + 1
            while (j < timed.size && timed[j].second < clusterEnd) {
                clusterEnd = maxOf(clusterEnd, timed[j].third)
                j++
            }
            blocks += packCluster(timed.subList(i, j))
            i = j
        }
        return TimelineDay(allDay = allDay, blocks = blocks)
    }

    /**
     * Lays one connected run into as few columns as it needs.
     *
     * Greedy by column: an item takes the first column whose last block has already finished. That
     * is the standard interval-partitioning result — the number of columns comes out equal to the
     * most things happening at any one instant, which is the fewest possible.
     */
    private fun packCluster(cluster: List<Triple<DayItem, Int, Int>>): List<TimedBlock> {
        val columnEnds = ArrayList<Int>()
        val placed = ArrayList<Pair<Triple<DayItem, Int, Int>, Int>>()

        for (entry in cluster) {
            val (_, start, end) = entry
            var col = columnEnds.indexOfFirst { it <= start }
            if (col < 0) {
                columnEnds += end
                col = columnEnds.size - 1
            } else {
                columnEnds[col] = end
            }
            placed += entry to col
        }

        val width = columnEnds.size
        return placed.map { (entry, col) ->
            TimedBlock(
                item = entry.first,
                startMinute = entry.second,
                endMinute = entry.third,
                column = col,
                columns = width,
            )
        }
    }

    /**
     * The minutes of [day] an item covers, or null if it belongs in the all-day bar.
     *
     * Clipped to the day, so the middle of a three-day conference fills its column from midnight to
     * midnight rather than starting below the screen. A thing with no length of its own is given
     * [MIN_BLOCK_MINUTES] — a task due at 14:00 with no block is still a mark on the day at 14:00,
     * and a zero-height one could not be seen or touched.
     */
    private fun spanOf(item: DayItem, day: java.time.LocalDate): Pair<Int, Int>? {
        val (start, end) = when (item) {
            is DayItem.Event -> {
                if (item.allDay) return null
                item.start to item.end
            }
            is DayItem.Task -> {
                if (!item.hasTime) return null
                item.at to (item.at.plusMinutes((item.durationMin ?: 0).toLong()))
            }
        }
        // Cross-day things go in the bar, the way every calendar does it: a block that began
        // yesterday has no honest top edge on today's ruler.
        if (start.toLocalDate() != day) return null
        // Ending at exactly midnight is *this* day's last minute, not a spill into the next.
        val endsMidnightTonight =
            end.toLocalDate() == day.plusDays(1) && end.toLocalTime() == LocalTime.MIDNIGHT
        if (end.toLocalDate() > day && !endsMidnightTonight) return null

        val from = start.toLocalTime().toSecondOfDay() / 60
        val rawTo = if (endsMidnightTonight) MINUTES_IN_DAY else end.toLocalTime().toSecondOfDay() / 60
        val to = maxOf(rawTo, from + MIN_BLOCK_MINUTES).coerceAtMost(MINUTES_IN_DAY)
        return from to to
    }

    /** The days of the week [day] falls in, Monday first — matching [monthGrid]. */
    fun weekOf(day: java.time.LocalDate): List<java.time.LocalDate> {
        val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
        return (0 until 7).map { monday.plusDays(it.toLong()) }
    }
}

/** Where a [LocalDateTime] sits in a day, in minutes past midnight. */
fun LocalDateTime.minuteOfDay(): Int = toLocalTime().toSecondOfDay() / 60
