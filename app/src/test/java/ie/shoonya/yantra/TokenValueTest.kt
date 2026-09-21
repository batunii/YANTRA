package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.EventTime
import ie.shoonya.yantra.data.format.ExternalRef
import ie.shoonya.yantra.data.format.PageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * A token is one word — CALENDAR_PLAN.md §27.
 *
 * The whole line grammar rests on it. Tokens are scanned **right to left** and the first word that is
 * not one ends the scan, everything before it being the title. So a value containing a space does
 * not merely look untidy: it ends the scan early and swallows every token written before it —
 * including `^id`.
 *
 * That is not hypothetical. A meeting whose location was "Microsoft Teams Meeting" wrote a line whose
 * id could not be read back, so the event was re-indexed under a positional id, stopped being
 * findable by the one that created it, and made a fresh page on every single tap. The meeting beside
 * it, with no location, worked perfectly — which is exactly what "half the time it opens" looked
 * like from outside.
 */
class TokenValueTest {

    private fun event(
        location: String? = null,
        uid: String? = null,
        title: String = "Pluto x Napkin",
    ) = EventRef(
        id = "n1",
        title = title,
        time = EventTime(
            start = LocalDateTime.parse("2026-09-16T10:00"),
            end = LocalDateTime.parse("2026-09-16T11:00"),
        ),
        location = location,
        external = uid?.let { ExternalRef(it) },
    )

    private fun roundTrip(e: EventRef): EventRef {
        val line = PageCodec.encodeBlock(e.copy(raw = null))
        assertTrue("a line must stay one line: $line", '\n' !in line)
        return PageCodec.decodeBlock(line) as EventRef
    }

    @Test
    fun `a location with spaces keeps the id, which is the whole point`() {
        val back = roundTrip(event(location = "Microsoft Teams Meeting", uid = "abc@google.com"))
        assertEquals("n1", back.id)
        assertEquals("Microsoft Teams Meeting", back.location)
        assertEquals("abc@google.com", back.external?.uid)
        assertEquals("Pluto x Napkin", back.title)
    }

    @Test
    fun `the awkward values a real calendar hands back all survive`() {
        listOf(
            "Room 4",
            "Napkin HQ, 3rd floor",
            "Microsoft Teams Meeting",
            "50% full",                       // the escape character itself
            "https://meet.google.com/abc-def",
            "Café — back room",               // unicode and a dash
        ).forEach { where ->
            val back = roundTrip(event(location = where, uid = "u@example.com"))
            assertEquals(where, back.location)
            assertEquals("the id must survive $where", "n1", back.id)
        }
    }

    @Test
    fun `a percent in a location is not doubled on the way back`() {
        // %25 encodes the percent, so decoding has to undo it exactly once.
        val back = roundTrip(roundTrip(event(location = "50% full")))
        assertEquals("50% full", back.location)
    }

    @Test
    fun `an ordinary location is written plainly, because almost all of them are`() {
        val line = PageCodec.encodeBlock(event(location = "Room4"))
        assertTrue("no encoding where none is needed: $line", line.contains("loc:Room4"))
    }

    @Test
    fun `a uid with a space in it survives too`() {
        // Not common, but a uid is opaque and generated elsewhere; assuming it has no space is
        // exactly the assumption that broke the location.
        val back = roundTrip(event(uid = "weird uid@example.com"))
        assertEquals("weird uid@example.com", back.external?.uid)
        assertEquals("n1", back.id)
    }
}
