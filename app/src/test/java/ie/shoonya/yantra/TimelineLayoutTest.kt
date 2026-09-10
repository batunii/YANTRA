package ie.shoonya.yantra

import ie.shoonya.yantra.ui.calendar.DayItem
import ie.shoonya.yantra.ui.calendar.TimelineLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * How a day packs itself when two things happen at once — CALENDAR_PLAN.md §10.
 *
 * Nothing here reserves time: overlapping is allowed and the job is to *show* it, side by side. The
 * subtle part is that widths are decided per connected run rather than per pair — a block can need
 * its own column because of something it does not itself touch — and getting that wrong produces a
 * layout that looks fine in the simple cases and jitters in the real ones.
 */
class TimelineLayoutTest {

    private val day = LocalDate.parse("2026-09-11")

    private fun event(id: String, from: String, to: String, allDay: Boolean = false) = DayItem.Event(
        nodeId = id,
        title = id,
        start = LocalDateTime.parse("2026-09-11T$from"),
        end = if (to.length > 5) LocalDateTime.parse(to) else LocalDateTime.parse("2026-09-11T$to"),
        allDay = allDay,
        location = null,
        repeating = false,
        cancelled = false,
        sortKey = 0,
    )

    private fun task(id: String, at: String?, minutes: Int? = null) = DayItem.Task(
        nodeId = id,
        title = id,
        at = LocalDateTime.parse("2026-09-11T${at ?: "00:00"}"),
        hasTime = at != null,
        done = false,
        durationMin = minutes,
        sortKey = 0,
    )

    private fun lay(vararg items: DayItem) = TimelineLayout.forDay(items.toList(), day)

    @Test
    fun `a lone block takes the whole width`() {
        val b = lay(event("a", "09:00", "10:00")).blocks.single()
        assertEquals(0, b.column)
        assertEquals(1, b.columns)
        assertEquals(9 * 60, b.startMinute)
        assertEquals(10 * 60, b.endMinute)
    }

    @Test
    fun `two at once sit side by side`() {
        val blocks = lay(event("a", "11:00", "12:30"), event("b", "11:30", "12:00")).blocks
        assertEquals(setOf(0, 1), blocks.map { it.column }.toSet())
        assertTrue(blocks.all { it.columns == 2 })
    }

    @Test
    fun `things that do not overlap reuse the same column`() {
        val blocks = lay(event("a", "09:00", "10:00"), event("b", "11:00", "12:00")).blocks
        assertTrue("both should be full width", blocks.all { it.columns == 1 && it.column == 0 })
    }

    @Test
    fun `touching end to start is not an overlap`() {
        // 09:00-10:00 and 10:00-11:00 are back to back, not a clash.
        val blocks = lay(event("a", "09:00", "10:00"), event("b", "10:00", "11:00")).blocks
        assertTrue(blocks.all { it.columns == 1 })
    }

    @Test
    fun `a run is widened by a block it does not itself touch`() {
        // a 09:00-10:00, b 09:30-11:30, c 11:00-12:00. a and c never meet, but b overlaps both, so
        // all three are one run and a pairwise decision would give a and c the full width and make
        // the layout jump as you scroll past b.
        val blocks = lay(
            event("a", "09:00", "10:00"),
            event("b", "09:30", "11:30"),
            event("c", "11:00", "12:00"),
        ).blocks
        assertEquals("one run, one width", setOf(2), blocks.map { it.columns }.toSet())
        // a and c never overlap, so the greedy packer is free to give them the same column.
        val byId = blocks.associateBy { it.item.nodeId }
        assertEquals(byId.getValue("a").column, byId.getValue("c").column)
        assertTrue(byId.getValue("b").column != byId.getValue("a").column)
    }

    @Test
    fun `three at once need three columns`() {
        val blocks = lay(
            event("a", "09:00", "10:00"),
            event("b", "09:15", "10:00"),
            event("c", "09:30", "10:00"),
        ).blocks
        assertEquals(setOf(3), blocks.map { it.columns }.toSet())
        assertEquals(setOf(0, 1, 2), blocks.map { it.column }.toSet())
    }

    @Test
    fun `separate runs are widened separately`() {
        // A clash in the morning must not make the afternoon half-width.
        val blocks = lay(
            event("a", "09:00", "10:00"),
            event("b", "09:30", "10:30"),
            event("lone", "15:00", "16:00"),
        ).blocks
        assertEquals(1, blocks.single { it.item.nodeId == "lone" }.columns)
    }

    @Test
    fun `the earliest thing takes the leftmost column`() {
        val blocks = lay(event("late", "09:30", "11:00"), event("early", "09:00", "11:00")).blocks
        assertEquals(0, blocks.single { it.item.nodeId == "early" }.column)
    }

    // ---- what belongs in the bar rather than on the ruler ----

    @Test
    fun `an all-day event goes in the bar`() {
        val d = lay(event("a", "00:00", "2026-09-12T00:00", allDay = true))
        assertEquals(listOf("a"), d.allDay.map { it.nodeId })
        assertTrue(d.blocks.isEmpty())
    }

    @Test
    fun `a task with no time goes in the bar`() {
        val d = lay(task("t", at = null))
        assertEquals(listOf("t"), d.allDay.map { it.nodeId })
    }

    @Test
    fun `a block that started yesterday goes in the bar`() {
        val yesterday = DayItem.Event(
            nodeId = "over", title = "over",
            start = LocalDateTime.parse("2026-09-10T23:00"),
            end = LocalDateTime.parse("2026-09-11T01:00"),
            allDay = false, location = null, repeating = false, cancelled = false, sortKey = 0,
        )
        assertEquals(listOf("over"), TimelineLayout.forDay(listOf(yesterday), day).allDay.map { it.nodeId })
    }

    @Test
    fun `a block running into tomorrow goes in the bar`() {
        assertEquals(
            listOf("over"),
            lay(event("over", "23:00", "2026-09-12T01:00")).allDay.map { it.nodeId },
        )
    }

    @Test
    fun `a block ending exactly at midnight is still tonight`() {
        // Ending at 00:00 is this day's last minute, not a spill into the next one.
        val b = lay(event("late", "23:00", "2026-09-12T00:00")).blocks.single()
        assertEquals(TimelineLayout.MINUTES_IN_DAY, b.endMinute)
    }

    // ---- blocking a task out ----

    @Test
    fun `a task blocked out for an hour is drawn to scale`() {
        val b = lay(task("t", "14:00", minutes = 60)).blocks.single()
        assertEquals(14 * 60, b.startMinute)
        assertEquals(15 * 60, b.endMinute)
    }

    @Test
    fun `a task and an event at the same time share the width`() {
        val blocks = lay(task("t", "14:00", minutes = 60), event("e", "14:00", "15:00")).blocks
        assertEquals(setOf(2), blocks.map { it.columns }.toSet())
        assertEquals(setOf(0, 1), blocks.map { it.column }.toSet())
    }

    @Test
    fun `a task with a time but no length still has a height you can hit`() {
        // Zero-height is invisible and untappable, so a moment gets a floor.
        val b = lay(task("t", "14:00")).blocks.single()
        assertEquals(TimelineLayout.MIN_BLOCK_MINUTES, b.endMinute - b.startMinute)
    }

    @Test
    fun `a very short block is floored rather than hidden`() {
        val b = lay(event("a", "09:00", "09:05")).blocks.single()
        assertEquals(TimelineLayout.MIN_BLOCK_MINUTES, b.endMinute - b.startMinute)
    }

    @Test
    fun `a block near midnight is floored without running off the end`() {
        val b = lay(event("a", "23:55", "2026-09-12T00:00")).blocks.single()
        assertEquals(TimelineLayout.MINUTES_IN_DAY, b.endMinute)
    }

    // ---- the week ----

    @Test
    fun `a week runs Monday to Sunday`() {
        val week = TimelineLayout.weekOf(LocalDate.parse("2026-09-11"))   // a Friday
        assertEquals(7, week.size)
        assertEquals(java.time.DayOfWeek.MONDAY, week.first().dayOfWeek)
        assertEquals(LocalDate.parse("2026-09-07"), week.first())
        assertEquals(LocalDate.parse("2026-09-13"), week.last())
    }

    @Test
    fun `three days start where you are, not on Monday`() {
        // A short span is a window you push along. Snapping it to Monday would put the day you
        // selected at the far side of the screen, or off it.
        val span = TimelineLayout.span(LocalDate.parse("2026-09-11"), 3)   // a Friday
        assertEquals(
            listOf("2026-09-11", "2026-09-12", "2026-09-13").map(LocalDate::parse),
            span,
        )
    }

    @Test
    fun `seven days still snap to the week they fall in`() {
        // A week has edges; a rolling seven days starting on a Wednesday is not one.
        assertEquals(
            TimelineLayout.weekOf(LocalDate.parse("2026-09-11")),
            TimelineLayout.span(LocalDate.parse("2026-09-11"), 7),
        )
        assertEquals(
            java.time.DayOfWeek.MONDAY,
            TimelineLayout.span(LocalDate.parse("2026-09-11"), 7).first().dayOfWeek,
        )
    }

    @Test
    fun `a Monday is the start of its own week`() {
        assertEquals(
            LocalDate.parse("2026-09-07"),
            TimelineLayout.weekOf(LocalDate.parse("2026-09-07")).first(),
        )
    }
}
