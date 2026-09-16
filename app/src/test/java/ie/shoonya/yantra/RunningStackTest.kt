package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.SittingSpan
import ie.shoonya.yantra.domain.RunningTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the bar holds, and in what order — CALENDAR_PLAN.md §13.
 *
 * Two sources feed it and neither is a subset of the other: tasks you have picked up, and tasks
 * whose sitting is happening right now. The rules that matter are that **a task appears once**
 * however many ways it qualifies, and that **the front card is the one worth looking at** — the
 * running clock first, then whatever the calendar says is this hour's work.
 */
class RunningStackTest {

    private val two = 2_000L

    private fun sitting(task: String, from: Long, to: Long, title: String = task) =
        SittingSpan(taskId = task, title = title, startUtc = from, endUtc = to)

    private fun stack(
        started: List<Pair<String, String>> = emptyList(),
        timing: Pair<String, Int>? = null,
        sittings: List<SittingSpan> = emptyList(),
        at: Long = two,
    ) = RunningTask.stack(started, timing, sittings, at)

    @Test
    fun `a sitting that has arrived puts its task on the bar`() {
        val bar = stack(sittings = listOf(sitting("t1", 1_000, 3_000, title = "Write the deck")))
        assertEquals(listOf("t1"), bar.map { it.nodeId })
        assertEquals("Write the deck", bar.single().title)
        assertTrue(bar.single().scheduled)
        // Ready, not running. Nothing has been written to the file and no clock has been started.
        assertFalse(bar.single().hasSession)
    }

    @Test
    fun `a sitting later today is not on the bar yet`() {
        assertTrue(stack(sittings = listOf(sitting("t1", 9_000, 10_000))).isEmpty())
    }

    @Test
    fun `a sitting that has finished leaves the bar`() {
        // The end is exclusive: at 15:00 a sitting that ran to 15:00 is over.
        assertTrue(stack(sittings = listOf(sitting("t1", 1_000, 2_000))).isEmpty())
    }

    @Test
    fun `a task that is both started and scheduled appears once`() {
        val bar = stack(
            started = listOf("t1" to "Write the deck"),
            sittings = listOf(sitting("t1", 1_000, 3_000)),
        )
        assertEquals(listOf("t1"), bar.map { it.nodeId })
        assertTrue(bar.single().scheduled)
    }

    @Test
    fun `two sittings for the same task in one hour are one card`() {
        val bar = stack(
            sittings = listOf(sitting("t1", 1_000, 3_000), sitting("t1", 1_500, 2_500)),
        )
        assertEquals(listOf("t1"), bar.map { it.nodeId })
    }

    @Test
    fun `the scheduled task comes before the rest`() {
        // Newest-first is a reasonable default with nothing better to go on. A sitting is something
        // better to go on: it is you, earlier, saying this is the hour for this.
        val bar = stack(
            started = listOf("newest" to "newest", "older" to "older"),
            sittings = listOf(sitting("older", 1_000, 3_000)),
        )
        assertEquals(listOf("older", "newest"), bar.map { it.nodeId })
    }

    @Test
    fun `the running clock still leads, sitting or no sitting`() {
        val bar = stack(
            started = listOf("timed" to "timed", "other" to "other"),
            timing = "timed" to 42,
            sittings = listOf(sitting("other", 1_000, 3_000)),
        )
        assertEquals(listOf("timed", "other"), bar.map { it.nodeId })
        assertEquals(42, bar.first().elapsedSecs)
    }

    @Test
    fun `a scheduled task that nobody started sorts ahead of a started one`() {
        val bar = stack(
            started = listOf("picked-up" to "picked-up"),
            sittings = listOf(sitting("planned", 1_000, 3_000)),
        )
        assertEquals(listOf("planned", "picked-up"), bar.map { it.nodeId })
    }

    @Test
    fun `started tasks keep the order the query gave them`() {
        val bar = stack(started = listOf("a" to "a", "b" to "b", "c" to "c"))
        assertEquals(listOf("a", "b", "c"), bar.map { it.nodeId })
        assertTrue(bar.none { it.scheduled })
    }
}
