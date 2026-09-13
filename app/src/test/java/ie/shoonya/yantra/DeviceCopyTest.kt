package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.EventTime
import ie.shoonya.yantra.data.format.PageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * The copy made from somebody else's event — CALENDAR_PLAN.md §18.
 *
 * A **copy**, deliberately, not a link: their event lives in an account that syncs it to every
 * device they own, and this app holds no second authority on it. What matters is that the copy is an
 * ordinary event in every respect — it round-trips through the format, it can be coloured and moved,
 * and it carries **no trace of the provider**, because an id from a content provider is a local
 * number that means nothing on the next device and nothing at all after a reinstall.
 */
class DeviceCopyTest {

    private fun copy(
        title: String = "Design review",
        start: String = "2026-09-16T14:00",
        end: String = "2026-09-16T15:00",
        allDay: Boolean = false,
        location: String? = null,
    ) = EventRef(
        id = "e1",
        title = title,
        time = EventTime(
            start = LocalDateTime.parse(start),
            end = LocalDateTime.parse(end),
            zone = null,
            allDay = allDay,
        ),
        location = location,
    )

    @Test
    fun `a copy is an ordinary event line`() {
        assertEquals(
            "@ 2026-09-16T14:00/PT1H Design review ^e1",
            PageCodec.encodeBlock(copy()),
        )
    }

    @Test
    fun `a copy keeps the words, the hours and the place`() {
        val line = PageCodec.encodeBlock(copy(location = "Room4"))
        val back = PageCodec.decodeBlock(line) as EventRef
        assertEquals("Design review", back.title)
        assertEquals(LocalDateTime.parse("2026-09-16T14:00"), back.time.start)
        assertEquals(LocalDateTime.parse("2026-09-16T15:00"), back.time.end)
        assertEquals("Room4", back.location)
    }

    @Test
    fun `an all-day one stays all-day`() {
        val back = PageCodec.decodeBlock(
            PageCodec.encodeBlock(copy(start = "2026-09-16T00:00", end = "2026-09-17T00:00", allDay = true))
        ) as EventRef
        assertEquals(true, back.time.allDay)
        assertEquals(LocalDateTime.parse("2026-09-16T00:00"), back.time.start)
    }

    @Test
    fun `a copy carries nothing of the provider`() {
        // No instance id, no event id, no calendar id. Those are local numbers: meaningless on
        // another device, and gone after a reinstall. A line that carried one would be a line
        // claiming a relationship it cannot honour.
        val line = PageCodec.encodeBlock(copy())
        assertEquals(false, line.contains("device"))
        val back = PageCodec.decodeBlock(line) as EventRef
        assertNull("a copy is nobody's sitting", back.forTaskId)
        assertNull("and it is not a series member", back.series)
    }

    @Test
    fun `a copy is free to become yours in every other way`() {
        // The point of copying rather than linking: it can then do everything one of yours can.
        val coloured = copy().copy(color = "Teal", reminderMin = 15)
        val back = PageCodec.decodeBlock(PageCodec.encodeBlock(coloured)) as EventRef
        assertEquals("Teal", back.color)
        assertEquals(15, back.reminderMin)
    }
}
