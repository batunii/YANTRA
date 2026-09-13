package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.EventEntity
import ie.shoonya.yantra.data.device.DeviceEvent
import ie.shoonya.yantra.ui.calendar.CalendarBucketer
import ie.shoonya.yantra.ui.calendar.DayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A note about somebody else's meeting — CALENDAR_PLAN.md §19.
 *
 * The rule the whole design rests on is **one meeting, one block**. A note is not a copy: when the
 * meeting is on screen, the note's own line stands aside and the meeting carries the note's id, so
 * there is nothing to duplicate and nothing to drift. What the tests here mostly guard is the pair
 * of ways that can go wrong — drawing both, and drawing neither.
 */
class ExternalNoteBucketTest {

    private val dublin: ZoneId = ZoneId.of("Europe/Dublin")

    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(dublin).toInstant().toEpochMilli()

    private fun theirs(
        uid: String? = "abc@google.com",
        begin: String = "2026-09-16T14:00",
        end: String = "2026-09-16T15:00",
        title: String = "Design review",
    ) = DeviceEvent(
        instanceId = 1, eventId = 10, title = title,
        beginUtc = at(begin), endUtc = at(end),
        allDay = false, location = null, color = null, uid = uid,
    )

    private fun note(
        nodeId: String = "n1",
        uid: String? = "abc@google.com",
        occurrence: String? = null,
        start: String = "2026-09-16T14:00",
        end: String = "2026-09-16T15:00",
    ) = EventEntity(
        nodeId = nodeId,
        startLocal = start, endLocal = end, allDay = false,
        startUtc = at(start), endUtc = at(end),
        extUid = uid, extStart = occurrence,
    )

    private fun bucket(ours: List<EventEntity> = emptyList(), device: List<DeviceEvent> = emptyList()) =
        CalendarBucketer.bucket(
            events = ours,
            tasks = emptyList(),
            titles = ours.associate { it.nodeId to "Cached title" },
            device = device,
            from = LocalDate.parse("2026-09-01"),
            toExclusive = LocalDate.parse("2026-10-01"),
            zone = dublin,
        )

    private val day = LocalDate.parse("2026-09-16")

    @Test
    fun `a meeting and its note are one block, drawn at their times`() {
        val items = bucket(ours = listOf(note()), device = listOf(theirs())).getValue(day)
        assertEquals("one meeting is one block", 1, items.size)
        val block = items.single() as DayItem.Device
        assertEquals("n1", block.noteId)
        // Their title, not the cached one: whoever owns the meeting owns what it is called.
        assertEquals("Design review", block.title)
    }

    @Test
    fun `the block follows the meeting when it moves, because the note has no say in it`() {
        // The failure a copy would have: their meeting moves to four o'clock, and the copy sits at
        // two for ever. Here the note's cached time is simply not consulted.
        val moved = theirs(begin = "2026-09-16T16:00", end = "2026-09-16T17:00")
        val block = bucket(ours = listOf(note()), device = listOf(moved)).getValue(day)
            .single() as DayItem.Device
        assertEquals(16, block.start.hour)
        assertEquals("n1", block.noteId)
    }

    @Test
    fun `a note whose meeting cannot be read still draws, so notes never go missing`() {
        // Permission revoked, or the meeting deleted. The cached time is all there is, and it is
        // better than the note becoming unreachable.
        val items = bucket(ours = listOf(note()), device = emptyList()).getValue(day)
        assertEquals(1, items.size)
        assertTrue("falls back to our own line", items.single() is DayItem.Event)
    }

    @Test
    fun `a meeting with no note is still drawn, and has none`() {
        val block = bucket(device = listOf(theirs())).getValue(day).single() as DayItem.Device
        assertNull(block.noteId)
    }

    @Test
    fun `a note about one occurrence does not attach to every other one`() {
        // The standup is one uid and fifty-two meetings. A note about the sixteenth is about the
        // sixteenth.
        val mondayNote = note(occurrence = "2026-09-16T09:00", start = "2026-09-16T09:00", end = "2026-09-16T09:30")
        val thisMonday = theirs(begin = "2026-09-16T09:00", end = "2026-09-16T09:30", title = "Standup")
        val nextMonday = DeviceEvent(
            instanceId = 2, eventId = 10, title = "Standup",
            beginUtc = at("2026-09-23T09:00"), endUtc = at("2026-09-23T09:30"),
            allDay = false, location = null, color = null, uid = "abc@google.com",
        )
        val days = bucket(ours = listOf(mondayNote), device = listOf(thisMonday, nextMonday))
        assertEquals("n1", (days.getValue(day).single() as DayItem.Device).noteId)
        assertNull(
            "next Monday is a different meeting",
            (days.getValue(LocalDate.parse("2026-09-23")).single() as DayItem.Device).noteId,
        )
    }

    @Test
    fun `a note with no occurrence matches a meeting that happens once`() {
        val block = bucket(ours = listOf(note(occurrence = null)), device = listOf(theirs()))
            .getValue(day).single() as DayItem.Device
        assertEquals("n1", block.noteId)
    }

    @Test
    fun `an event the provider gives no identity for cannot be annotated, and is not`() {
        val block = bucket(ours = listOf(note()), device = listOf(theirs(uid = null))).getValue(day)
        // Two blocks: theirs (unannotatable) and the note falling back to its own line. Nothing is
        // matched to the wrong meeting, which is the part that matters.
        assertEquals(2, block.size)
        assertNull(block.filterIsInstance<DayItem.Device>().single().noteId)
    }
}
