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
    fun `one line is both a note and a sitting`() {
        val line = "@ 2026-09-16T14:00/PT1H Design review ^n1 for:t1 ext:abc@google.com"
        val e = PageCodec.decodeBlock(line) as EventRef
        assertEquals("t1", e.forTaskId)
        assertEquals("abc@google.com", e.external!!.uid)
        assertEquals("and it round-trips", line, PageCodec.encodeBlock(e.copy(raw = null)))
    }

    // ---- the day ----

    private fun bucket(ours: List<EventEntity>, device: List<DeviceEvent>, titles: Map<String, String>) =
        CalendarBucketer.bucket(
            events = ours,
            tasks = emptyList(),
            titles = titles,
            device = device,
            from = LocalDate.parse("2026-09-01"),
            toExclusive = LocalDate.parse("2026-10-01"),
            zone = dublin,
        )

    private fun lineFor(taskId: String?, title: String = "Design review") = EventEntity(
        nodeId = "n1",
        startLocal = "2026-09-16T14:00", endLocal = "2026-09-16T15:00", allDay = false,
        startUtc = at("2026-09-16T14:00"), endUtc = at("2026-09-16T15:00"),
        forNodeId = taskId,
        extUid = "abc@google.com",
    )

    private val day = LocalDate.parse("2026-09-16")

    @Test
    fun `a meeting with a task on it is still one block, and reaches the task`() {
        val items = bucket(
            ours = listOf(lineFor("t1")),
            device = listOf(theirs()),
            titles = mapOf("n1" to "Design review"),
        ).getValue(day)
        assertEquals("one meeting is one block", 1, items.size)
        val block = items.single() as DayItem.Device
        assertEquals("t1", block.taskId)
        assertEquals("n1", block.noteId)
    }

    @Test
    fun `the meeting keeps its own name`() {
        // A renamed task says so on the second line; the block is still called what the meeting is
        // called, because that is what makes a Wednesday afternoon recognisable.
        val block = bucket(
            ours = listOf(lineFor("t1")),
            device = listOf(theirs()),
            titles = mapOf("n1" to "Prepare the Q3 numbers"),
        ).getValue(day).single() as DayItem.Device
        assertEquals("Design review", block.title)
        assertEquals("Prepare the Q3 numbers", block.taskTitle)
    }

    @Test
    fun `a task named the same as its meeting says nothing twice`() {
        val block = bucket(
            ours = listOf(lineFor("t1")),
            device = listOf(theirs()),
            titles = mapOf("n1" to "Design review"),
        ).getValue(day).single() as DayItem.Device
        assertNull("no second line when it would repeat the first", block.taskTitle)
    }

    @Test
    fun `a meeting with only notes has no task`() {
        val block = bucket(
            ours = listOf(lineFor(taskId = null)),
            device = listOf(theirs()),
            titles = mapOf("n1" to "Design review"),
        ).getValue(day).single() as DayItem.Device
        assertNull(block.taskId)
        assertEquals("n1", block.noteId)
    }

    // ---- the cache, and what reads it ----

    @Test
    fun `a moved meeting is a difference the refresh can see`() {
        // The comparison reconcile makes. It has to be against the *same* reading of a device event
        // the drawing uses, or a line would be rewritten on every read of the calendar for ever.
        val cached = lineFor("t1")
        val moved = theirs(begin = "2026-09-16T16:00", end = "2026-09-16T17:00")
        val (start, end) = deviceLocalSpan(moved, dublin)
        assertEquals("2026-09-16T16:00", start.toString())
        assertEquals("2026-09-16T17:00", end.toString())
        assertEquals(false, cached.startLocal == start.toString())
    }

    @Test
    fun `an unmoved meeting is not a difference, so nothing is rewritten`() {
        val cached = lineFor("t1")
        val (start, end) = deviceLocalSpan(theirs(), dublin)
        assertEquals(cached.startLocal, start.toString())
        assertEquals(cached.endLocal, end.toString())
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
