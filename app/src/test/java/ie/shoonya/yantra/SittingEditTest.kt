package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.PageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * What a move does to a sitting's line — CALENDAR_PLAN.md §12.
 *
 * A sitting is the one block on a timeline with no words of its own, so it is the one whose line can
 * lose what makes it itself without anything looking obviously wrong. `for:` is the whole difference
 * between a sitting and a nameless hour, and a move rewrites the entire line — this walks the exact
 * transform `CalendarViewModel.moveTo` and `resizeTo` apply and reads the line back.
 */
class SittingEditTest {

    private fun parse(line: String) = PageCodec.decodeBlock(line) as? EventRef

    private val line = "@ 2026-09-13T14:00/PT1H ^s1 for:t1 remind:0"

    /** Exactly what moveTo does: a new start, the length carried with it, and the raw dropped. */
    private fun moved(to: LocalDateTime): String {
        val e = parse(line) ?: error("the fixture is not an event")
        val length = Duration.between(e.time.start, e.time.end)
        return PageCodec.encodeBlock(
            e.copy(time = e.time.copy(start = to, end = to.plus(length)), raw = null)
        )
    }

    @Test
    fun `a moved sitting is still a sitting`() {
        val out = moved(LocalDateTime.parse("2026-09-13T17:00"))
        val back = parse(out)
        assertNotNull("a moved sitting must still parse as an event: $out", back)
        assertEquals("it must still be for its task: $out", "t1", back!!.forTaskId)
        assertEquals(LocalDateTime.parse("2026-09-13T17:00"), back.time.start)
        assertEquals(Duration.ofHours(1), back.time.duration)
        assertEquals(0, back.reminderMin)
    }

    @Test
    fun `a moved sitting keeps its id, or the next edit cannot find it`() {
        val back = parse(moved(LocalDateTime.parse("2026-09-13T17:00")))!!
        assertEquals("s1", back.id)
    }

    @Test
    fun `a resized sitting is still a sitting`() {
        val e = parse(line)!!
        val out = PageCodec.encodeBlock(
            e.copy(time = e.time.copy(end = LocalDateTime.parse("2026-09-13T16:30")), raw = null)
        )
        val back = parse(out)
        assertNotNull("a resized sitting must still parse: $out", back)
        assertEquals("t1", back!!.forTaskId)
        assertEquals(Duration.ofMinutes(150), back.time.duration)
    }

    @Test
    fun `moving it across midnight does not lose it`() {
        val back = parse(moved(LocalDateTime.parse("2026-09-13T23:30")))
        assertNotNull(back)
        assertEquals("t1", back!!.forTaskId)
    }
}
