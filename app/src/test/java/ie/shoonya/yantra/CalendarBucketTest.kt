package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.DueRow
import ie.shoonya.yantra.data.db.EventEntity
import ie.shoonya.yantra.ui.calendar.CalendarBucketer
import ie.shoonya.yantra.ui.calendar.DayItem
import ie.shoonya.yantra.ui.calendar.monthGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Which day a thing lands on — CALENDAR_PLAN.md §6.
 *
 * Every off-by-one in a calendar lives here: an all-day event whose exclusive end must not add a
 * phantom day, a meeting ending exactly at midnight, a task due at 00:00 belonging to that day
 * rather than the one before, and a multi-day event appearing on each of its days. All of it is
 * pure, so all of it is checkable without a screen.
 */
class CalendarBucketTest {

    private val dublin = ZoneId.of("Europe/Dublin")

    private fun event(
        id: String,
        start: String,
        end: String,
        allDay: Boolean = false,
        rrule: String? = null,
        cancelled: Boolean = false,
    ) = EventEntity(
        nodeId = id,
        startLocal = start,
        endLocal = end,
        allDay = allDay,
        startUtc = 0,
        endUtc = 0,
        rrule = rrule,
        cancelled = cancelled,
    )

    private fun bucket(
        events: List<EventEntity> = emptyList(),
        tasks: List<DueRow> = emptyList(),
        titles: Map<String, String> = emptyMap(),
        from: String = "2026-09-01",
        to: String = "2026-10-01",
    ) = CalendarBucketer.bucket(
        events = events.map { indexed(it, titles[it.nodeId]) },
        tasks = tasks,
        titles = titles,
        from = LocalDate.parse(from),
        toExclusive = LocalDate.parse(to),
        zone = dublin,
    )

    private fun dueAt(id: String, local: String, hasTime: Boolean, done: Boolean = false) = DueRow(
        nodeId = id,
        title = id,
        done = done,
        dueMillis = LocalDateTime.parse(local).atZone(dublin).toInstant().toEpochMilli(),
        hasTime = hasTime,
    )

    @Test
    fun `a one-day all-day event lands on exactly one day`() {
        // The end is exclusive — 00:00 on the 12th — and must not put anything on the 12th.
        val days = bucket(listOf(event("e", "2026-09-11T00:00", "2026-09-12T00:00", allDay = true)))
        assertEquals(setOf(LocalDate.parse("2026-09-11")), days.keys)
    }

    @Test
    fun `a three-day all-day event lands on three days`() {
        val days = bucket(listOf(event("e", "2026-09-11T00:00", "2026-09-14T00:00", allDay = true)))
        assertEquals(
            listOf("2026-09-11", "2026-09-12", "2026-09-13").map(LocalDate::parse).toSet(),
            days.keys,
        )
    }

    @Test
    fun `a meeting ending at midnight belongs to the day it started`() {
        val days = bucket(listOf(event("e", "2026-09-11T23:00", "2026-09-12T00:00")))
        assertEquals(setOf(LocalDate.parse("2026-09-11")), days.keys)
    }

    @Test
    fun `a meeting running past midnight lands on both days`() {
        val days = bucket(listOf(event("e", "2026-09-11T23:00", "2026-09-12T00:30")))
        assertEquals(
            setOf(LocalDate.parse("2026-09-11"), LocalDate.parse("2026-09-12")),
            days.keys,
        )
    }

    @Test
    fun `a moment lands on its own day`() {
        val days = bucket(listOf(event("e", "2026-09-11T14:00", "2026-09-11T14:00")))
        assertEquals(setOf(LocalDate.parse("2026-09-11")), days.keys)
    }

    @Test
    fun `a cancelled occurrence is an absence`() {
        val days = bucket(listOf(event("e", "2026-09-11T09:00", "2026-09-11T09:15", cancelled = true)))
        assertTrue(days.isEmpty())
    }

    @Test
    fun `a recurring event outside the window is not drawn in the wrong month`() {
        // inRange returns every rule whatever its span, because the row holds only the first
        // occurrence. Until expansion lands, anything outside the window is dropped rather than
        // painted onto a month it does not belong to.
        val standup = event("s1", "2026-07-06T09:00", "2026-07-06T09:15", rrule = "FREQ=WEEKLY")
        assertTrue(bucket(listOf(standup)).isEmpty())
    }

    @Test
    fun `a recurring event inside the window is drawn and marked`() {
        val standup = event("s1", "2026-09-07T09:00", "2026-09-07T09:15", rrule = "FREQ=WEEKLY")
        val item = bucket(listOf(standup), titles = mapOf("s1" to "Standup"))
            .getValue(LocalDate.parse("2026-09-07")).single()
        assertTrue((item as DayItem.Event).repeating)
        assertEquals("Standup", item.title)
    }

    @Test
    fun `a task due at midnight belongs to that day`() {
        val days = bucket(tasks = listOf(dueAt("t", "2026-09-11T00:00", hasTime = false)))
        assertEquals(setOf(LocalDate.parse("2026-09-11")), days.keys)
    }

    @Test
    fun `a finished task still appears`() {
        // A calendar that hides what you completed on Tuesday cannot be looked back at.
        val days = bucket(tasks = listOf(dueAt("t", "2026-09-11T10:00", hasTime = true, done = true)))
        assertTrue((days.getValue(LocalDate.parse("2026-09-11")).single() as DayItem.Task).done)
    }

    @Test
    fun `a day reads top to bottom as it happens`() {
        val days = bucket(
            events = listOf(
                event("allday", "2026-09-11T00:00", "2026-09-12T00:00", allDay = true),
                event("three", "2026-09-11T15:00", "2026-09-11T16:00"),
                event("nine", "2026-09-11T09:00", "2026-09-11T10:00"),
            ),
            tasks = listOf(
                dueAt("undated", "2026-09-11T00:00", hasTime = false),
                dueAt("noon", "2026-09-11T12:00", hasTime = true),
            ),
            titles = mapOf("allday" to "allday", "three" to "three", "nine" to "nine"),
        )
        assertEquals(
            // All-day first, then by clock, then the task with no time of its own last.
            listOf("allday", "nine", "noon", "three", "undated"),
            days.getValue(LocalDate.parse("2026-09-11")).map { it.nodeId },
        )
    }

    @Test
    fun `an event with no title still gets something tappable`() {
        val item = bucket(listOf(event("e", "2026-09-11T14:00", "2026-09-11T15:00")))
            .getValue(LocalDate.parse("2026-09-11")).single()
        assertEquals("Event", item.title)
    }

    @Test
    fun `a malformed row is skipped rather than crashing the month`() {
        val bad = event("bad", "not-a-time", "also-not")
        val good = event("good", "2026-09-11T14:00", "2026-09-11T15:00")
        assertEquals(setOf(LocalDate.parse("2026-09-11")), bucket(listOf(bad, good)).keys)
    }

    // ---- sittings ----

    @Test
    fun `a sitting is drawn as the task it is for`() {
        val sitting = event("s1", "2026-09-11T14:00", "2026-09-11T16:00")
            .copy(forNodeId = "t1")
        val item = CalendarBucketer.bucket(
            events = listOf(indexed(sitting, "Write the deck")),
            tasks = emptyList(),
            // The view model resolves this through the DAO's LEFT JOIN on for_node_id; here it is
            // handed in the same way, which is the contract that matters.
            titles = mapOf("s1" to "Write the deck"),
            sittingOf = mapOf("s1" to "t1"),
            from = LocalDate.parse("2026-09-01"),
            toExclusive = LocalDate.parse("2026-10-01"),
            zone = dublin,
        ).getValue(LocalDate.parse("2026-09-11")).single() as DayItem.Event
        assertEquals("Write the deck", item.title)
        assertEquals("t1", item.forTaskId)
    }

    // ---- colour ----

    // The workspace is the spine and the block's own colour is the fill — two facts, two places,
    // never one rule doing both. These four say exactly that, because "the workspace is the
    // fallback" is the thing that was true here until it was not.

    @Test
    fun `the workspace goes to the spine and leaves the block uncoloured`() {
        val item = CalendarBucketer.bucket(
            events = listOf(indexed(event("e1", "2026-09-11T14:00", "2026-09-11T15:00").copy(workspaceId = "w1"), "Design review")),
            tasks = emptyList(),
            titles = mapOf("e1" to "Design review"),
            workspaceTints = mapOf("w1" to 999L),
            from = LocalDate.parse("2026-09-01"),
            toExclusive = LocalDate.parse("2026-10-01"),
            zone = dublin,
        ).getValue(LocalDate.parse("2026-09-11")).single() as DayItem.Event
        assertEquals(999L, item.workspaceTint)
        assertNull(item.tint)
    }

    @Test
    fun `a block's own colour is its own, and says nothing about the repository`() {
        val teal = ie.shoonya.yantra.data.label.LabelPalette.swatches.first { it.name == "Teal" }.light
        val item = CalendarBucketer.bucket(
            events = listOf(
                indexed(event("e1", "2026-09-11T14:00", "2026-09-11T15:00").copy(workspaceId = "w1", color = "Teal"), "Design review"),
            ),
            tasks = emptyList(),
            titles = mapOf("e1" to "Design review"),
            workspaceTints = mapOf("w1" to 999L),
            from = LocalDate.parse("2026-09-01"),
            toExclusive = LocalDate.parse("2026-10-01"),
            zone = dublin,
        ).getValue(LocalDate.parse("2026-09-11")).single() as DayItem.Event
        assertEquals(teal, item.tint)
        assertEquals(999L, item.workspaceTint)
    }

    @Test
    fun `an uncoloured sitting wears the colour of its task's list`() {
        val sitting = event("s1", "2026-09-11T14:00", "2026-09-11T16:00").copy(forNodeId = "t1")
        val item = CalendarBucketer.bucket(
            events = listOf(indexed(sitting, "Write the deck")),
            tasks = emptyList(),
            titles = mapOf("s1" to "Write the deck"),
            sittingOf = mapOf("s1" to "t1"),
            listTints = mapOf("t1" to 777L),
            from = LocalDate.parse("2026-09-01"),
            toExclusive = LocalDate.parse("2026-10-01"),
            zone = dublin,
        ).getValue(LocalDate.parse("2026-09-11")).single() as DayItem.Event
        assertEquals(777L, item.tint)
    }

    @Test
    fun `an appointment has no list to borrow from and stays in the accent`() {
        val item = CalendarBucketer.bucket(
            events = listOf(indexed(event("e1", "2026-09-11T14:00", "2026-09-11T15:00"), "Design review")),
            tasks = emptyList(),
            titles = mapOf("e1" to "Design review"),
            // A list colour is on the table; nothing on screen is a sitting, so nothing takes it.
            listTints = mapOf("t1" to 777L),
            from = LocalDate.parse("2026-09-01"),
            toExclusive = LocalDate.parse("2026-10-01"),
            zone = dublin,
        ).getValue(LocalDate.parse("2026-09-11")).single() as DayItem.Event
        assertNull(item.tint)
        assertNull(item.workspaceTint)
    }

    @Test
    fun `an ordinary event is not a sitting`() {
        val item = bucket(
            listOf(event("e1", "2026-09-11T14:00", "2026-09-11T15:00")),
            titles = mapOf("e1" to "Design review"),
        ).getValue(LocalDate.parse("2026-09-11")).single() as DayItem.Event
        assertNull(item.forTaskId)
    }

    // ---- the grid ----

    @Test
    fun `a month grid is six weeks starting on a Monday`() {
        val grid = monthGrid(LocalDate.parse("2026-09-15"))
        assertEquals(42, grid.size)
        assertEquals(java.time.DayOfWeek.MONDAY, grid.first().dayOfWeek)
        assertTrue(grid.contains(LocalDate.parse("2026-09-01")))
        assertTrue(grid.contains(LocalDate.parse("2026-09-30")))
    }

    @Test
    fun `a month starting on a Monday does not gain a blank week`() {
        // June 2026 starts on a Monday; backing up to "the Monday on or before" must be a no-op.
        val grid = monthGrid(LocalDate.parse("2026-06-10"))
        assertEquals(LocalDate.parse("2026-06-01"), grid.first())
    }
}
