package ie.shoonya.yantra

import ie.shoonya.yantra.data.device.MeetingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a real invitation — CALENDAR_PLAN.md §24.
 *
 * Both halves of this are things you only find by looking at what a calendar actually hands back:
 * the description is usually **HTML**, because that is what the web client writes, and the join link
 * is buried in the middle of thirty lines of dial-in numbers. Neither is obvious, and both make the
 * difference between a page you can read and one you scroll past.
 */
class MeetingTextTest {

    // ---- the join link ----

    @Test
    fun `a meet link in the location is the one to join`() {
        // Where a provider that knows the meeting is a video call puts it.
        val c = MeetingText.conferenceIn("https://meet.google.com/abc-defg-hij", null)
        assertEquals("Google Meet", c!!.name)
        assertEquals("https://meet.google.com/abc-defg-hij", c.url)
    }

    @Test
    fun `a link buried in the description is still found`() {
        val description = """
            Hello everyone,

            Join Zoom Meeting
            https://napkin.zoom.us/j/12345678?pwd=abcd

            Dial by your location
            +353 1 234 5678 Ireland
        """.trimIndent()
        assertEquals("Zoom", MeetingText.conferenceIn(null, description)!!.name)
    }

    @Test
    fun `the location wins over the description`() {
        val c = MeetingText.conferenceIn(
            "https://meet.google.com/abc-defg-hij",
            "Backup: https://napkin.zoom.us/j/1",
        )
        assertEquals("Google Meet", c!!.name)
    }

    @Test
    fun `an ordinary link is not offered as a meeting`() {
        // "Join meeting" on a link to a document is worse than no button: it is a promise about
        // what pressing it will do.
        assertNull(MeetingText.conferenceIn("Room 4", "The deck: https://docs.google.com/document/d/1"))
    }

    @Test
    fun `the services people actually use are recognised`() {
        listOf(
            "https://meet.google.com/x" to "Google Meet",
            "https://napkin.zoom.us/j/1" to "Zoom",
            "https://teams.microsoft.com/l/meetup-join/x" to "Microsoft Teams",
            "https://napkin.webex.com/meet/x" to "Webex",
            "https://whereby.com/napkin" to "Whereby",
        ).forEach { (url, name) -> assertEquals(url, name, MeetingText.hostName(url)) }
    }

    @Test
    fun `a trailing full stop is not part of the link`() {
        // Prose ends in punctuation and URLs do not. Left on, the link 404s.
        assertEquals(
            listOf("https://meet.google.com/abc"),
            MeetingText.linksIn("See you at https://meet.google.com/abc."),
        )
    }

    @Test
    fun `the same link twice is one link`() {
        assertEquals(1, MeetingText.linksIn("https://a.com/x and again https://a.com/x").size)
    }

    // ---- the description ----

    @Test
    fun `html from the web client comes back readable`() {
        val raw = "Agenda:<br><ul><li>Numbers</li><li>Timeline</li></ul><p>Bring the deck.</p>"
        val out = MeetingText.readable(raw)
        assertTrue("no tags left in: $out", !out.contains('<'))
        assertTrue(out.contains("• Numbers"))
        assertTrue(out.contains("Bring the deck."))
    }

    @Test
    fun `entities are unescaped`() {
        assertEquals("Tom & Jerry's \"meeting\"", MeetingText.readable("Tom &amp; Jerry&#39;s &quot;meeting&quot;"))
    }

    @Test
    fun `plain text passes through untouched, which is the common case`() {
        val plain = "Quick sync about the deck.\n\nBring numbers."
        assertEquals(plain, MeetingText.readable(plain))
    }

    @Test
    fun `nothing is nothing`() {
        assertEquals("", MeetingText.readable(null))
        assertEquals("", MeetingText.readable("   "))
        assertEquals(emptyList<String>(), MeetingText.linksIn(null))
    }

    @Test
    fun `the boilerplate gap is not twenty blank lines`() {
        val out = MeetingText.readable("Top<br><br><br><br><br>Bottom")
        assertEquals("Top\n\nBottom", out)
    }
}
