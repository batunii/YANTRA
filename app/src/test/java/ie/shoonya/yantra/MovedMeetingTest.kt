package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.EventEntity
import ie.shoonya.yantra.data.device.DeviceEvent
import ie.shoonya.yantra.ui.calendar.CalendarBucketer
import ie.shoonya.yantra.ui.calendar.DayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A meeting that moved is still the same meeting — CALENDAR_PLAN.md §25.
 *
 * The deadlock this replaced: the match was keyed on identity **plus the occurrence start**, the
 * occurrence start is the one thing that moves, and a line whose cached start had gone stale could
 * therefore never match its meeting again — so it could never be corrected either, and the day drew
 * the same meeting twice for ever. Which is exactly what a recording showed: two "Pluto x Napkin",
 * one at ten and one at nine.
 */
class MovedMeetingTest {

    private val dublin: ZoneId = ZoneId.of("Europe/Dublin")
    private val day = LocalDate.parse("2026-09-16")

    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(dublin).toInstant().toEpochMilli()

    private fun theirs(
        instance: Long = 1,
        begin: String,
        end: String,
        title: String = "Pluto x Napkin",
        uid: String? = "abc@google.com",
    ) = DeviceEvent(
        instanceId = instance, eventId = 10, title = title,
        beginUtc = at(begin), endUtc = at(end),
        allDay = false, location = null, color = null, uid = uid,
    )

    private fun ours(
        nodeId: String = "n1",
        start: String,
        end: String,
        occurrence: String? = null,
        uid: String? = "abc@google.com",
    ) = indexed(
        event = EventEntity(
            nodeId = nodeId,
            startLocal = start, endLocal = end, allDay = false,
            startUtc = at(start), endUtc = at(end),
        ),
        title = "Pluto x Napkin",
        extUid = uid,
        extStart = occurrence,
    )

    private fun bucket(
        ours: List<ie.shoonya.yantra.data.db.EventWithTitle> = emptyList(),
        device: List<DeviceEvent> = emptyList(),
    ) = CalendarBucketer.bucket(
        events = ours,
        tasks = emptyList(),
        titles = ours.associate { it.event.nodeId to "Pluto x Napkin" },
        device = device,
        from = LocalDate.parse("2026-09-01"),
        toExclusive = LocalDate.parse("2026-10-01"),
        zone = dublin,
    )

    @Test
    fun `a meeting that moved an hour is still one block`() {
        // Ours remembers ten o'clock; theirs now says nine. One meeting, one block, at nine.
        val items = bucket(
            ours = listOf(ours(start = "2026-09-16T10:00", end = "2026-09-16T11:00", occurrence = "2026-09-16T10:00")),
            device = listOf(theirs(begin = "2026-09-16T09:00", end = "2026-09-16T10:00")),
        ).getValue(day)
        assertEquals("two blocks is the bug this replaced", 1, items.size)
        val block = items.single() as DayItem.Device
        assertEquals("n1", block.noteId)
        assertEquals(9, block.start.hour)
    }

    @Test
    fun `a meeting that moved to another day is still one block`() {
        val days = bucket(
            ours = listOf(ours(start = "2026-09-16T10:00", end = "2026-09-16T11:00", occurrence = "2026-09-16T10:00")),
            device = listOf(theirs(begin = "2026-09-18T10:00", end = "2026-09-18T11:00")),
        )
        assertNull("nothing left behind on the old day", days[day])
        assertEquals("n1", (days.getValue(LocalDate.parse("2026-09-18")).single() as DayItem.Device).noteId)
    }

    @Test
    fun `a page written for one morning stays with that morning`() {
        // Two lines about the same weekly standup. The one written for the sixteenth must not
        // follow the twenty-third, and nearest-occurrence is what keeps them apart.
        val days = bucket(
            ours = listOf(
                ours("n16", "2026-09-16T09:00", "2026-09-16T09:30", occurrence = "2026-09-16T09:00"),
                ours("n23", "2026-09-23T09:00", "2026-09-23T09:30", occurrence = "2026-09-23T09:00"),
            ),
            device = listOf(
                theirs(1, "2026-09-16T09:00", "2026-09-16T09:30"),
                theirs(2, "2026-09-23T09:00", "2026-09-23T09:30"),
            ),
        )
        assertEquals("n16", (days.getValue(day).single() as DayItem.Device).noteId)
        assertEquals(
            "n23",
            (days.getValue(LocalDate.parse("2026-09-23")).single() as DayItem.Device).noteId,
        )
    }

    @Test
    fun `and it survives that morning being moved half an hour`() {
        // Nearest rather than equal: the standup slips to half past and keeps its page.
        val days = bucket(
            ours = listOf(
                ours("n16", "2026-09-16T09:00", "2026-09-16T09:30", occurrence = "2026-09-16T09:00"),
                ours("n23", "2026-09-23T09:00", "2026-09-23T09:30", occurrence = "2026-09-23T09:00"),
            ),
            device = listOf(
                theirs(1, "2026-09-16T09:30", "2026-09-16T10:00"),
                theirs(2, "2026-09-23T09:00", "2026-09-23T09:30"),
            ),
        )
        assertEquals("n16", (days.getValue(day).single() as DayItem.Device).noteId)
    }

    @Test
    fun `a line about a meeting that is gone still draws, from what it remembers`() {
        val items = bucket(ours = listOf(ours(start = "2026-09-16T10:00", end = "2026-09-16T11:00"))).getValue(day)
        assertEquals(1, items.size)
        assertEquals("n1", items.single().nodeId)
    }
}
