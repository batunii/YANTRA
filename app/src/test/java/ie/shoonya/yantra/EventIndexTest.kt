package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.data.workspace.PageMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * What an event line becomes in the index — CALENDAR_PLAN.md §7 phase 1.
 *
 * The index is disposable and rebuilt from files, so what is worth pinning here is the *translation*:
 * that the local time is carried through untouched, that the UTC pair used for range queries is
 * derived the way the doc claims, and that an event's own `^id` survives an unrelated edit above it.
 */
class EventIndexTest {

    private val dublin = ZoneId.of("Europe/Dublin")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun rows(vararg lines: String, zone: ZoneId = dublin) = PageMapper.toRows(
        PageCodec.decode(
            "---\nid: p1\ntype: list\nmodified_at: 2026-09-09T10:00:00Z\n---\n" + lines.joinToString("\n") + "\n"
        ),
        workspaceId = "ws",
        zone = zone,
    )

    @Test
    fun `an event line becomes an event node and an event row`() {
        val m = rows("@ 2026-09-11T14:00/PT1H Design review ^e1 loc:Room4 remind:15")
        assertEquals(NodeType.EVENT, m.children.single().type)
        assertEquals("Design review", m.children.single().title)

        val e = m.events.single()
        assertEquals("e1", e.nodeId)
        assertEquals("2026-09-11T14:00", e.startLocal)
        assertEquals("2026-09-11T15:00", e.endLocal)
        assertEquals("Room4", e.location)
        assertEquals(15, e.reminderMin)
        assertTrue(!e.allDay)
        assertNull(e.rrule)
    }

    @Test
    fun `an event keeps its own id so a series cannot come apart`() {
        // A derived id is the page id plus a line number, which changes the moment anything is
        // inserted above. An override names its series by id, so a renumbering would orphan it.
        val before = rows(
            "@ 2026-09-07T09:00/PT15M Standup ^s1 rrule:FREQ=WEEKLY",
        ).events.single().nodeId
        val after = rows(
            "# A heading somebody added later",
            "@ 2026-09-07T09:00/PT15M Standup ^s1 rrule:FREQ=WEEKLY",
        ).events.single().nodeId
        assertEquals("s1", before)
        assertEquals(before, after)
    }

    @Test
    fun `an event with no id still indexes, from its position`() {
        val e = rows("@ 2026-09-11 Something untitled").events.single()
        assertTrue("derived id was '${e.nodeId}'", e.nodeId.contains(PageMapper.BLOCK_SEP))
    }

    @Test
    fun `a zoned event resolves in its own zone, whatever the device thinks`() {
        val fromDublin = rows("@ 2026-09-11T14:00[Europe/Dublin]/PT1H Call", zone = dublin).events.single()
        val fromTokyo = rows("@ 2026-09-11T14:00[Europe/Dublin]/PT1H Call", zone = tokyo).events.single()
        assertEquals("a zone in the file wins", fromDublin.startUtc, fromTokyo.startUtc)
        assertEquals("Europe/Dublin", fromDublin.zone)
    }

    @Test
    fun `a floating event resolves in the device's zone, which is the point of floating`() {
        val fromDublin = rows("@ 2026-09-11T09:00/PT15M Morning pages", zone = dublin).events.single()
        val fromTokyo = rows("@ 2026-09-11T09:00/PT15M Morning pages", zone = tokyo).events.single()
        assertTrue("floating must differ by device zone", fromDublin.startUtc != fromTokyo.startUtc)
        // The local time — the authoritative half — is identical on both.
        assertEquals(fromDublin.startLocal, fromTokyo.startLocal)
        assertNull(fromDublin.zone)
    }

    @Test
    fun `an all-day event spans to an exclusive end`() {
        val e = rows("@ 2026-09-11/2026-09-13 Conference").events.single()
        assertTrue(e.allDay)
        assertEquals("2026-09-14T00:00", e.endLocal)
        assertEquals(3 * 24 * 60 * 60 * 1000L, e.endUtc - e.startUtc)
    }

    @Test
    fun `a bare series reference is written out as its own start`() {
        // `series:s1` means "the occurrence at this line's own start". The index stores that start
        // rather than a null every reader would have to know how to fill in.
        val e = rows("@ 2026-11-04T09:00/PT15M Standup ^s1x2 series:s1 cancelled").events.single()
        assertEquals("s1", e.seriesId)
        assertEquals("2026-11-04T09:00", e.seriesOriginal)
        assertTrue(e.cancelled)
    }

    @Test
    fun `a moved occurrence records the occurrence it replaces, not where it went`() {
        val e = rows("@ 2026-10-28T09:30/PT15M Standup ^s1x1 series:s1@2026-10-28T09:00").events.single()
        assertEquals("2026-10-28T09:00", e.seriesOriginal)
        assertEquals("2026-10-28T09:30", e.startLocal)
    }

    @Test
    fun `a rule is carried through verbatim`() {
        val e = rows("@ 2026-09-07T09:00/PT15M Standup ^s1 rrule:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR").events.single()
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", e.rrule)
    }

    @Test
    fun `tasks and events on one page do not disturb each other`() {
        val m = rows(
            "- [ ] Write the deck ^t1 due:2026-09-12",
            "@ 2026-09-11T14:00/PT1H Design review ^e1",
            "- Just a bullet",
        )
        assertEquals(3, m.children.size)
        assertEquals(1, m.events.size)
        assertEquals("e1", m.events.single().nodeId)
        assertNotNull(m.values.firstOrNull())      // the task's due date still indexed
    }

    @Test
    fun `a line that is not an event contributes no event row`() {
        assertTrue(rows("@ sometime next week, ask Sam").events.isEmpty())
        assertTrue(rows("- [ ] Write the deck ^t1").events.isEmpty())
    }
}
