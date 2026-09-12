package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.RailTask
import ie.shoonya.yantra.ui.calendar.RailBucket
import ie.shoonya.yantra.ui.calendar.bucketOf
import ie.shoonya.yantra.ui.calendar.railShelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which shelf a task lands on — CALENDAR_PLAN.md §13.
 *
 * The rule that matters is that **a task lands on exactly one**. A rail where the same thing appears
 * under two tabs is a rail you cannot count, and the overlaps are not hypothetical: most real tasks
 * carry both a due date and a deadline.
 */
class RailBucketTest {

    private val dublin: ZoneId = ZoneId.of("Europe/Dublin")
    private val today: LocalDate = LocalDate.parse("2026-09-12")

    private fun task(
        id: String,
        due: String? = null,
        deadline: String? = null,
        sittings: Int = 0,
    ) = RailTask(
        nodeId = id,
        title = id,
        dueMillis = due?.let { LocalDate.parse(it).atStartOfDay(dublin).toInstant().toEpochMilli() },
        deadlineMillis = deadline?.let { LocalDate.parse(it).atStartOfDay(dublin).toInstant().toEpochMilli() },
        sittings = sittings,
    )

    private fun bucket(t: RailTask) = bucketOf(t, today, dublin)

    @Test
    fun `due today is today`() {
        assertEquals(RailBucket.TODAY, bucket(task("a", due = "2026-09-12")))
    }

    @Test
    fun `a deadline in the next few days is soon`() {
        assertEquals(RailBucket.SOON, bucket(task("a", deadline = "2026-09-14")))
    }

    @Test
    fun `a deadline today is also soon`() {
        assertEquals(RailBucket.SOON, bucket(task("a", deadline = "2026-09-12")))
    }

    @Test
    fun `a deadline just past the window is not soon`() {
        assertEquals(RailBucket.OTHER, bucket(task("a", deadline = "2026-09-16")))
    }

    @Test
    fun `no dates at all is undated`() {
        assertEquals(RailBucket.UNDATED, bucket(task("a")))
    }

    @Test
    fun `dated but neither today nor soon falls to other`() {
        assertEquals(RailBucket.OTHER, bucket(task("a", due = "2026-10-01")))
    }

    @Test
    fun `overdue is other, not today`() {
        // "Due last Tuesday" is not a claim about today, and quietly relabelling it as today's work
        // would be the planner deciding something on your behalf.
        assertEquals(RailBucket.OTHER, bucket(task("a", due = "2026-09-08")))
    }

    @Test
    fun `today wins over a deadline that is also soon`() {
        // The overlap that actually occurs: due today, deadline Friday. It must appear once, where
        // you would look for it.
        assertEquals(RailBucket.TODAY, bucket(task("a", due = "2026-09-12", deadline = "2026-09-14")))
    }

    @Test
    fun `every task lands on exactly one shelf`() {
        val tasks = listOf(
            task("today", due = "2026-09-12"),
            task("soon", deadline = "2026-09-13"),
            task("both", due = "2026-09-12", deadline = "2026-09-13"),
            task("undated"),
            task("later", due = "2026-11-01"),
            task("overdue", due = "2026-09-01"),
        )
        val shelves = railShelves(tasks, today, dublin)
        val placed = shelves.values.flatten().map { it.nodeId }
        assertEquals("nothing lost, nothing duplicated", tasks.size, placed.size)
        assertEquals(tasks.map { it.nodeId }.toSet(), placed.toSet())
    }

    @Test
    fun `the shelves are always all four, even when empty`() {
        // Tabs that come and go with the data are tabs you cannot learn the position of.
        val shelves = railShelves(emptyList(), today, dublin)
        assertEquals(RailBucket.entries.toSet(), shelves.keys)
        assertTrue(shelves.values.all { it.isEmpty() })
    }

    @Test
    fun `a shelf is ordered by date then name, not by what was last touched`() {
        val shelves = railShelves(
            listOf(
                task("zebra", due = "2026-11-01"),
                task("apple", due = "2026-10-01"),
                task("mango", due = "2026-10-01"),
            ),
            today, dublin,
        )
        assertEquals(
            listOf("apple", "mango", "zebra"),
            shelves.getValue(RailBucket.OTHER).map { it.nodeId },
        )
    }

    @Test
    fun `a task carries how many sittings it already has`() {
        val shelves = railShelves(listOf(task("a", sittings = 2)), today, dublin)
        assertEquals(2, shelves.getValue(RailBucket.UNDATED).single().sittings)
    }
}
