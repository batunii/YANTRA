package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.DueSpec
import ie.shoonya.yantra.data.format.DueValue
import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.data.format.TaskRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turning an event into a task — CALENDAR_PLAN.md §21.
 *
 * The conversion the app now offers instead of three competing ways to write about a calendar entry.
 * It works because a due date has carried a **duration** since §10 — the same field that lets a task
 * be drawn to scale on a timeline — so an hour-long event really is an hour-long task, and nothing
 * about its shape has to be invented or thrown away.
 *
 * These test the mapping as a pure function of the two line formats, which is where it can be
 * checked exactly: what is written, and what comes back.
 */
class EventToTaskTest {

    private val dublin: ZoneId = ZoneId.of("Europe/Dublin")

    /** The same transform `CalendarViewModel.turnIntoTask` applies, on a parsed line. */
    private fun convert(line: String): TaskRef {
        val e = PageCodec.decodeBlock(line) as EventRef
        return TaskRef(
            id = e.id,
            title = e.title.ifBlank { "Untitled" },
            due = DueSpec(
                value = if (e.time.allDay) DueValue.AllDay(e.time.start.toLocalDate())
                else DueValue.At(e.time.start.atZone(dublin).toInstant()),
                reminders = listOfNotNull(e.reminderMin),
                duration = if (e.time.allDay) null else e.time.duration.takeIf { !it.isZero },
            ),
            labels = e.labels,
            priority = e.priority,
            indent = e.indent,
            raw = null,
        )
    }

    @Test
    fun `an hour-long event becomes an hour-long task`() {
        val t = convert("@ 2026-09-16T14:00/PT1H Design review ^e1")
        assertEquals("Design review", t.title)
        assertEquals(Duration.ofHours(1), t.due!!.duration)
        assertEquals(
            LocalDate.parse("2026-09-16").atTime(14, 0).atZone(dublin).toInstant(),
            (t.due!!.value as DueValue.At).instant,
        )
    }

    @Test
    fun `the id is kept, so anything already written on it is still there`() {
        // editBlock maps a block to a block: the node is the same node, so a page the event had
        // becomes the task's page rather than being orphaned.
        assertEquals("e1", convert("@ 2026-09-16T14:00/PT1H Design review ^e1").id)
    }

    @Test
    fun `an all-day event becomes an all-day task with no span`() {
        // Twenty-four hours would draw a block down the whole ruler for something with no hours of
        // its own.
        val t = convert("@ 2026-09-16 Deirdre's birthday ^e2")
        assertEquals(LocalDate.parse("2026-09-16"), (t.due!!.value as DueValue.AllDay).date)
        assertNull(t.due!!.duration)
    }

    @Test
    fun `a reminder comes along`() {
        assertEquals(listOf(15), convert("@ 2026-09-16T14:00/PT1H Design review ^e1 remind:15").due!!.reminders)
    }

    @Test
    fun `labels and priority come along`() {
        val t = convert("@ 2026-09-16T14:00/PT1H Design review ^e1 !High #work")
        assertEquals("High", t.priority)
        assertEquals(listOf("work"), t.labels)
    }

    @Test
    fun `a moment becomes a task at a time rather than a zero-length block`() {
        val t = convert("@ 2026-09-16T14:00 Doorbell ^e3")
        assertNull("nothing to draw to scale", t.due!!.duration)
    }

    @Test
    fun `an untitled event does not become a nameless task`() {
        assertEquals("Untitled", convert("@ 2026-09-16T14:00/PT1H ^e4").title)
    }

    @Test
    fun `the task it becomes is an ordinary task line`() {
        val line = PageCodec.encodeBlock(convert("@ 2026-09-16T14:00/PT1H Design review ^e1"))
        val back = PageCodec.decodeBlock(line) as TaskRef
        assertEquals("Design review", back.title)
        assertEquals(Duration.ofHours(1), back.due!!.duration)
    }
}
