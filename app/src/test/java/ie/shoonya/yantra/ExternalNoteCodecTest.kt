package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.PageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * `ext:` — a line that is a **note about somebody else's meeting** — CALENDAR_PLAN.md §19.
 *
 * The identity written here comes from the sync source, not from this device, and it is very often
 * an address: `abc123@google.com` is the ordinary shape of a Google Calendar UID. That single fact
 * is what most of these guard, because the occurrence grammar `series:` established uses `@` as its
 * separator — and splitting a UID on the wrong `@` would silently take `google.com` for a date and
 * throw away the identity of every event in the account.
 */
class ExternalNoteCodecTest {

    private fun parse(line: String) = PageCodec.decodeBlock(line) as? EventRef

    private fun roundTrip(line: String): EventRef {
        val once = parse(line) ?: error("did not parse as an event: $line")
        val rendered = PageCodec.encodeBlock(once.copy(raw = null))
        assertEquals("not a fixed point", line, rendered)
        return once
    }

    @Test
    fun `a note names the meeting it is about`() {
        val e = roundTrip("@ 2026-09-16T14:00/PT1H Design review ^n1 ext:abc123")
        assertEquals("abc123", e.external!!.uid)
        assertNull(e.external!!.occurrence)
    }

    @Test
    fun `a uid containing an at-sign survives whole`() {
        // The ordinary Google Calendar shape. Split on the first `@` and the identity is gone.
        val e = roundTrip("@ 2026-09-16T14:00/PT1H Design review ^n1 ext:abc123@google.com")
        assertEquals("abc123@google.com", e.external!!.uid)
        assertNull("google.com is not a date", e.external!!.occurrence)
    }

    @Test
    fun `one occurrence of a repeating meeting names which`() {
        val e = roundTrip("@ 2026-09-16T09:00/PT30M Standup ^n2 ext:abc123@google.com@2026-09-16T09:00")
        assertEquals("abc123@google.com", e.external!!.uid)
        assertEquals(LocalDateTime.parse("2026-09-16T09:00"), e.external!!.occurrence)
    }

    @Test
    fun `a uid with no at-sign still takes an occurrence`() {
        val e = roundTrip("@ 2026-09-16T09:00/PT30M Standup ^n3 ext:plainuid@2026-09-16T09:00")
        assertEquals("plainuid", e.external!!.uid)
        assertEquals(LocalDateTime.parse("2026-09-16T09:00"), e.external!!.occurrence)
    }

    @Test
    fun `an ordinary event annotates nothing`() {
        assertNull(roundTrip("@ 2026-09-16T14:00/PT1H Design review ^e1").external)
    }

    @Test
    fun `an empty ext is not a reference and stays in the title`() {
        val e = parse("@ 2026-09-16T14:00/PT1H Design review ext:")!!
        assertNull(e.external)
        assertEquals("Design review ext:", e.title)
    }

    @Test
    fun `a note can carry everything else a line can`() {
        val e = roundTrip(
            "@ 2026-09-16T14:00/PT1H Design review ^n1 col:Teal ext:abc123@google.com remind:15"
        )
        assertEquals("abc123@google.com", e.external!!.uid)
        assertEquals("Teal", e.color)
        assertEquals(15, e.reminderMin)
    }

    @Test
    fun `a note survives a whole-page round trip`() {
        val page = """
            |---
            |id: p1
            |type: list
            |modified_at: 2026-09-16T10:00:00Z
            |---
            |@ 2026-09-16T14:00/PT1H Design review ^n1 ext:abc123@google.com
        """.trimMargin() + "\n"
        assertEquals(page, PageCodec.encode(PageCodec.decode(page)))
    }
}
