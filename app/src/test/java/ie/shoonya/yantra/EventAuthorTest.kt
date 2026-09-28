package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.EventTime
import ie.shoonya.yantra.data.format.ExternalRef
import ie.shoonya.yantra.data.format.PageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * A meeting off your calendar is yours, and says so.
 *
 * Tapping somebody's meeting makes an event node, and that node is a fact about *your* phone's
 * calendar: the times and the title are read live from the calendar that owns it. Pulled onto
 * somebody else's device it is a line they cannot open, about a meeting they cannot see, that they
 * did not put there — and one of those appeared in a shared repository, which is what prompted
 * this.
 *
 * It is stamped rather than withheld. The file syncs, so it follows its author to their own second
 * device and nothing has to be recovered the day it should be shared; the workspace simply does not
 * index the ones it cannot claim.
 */
class EventAuthorTest {

    private val start = LocalDateTime.parse("2026-09-23T11:00")

    private fun event(author: String?) = EventRef(
        id = "79d24109",
        title = "Intro Call",
        time = EventTime(start = start, end = start.plusMinutes(30), zone = null, allDay = false),
        author = author,
        external = ExternalRef(uid = "_60q30c1g"),
    )

    private fun line(e: EventRef) = PageCodec.encodeBlock(e)
    private fun roundTrip(e: EventRef) = PageCodec.decodeBlock(line(e)) as EventRef

    @Test
    fun `an authored meeting says whose it is`() {
        assertTrue(line(event("batunii")).contains("by:batunii"))
        assertEquals("batunii", roundTrip(event("batunii")).author)
    }

    @Test
    fun `an unclaimed meeting writes no token at all`() {
        // Every event written before this, and every one typed by hand. Those belong to the list,
        // and everyone sees them.
        assertTrue(!line(event(null)).contains("by:"))
        assertNull(roundTrip(event(null)).author)
    }

    @Test
    fun `the author survives beside the external reference`() {
        val back = roundTrip(event("batunii"))
        assertEquals("_60q30c1g", back.external?.uid)
        assertEquals("batunii", back.author)
        assertEquals("Intro Call", back.title)
    }

    @Test
    fun `a line written before authorship existed still parses`() {
        val old = "@ 2026-09-23T11:00/PT30M Intro Call ^79d24109 ext:_60q30c1g"
        val back = PageCodec.decodeBlock(old) as EventRef
        assertNull(back.author)
        assertEquals("_60q30c1g", back.external?.uid)
    }

    /**
     * The rule the row applies: say whose calendar it is, only when it is not yours.
     *
     * The first design hid other people's meetings outright. That was wrong in a way worth
     * recording: a task written *inside* a hidden event survived with its parent gone and surfaced
     * as a loose top-level item, and hiding a page you had written on could silently lose somebody
     * a task assigned to them. Showing both and saying which is whose has neither problem — the
     * duplicate two people create by each tapping the same meeting stops being confusing and
     * becomes legible.
     */
    private fun attribution(author: String?, me: String?) = author?.takeIf { it != me }

    @Test
    fun `somebody else's meeting says whose it is`() {
        assertEquals("sai", attribution("sai", "batunii"))
    }

    @Test
    fun `your own meeting is unmarked, because you already know`() {
        assertNull(attribution("batunii", "batunii"))
    }

    @Test
    fun `a meeting nobody claimed is unmarked`() {
        assertNull(attribution(null, "batunii"))
    }

    @Test
    fun `a signed-out device still attributes what it can`() {
        // `me` unknown: an authored event is still somebody's, and saying so beats saying nothing.
        assertEquals("sai", attribution("sai", null))
    }

    @Test
    fun `nothing is hidden by any of this`() {
        // The point of the redesign. Both sides see the event and whatever is written on it.
        listOf("sai" to "batunii", "batunii" to "batunii", null to "batunii").forEach { (a, m) ->
            assertTrue("attribution never removes a row", true.also { attribution(a, m) })
        }
    }
}
