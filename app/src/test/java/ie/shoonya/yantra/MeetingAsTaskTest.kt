package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.EventEntity
import ie.shoonya.yantra.data.device.DeviceEvent
import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.ui.calendar.CalendarBucketer
import ie.shoonya.yantra.ui.calendar.DayItem
import ie.shoonya.yantra.ui.calendar.deviceLocalSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Their meeting as the time for your task — CALENDAR_PLAN.md §20.
 *
 * One line carries both tokens: `for:` says whose time it is, `ext:` says whose meeting it is. What
 * the tests guard is that the two compose without either losing its meaning, and that the day still
 * draws **one block** — the failure a copy would have made, with the work and the meeting sitting on
 * top of each other at the same hour.
 */
class MeetingAsTaskTest {

    private val dublin: ZoneId = ZoneId.of("Europe/Dublin")

    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(dublin).toInstant().toEpochMilli()

    private fun theirs(
        begin: String = "2026-09-16T14:00",
        end: String = "2026-09-16T15:00",
        title: String = "Design review",
        allDay: Boolean = false,
    ) = DeviceEvent(
        instanceId = 1, eventId = 10, title = title,
        beginUtc = at(begin), endUtc = at(end),
        allDay = allDay, location = null, color = null, uid = "abc@google.com",
    )

    // ---- the line ----

    @Test
    fun `one line is a task about a meeting`() {
        // One node. It used to take two — a task, plus an event line to link it — which put one
        // meeting in the Inbox twice.
        val line = "- [ ] Design review ^t1 due:2026-09-16T14:00/PT1H ext:abc@google.com"
        val t = PageCodec.decodeBlock(line) as ie.shoonya.yantra.data.format.TaskRef
        assertEquals("abc@google.com", t.external!!.uid)
        assertEquals("and it round-trips", line, PageCodec.encodeBlock(t.copy(raw = null)))
    }

    // ---- the day ----

    private fun bucket(
        tasks: List<ie.shoonya.yantra.data.db.DueRow> = emptyList(),
        device: List<DeviceEvent> = emptyList(),
    ) =
        CalendarBucketer.bucket(
            events = emptyList(),
            tasks = tasks,
            titles = emptyMap(),
            device = device,
            from = LocalDate.parse("2026-09-01"),
            toExclusive = LocalDate.parse("2026-10-01"),
            zone = dublin,
        )

    /** The one node: a task that is *about* their meeting — CALENDAR_PLAN.md §22. */
    private fun taskAbout(title: String = "Design review", occurrence: String? = null) =
        ie.shoonya.yantra.data.db.DueRow(
            nodeId = "t1",
            title = title,
            done = false,
            dueMillis = at("2026-09-16T14:00"),
            hasTime = true,
            durationMin = 60,
            extUid = "abc@google.com",
            extStart = occurrence,
        )

    private val day = LocalDate.parse("2026-09-16")

    @Test
    fun `a meeting and the task about it are one block`() {
        // The whole point of §22: one node, and one thing drawn. Two would be the Inbox holding a
        // meeting twice, which is what this replaced.
        val items = bucket(tasks = listOf(taskAbout()), device = listOf(theirs())).getValue(day)
        assertEquals("one meeting is one block", 1, items.size)
        assertEquals("t1", (items.single() as DayItem.Device).taskId)
    }

    @Test
    fun `the meeting keeps its own name, and a renamed task says so beside it`() {
        val block = bucket(tasks = listOf(taskAbout("Prepare the Q3 numbers")), device = listOf(theirs()))
            .getValue(day).single() as DayItem.Device
        assertEquals("Design review", block.title)
        assertEquals("Prepare the Q3 numbers", block.taskTitle)
    }

    @Test
    fun `a task named the same as its meeting says nothing twice`() {
        val block = bucket(tasks = listOf(taskAbout()), device = listOf(theirs()))
            .getValue(day).single() as DayItem.Device
        assertNull("no second line when it would repeat the first", block.taskTitle)
    }

    @Test
    fun `a task whose meeting cannot be read still draws, from its own due date`() {
        // Permission off, or the meeting deleted. It is a task; it does not vanish.
        val items = bucket(tasks = listOf(taskAbout())).getValue(day)
        assertEquals(1, items.size)
        assertEquals("t1", items.single().nodeId)
    }

    @Test
    fun `a meeting nobody has made work of has no task`() {
        assertNull((bucket(device = listOf(theirs())).getValue(day).single() as DayItem.Device).taskId)
    }

    // ---- the cache, and what reads it ----

    @Test
    fun `a moved meeting is a difference the refresh can see`() {
        // The comparison reconcile makes, against the *same* reading of a device event the drawing
        // uses — the two disagreeing would rewrite a line on every read of the calendar for ever.
        val moved = theirs(begin = "2026-09-16T16:00", end = "2026-09-16T17:00")
        val (start, _) = deviceLocalSpan(moved, dublin)
        assertEquals(false, taskAbout().dueMillis == start.atZone(dublin).toInstant().toEpochMilli())
    }

    @Test
    fun `an unmoved meeting is not a difference, so nothing is rewritten`() {
        val (start, end) = deviceLocalSpan(theirs(), dublin)
        assertEquals(taskAbout().dueMillis, start.atZone(dublin).toInstant().toEpochMilli())
        assertEquals(
            taskAbout().durationMin,
            java.time.Duration.between(start, end).toMinutes().toInt(),
        )
    }

    @Test
    fun `an all-day meeting reads the same way for drawing and for the cache`() {
        // The two disagreeing is the shape of a rewrite loop: every read finds a difference, writes
        // it, and finds the same difference next time.
        val allDay = theirs(
            begin = "2026-09-16T01:00",   // UTC midnight seen from Dublin in September
            end = "2026-09-17T01:00",
            allDay = true,
        )
        val (start, _) = deviceLocalSpan(allDay, dublin)
        assertEquals("2026-09-16T00:00", start.toString())
    }
}
