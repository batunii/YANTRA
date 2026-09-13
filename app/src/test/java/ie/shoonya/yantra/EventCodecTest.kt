package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.Bullet
import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.data.format.Prose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The `@ ` event line — CALENDAR_PLAN.md §3.
 *
 * Two properties matter more than any individual field. **Parse → render → parse is a fixed point**,
 * or an event drifts a little every time the app touches the page it is on. And **a line this build
 * does not fully understand comes back byte-exact**, because the alternative is a file written on a
 * newer phone quietly losing a field when an older one opens it.
 */
class EventCodecTest {

    private fun parse(line: String): EventRef? = PageCodec.decodeBlock(line) as? EventRef

    /** Parse, render, parse again — the second block must equal the first. */
    private fun roundTrip(line: String): EventRef {
        val once = parse(line) ?: error("did not parse as an event: $line")
        val rendered = PageCodec.encodeBlock(once.copy(raw = null))
        val twice = parse(rendered) ?: error("re-render did not parse: $rendered")
        assertEquals("not a fixed point: $line -> $rendered", once.copy(raw = null), twice.copy(raw = null))
        return once
    }

    // ---- the when-slot ----

    @Test
    fun `an all-day event is a bare date`() {
        val e = roundTrip("@ 2026-09-11 Deirdre's birthday")
        assertTrue(e.time.allDay)
        assertEquals(LocalDateTime.parse("2026-09-11T00:00"), e.time.start)
        // Exclusive end, so the span is one day rather than zero.
        assertEquals(LocalDateTime.parse("2026-09-12T00:00"), e.time.end)
        assertEquals("Deirdre's birthday", e.title)
    }

    @Test
    fun `an all-day span reads inclusively and stores exclusively`() {
        // "the 11th to the 13th" is three days to a person and an exclusive 14th to arithmetic.
        val e = roundTrip("@ 2026-09-11/2026-09-13 Conference")
        assertTrue(e.time.allDay)
        assertEquals(LocalDateTime.parse("2026-09-14T00:00"), e.time.end)
        assertEquals(Duration.ofDays(3), e.time.duration)
        assertEquals("@ 2026-09-11/2026-09-13 Conference", PageCodec.encodeBlock(e.copy(raw = null)))
    }

    @Test
    fun `a timed event carries a duration`() {
        val e = roundTrip("@ 2026-09-11T14:00/PT1H Design review")
        assertTrue(!e.time.allDay)
        assertEquals(Duration.ofHours(1), e.time.duration)
    }

    @Test
    fun `an explicit end is accepted and rendered as a duration`() {
        val e = parse("@ 2026-09-11T14:00/2026-09-11T15:30 Design review")!!
        assertEquals(Duration.ofMinutes(90), e.time.duration)
        assertEquals(
            "@ 2026-09-11T14:00/PT1H30M Design review",
            PageCodec.encodeBlock(e.copy(raw = null)),
        )
    }

    @Test
    fun `a hand-written explicit end is left alone`() {
        // The renderer prefers a duration, but rawStillDescribes re-parses the original line, gets
        // the same event, and writes the bytes that were already there. Somebody's file does not get
        // reformatted because the app happened to read it.
        val line = "@ 2026-09-11T14:00/2026-09-11T15:30 Design review"
        assertEquals(line, PageCodec.encodeBlock(parse(line)!!))
    }

    @Test
    fun `a moment has no tail`() {
        val e = roundTrip("@ 2026-09-11T14:00 Doorbell")
        assertTrue(e.time.isInstantaneous)
        assertEquals("@ 2026-09-11T14:00 Doorbell", PageCodec.encodeBlock(e.copy(raw = null)))
    }

    @Test
    fun `a zone survives, brackets and slashes and all`() {
        // Europe/Dublin contains the same slash that separates start from duration, so the split
        // has to happen outside the brackets.
        val e = roundTrip("@ 2026-09-11T14:00[Europe/Dublin]/PT1H Call with Tokyo")
        assertEquals(ZoneId.of("Europe/Dublin"), e.time.zone)
        assertEquals(Duration.ofHours(1), e.time.duration)
        assertEquals("Call with Tokyo", e.title)
    }

    @Test
    fun `no zone means floating`() {
        assertNull(roundTrip("@ 2026-09-11T09:00/PT15M Morning pages").time.zone)
    }

    @Test
    fun `an end before its start is not an event`() {
        assertNull(parse("@ 2026-09-11T15:00/2026-09-11T14:00 Backwards"))
    }

    // ---- tokens ----

    @Test
    fun `every token round-trips`() {
        val e = roundTrip(
            "@ 2026-09-11T14:00/PT1H Design review ^e1 rrule:FREQ=WEEKLY " +
                "loc:Room4 remind:15 !High #work @sam @deirdre",
        )
        assertEquals("e1", e.id)
        assertEquals("Design review", e.title)
        assertEquals("FREQ=WEEKLY", e.rrule)
        assertEquals("Room4", e.location)
        assertEquals(15, e.reminderMin)
        assertEquals("High", e.priority)
        assertEquals(listOf("work"), e.labels)
        assertEquals(listOf("sam", "deirdre"), e.attendees)
    }

    @Test
    fun `the title keeps words that only look like tokens`() {
        // The same right-to-left rule the task line uses: the scan stops at "pencils", so "#2" is
        // part of what the line says rather than a label.
        assertEquals("Buy #2 pencils", roundTrip("@ 2026-09-11 Buy #2 pencils").title)
    }

    @Test
    fun `an unknown token stays in the title rather than vanishing`() {
        val e = parse("@ 2026-09-11 Standup zoom:abc123")!!
        assertEquals("Standup zoom:abc123", e.title)
        // And the line comes back byte-exact, which is the point.
        assertEquals("@ 2026-09-11 Standup zoom:abc123", PageCodec.encodeBlock(e))
    }

    // ---- series, overrides, cancellations ----

    @Test
    fun `a series line carries its rule`() {
        val e = roundTrip("@ 2026-09-07T09:00/PT15M Standup ^s1 rrule:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", e.rrule)
        assertNull(e.series)
    }

    @Test
    fun `a moved occurrence names the one it replaces`() {
        val e = roundTrip("@ 2026-10-28T09:30/PT15M Standup ^s1x1 series:s1@2026-10-28T09:00")
        assertEquals("s1", e.series!!.id)
        assertEquals(LocalDateTime.parse("2026-10-28T09:00"), e.series!!.originalStart)
        assertTrue(!e.cancelled)
    }

    @Test
    fun `a cancellation sitting on its own start needs no at-sign`() {
        val e = roundTrip("@ 2026-11-04T09:00/PT15M Standup ^s1x2 series:s1 cancelled")
        assertEquals("s1", e.series!!.id)
        assertNull(e.series!!.originalStart)
        assertTrue(e.cancelled)
    }

    @Test
    fun `an override that did not move drops the redundant original`() {
        // originalStart equal to the line's own start is what the bare form already means, so
        // writing it again would be noise that two devices could disagree about.
        val parsed = parse("@ 2026-11-04T09:00/PT15M Standup ^s1x2 series:s1@2026-11-04T09:00")!!
        assertEquals(
            "@ 2026-11-04T09:00/PT15M Standup ^s1x2 series:s1",
            PageCodec.encodeBlock(parsed.copy(raw = null)),
        )
    }

    // ---- sittings ----

    @Test
    fun `a sitting points at the task it is time for`() {
        val e = roundTrip("@ 2026-09-12T14:00/PT2H ^s1 for:t1")
        assertEquals("t1", e.forTaskId)
        assertEquals("", e.title)      // it borrows the task's; it does not keep one
    }

    @Test
    fun `a sitting with a title keeps it, because dropping it would lose somebody's words`() {
        // Nothing in the app writes one, but a hand-edited file may, and the parser's job is not to
        // have opinions about that.
        assertEquals("Deck, second go", roundTrip("@ 2026-09-12T14:00/PT2H Deck, second go ^s1 for:t1").title)
    }

    @Test
    fun `an ordinary event has no referent`() {
        assertNull(roundTrip("@ 2026-09-11T14:00/PT1H Design review ^e1").forTaskId)
    }

    @Test
    fun `for and rrule and a reminder coexist`() {
        val e = roundTrip("@ 2026-09-12T09:00/PT1H ^s2 rrule:FREQ=WEEKLY for:t1 remind:5")
        assertEquals("t1", e.forTaskId)
        assertEquals("FREQ=WEEKLY", e.rrule)
        assertEquals(5, e.reminderMin)
    }

    @Test
    fun `a task and its two sittings survive a whole-page round trip`() {
        val page = """
            |---
            |id: p1
            |type: list
            |modified_at: 2026-09-12T10:00:00Z
            |---
            |- [ ] Write the deck ^t1 due:2026-09-18
            |@ 2026-09-12T14:00/PT2H ^s1 for:t1
            |@ 2026-09-13T09:00/PT1H ^s2 for:t1
        """.trimMargin() + "\n"
        val doc = PageCodec.decode(page)
        assertEquals(listOf("t1", "t1"), doc.blocks.filterIsInstance<EventRef>().map { it.forTaskId })
        assertEquals(page, PageCodec.encode(doc))
    }

    // ---- colour ----

    @Test
    fun `an event can say what colour it wears`() {
        val e = roundTrip("@ 2026-09-13T14:00/PT1H Design review ^e1 col:Teal")
        assertEquals("Teal", e.color)
    }

    @Test
    fun `a colour this build does not know is kept rather than dropped`() {
        // The word is the file's, not ours. Somebody on a newer build picking a colour we have never
        // heard of must not lose it the first time this one opens their page.
        val e = roundTrip("@ 2026-09-13T14:00/PT1H Design review ^e1 col:Vermilion")
        assertEquals("Vermilion", e.color)
    }

    @Test
    fun `no colour is the ordinary case and writes nothing`() {
        assertNull(roundTrip("@ 2026-09-13T14:00/PT1H Design review ^e1").color)
        assertEquals(
            "@ 2026-09-13T14:00/PT1H Design review ^e1",
            PageCodec.encodeBlock(parse("@ 2026-09-13T14:00/PT1H Design review ^e1")!!.copy(raw = null)),
        )
    }

    @Test
    fun `a sitting can wear a colour too`() {
        val e = roundTrip("@ 2026-09-13T14:00/PT2H ^s1 for:t1 col:Plum")
        assertEquals("t1", e.forTaskId)
        assertEquals("Plum", e.color)
    }

    // ---- not events ----

    @Test
    fun `a bullet is still a bullet`() {
        assertTrue(PageCodec.decodeBlock("- Buy milk") is Bullet)
        assertTrue(PageCodec.decodeBlock("* Buy milk") is Prose)
    }

    @Test
    fun `an at-sign line with no readable time is prose, not a lost event`() {
        val b = PageCodec.decodeBlock("@ sometime next week, ask Sam")
        assertTrue("got ${b::class.simpleName}", b is Prose)
        assertEquals("@ sometime next week, ask Sam", PageCodec.encodeBlock(b))
    }

    @Test
    fun `an email address at the start of a line is not an event`() {
        assertTrue(PageCodec.decodeBlock("@sam owes me a review") is Prose)
    }

    @Test
    fun `an event survives a whole-page round trip`() {
        val page = """
            |---
            |id: p1
            |type: list
            |modified_at: 2026-09-09T10:00:00Z
            |---
            |# Week
            |
            |- [ ] Write the deck ^t1 due:2026-09-12
            |@ 2026-09-11T14:00/PT1H Design review ^e1 loc:Room4 @sam
            |@ 2026-09-07T09:00/PT15M Standup ^s1 rrule:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR
        """.trimMargin() + "\n"
        val doc = PageCodec.decode(page)
        assertEquals(2, doc.blocks.filterIsInstance<EventRef>().size)
        assertEquals(page, PageCodec.encode(doc))
    }
}
