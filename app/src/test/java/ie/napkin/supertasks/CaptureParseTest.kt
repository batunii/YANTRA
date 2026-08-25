package ie.napkin.supertasks

import ie.napkin.supertasks.data.capture.CaptureParse
import ie.napkin.supertasks.data.capture.Captured
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Reading a task out of a line of typing.
 *
 * Most of these are about *not* matching. A parser that reads too much is worse than one that reads
 * nothing: a word silently deleted from a title, or a due date the user never asked for, are both
 * failures they may not notice until the task is missing from Today.
 */
class CaptureParseTest {

    /** A Wednesday, so weekday arithmetic has somewhere to be wrong. */
    private val today = LocalDate.of(2026, 8, 26)

    private fun parse(s: String) = CaptureParse.parse(s, today)

    // ---- the ordinary cases ----

    @Test
    fun `today is stripped and understood`() {
        val c = parse("buy milk today")
        assertEquals("buy milk", c.title)
        assertEquals(today, c.date)
    }

    @Test
    fun `tomorrow`() {
        assertEquals(today.plusDays(1), parse("call the vet tomorrow").date)
    }

    @Test
    fun `a weekday means the next one, never today`() {
        // Today is a Wednesday. "wednesday" has to mean next week, or a task typed on the day would
        // land in the past the moment it was created.
        assertEquals(DayOfWeek.WEDNESDAY, today.dayOfWeek)
        val c = parse("standup wednesday")
        assertEquals(today.plusDays(7), c.date)
        assertEquals("standup", c.title)
    }

    @Test
    fun `next weekday is a week further out`() {
        assertEquals(today.plusDays(5 + 7), parse("review next monday").date)
    }

    @Test
    fun `twelve hour times, including the two that break naive arithmetic`() {
        assertEquals(LocalTime.of(18, 0), parse("dinner 6pm").time)
        assertEquals(LocalTime.of(18, 30), parse("dinner 6:30pm").time)
        assertEquals(LocalTime.of(9, 0), parse("standup 9 am").time)
        // 12am is midnight and 12pm is noon. Adding twelve to either gets both wrong.
        assertEquals(LocalTime.of(0, 0), parse("shift 12am").time)
        assertEquals(LocalTime.of(12, 0), parse("lunch 12pm").time)
    }

    @Test
    fun `twenty four hour times`() {
        assertEquals(LocalTime.of(18, 30), parse("dinner 18:30").time)
        assertEquals(LocalTime.of(7, 5), parse("train 07:05").time)
    }

    @Test
    fun `labels, more than one`() {
        val c = parse("fix the sink #home #urgent")
        assertEquals("fix the sink", c.title)
        assertEquals(listOf("home", "urgent"), c.labels)
    }

    @Test
    fun `priority by name and by shorthand`() {
        assertEquals("High", parse("ship it !high").priority)
        assertEquals("High", parse("ship it !High").priority)
        assertEquals("Medium", parse("ship it !med").priority)
        assertEquals("Low", parse("ship it !l").priority)
    }

    @Test
    fun `everything at once`() {
        val c = parse("buy milk tomorrow 6pm #home !high")
        assertEquals("buy milk", c.title)
        assertEquals(today.plusDays(1), c.date)
        assertEquals(LocalTime.of(18, 0), c.time)
        assertEquals(listOf("home"), c.labels)
        assertEquals("High", c.priority)
    }

    @Test
    fun `dates in the shapes people write them`() {
        assertEquals(LocalDate.of(2026, 12, 25), parse("presents 25/12").date)
        assertEquals(LocalDate.of(2027, 1, 3), parse("thing 2027-01-03").date)
        assertEquals(LocalDate.of(2026, 9, 4), parse("thing 4 sep").date)
        assertEquals(LocalDate.of(2026, 9, 4), parse("thing sep 4").date)
    }

    @Test
    fun `a bare date already past this year means next year`() {
        // Today is late August. "4 march" is not five months ago; nobody schedules into the past.
        assertEquals(LocalDate.of(2027, 3, 4), parse("taxes 4 march").date)
    }

    // ---- the cases where it must keep quiet ----

    @Test
    fun `plain text is left completely alone`() {
        val c = parse("think about the roadmap")
        assertEquals("think about the roadmap", c.title)
        assertNull(c.date)
        assertTrue(!c.hasAnything)
    }

    @Test
    fun `a token that is the whole title stays as the title`() {
        // A task called "Today" is a task called Today, not an empty task due today.
        assertEquals("today", parse("today").title)
        assertEquals("#home", parse("#home").title)
    }

    @Test
    fun `a hash inside a word is not a label`() {
        assertEquals("issue C#100 is open", parse("issue C#100 is open").title)
    }

    @Test
    fun `an unknown priority word is not a priority`() {
        val c = parse("do it !soon")
        assertEquals("do it !soon", c.title)
        assertNull(c.priority)
    }

    @Test
    fun `a bare number is not a time`() {
        // "buy 18 eggs" must not become a task at six in the evening.
        val c = parse("buy 18 eggs")
        assertEquals("buy 18 eggs", c.title)
        assertNull(c.time)
    }

    @Test
    fun `an impossible clock time is not a time`() {
        assertNull(parse("call 25:99").time)
        assertNull(parse("thing 19pm").time)
    }

    @Test
    fun `an impossible date is not a date`() {
        assertNull(parse("thing 31/2").date)
        assertNull(parse("thing 45/13").date)
    }

    // ---- what the field needs to highlight ----

    @Test
    fun `every match reports where it was`() {
        // The safety story: the user sees what was consumed, in place, as they type. Positions are
        // into the original string, so they survive the title being rewritten.
        val text = "buy milk tomorrow #home"
        val c = parse(text)
        assertEquals(2, c.spans.size)
        c.spans.forEach { span ->
            val matched = text.substring(span.range.first, span.range.last + 1)
            when (span.kind) {
                Captured.Kind.DATE -> assertEquals("tomorrow", matched)
                Captured.Kind.LABEL -> assertEquals("#home", matched)
                else -> error("unexpected $span")
            }
        }
    }

    @Test
    fun `spans come back in the order they appear`() {
        val c = parse("thing #a tomorrow !high")
        assertEquals(c.spans.map { it.range.first }.sorted(), c.spans.map { it.range.first })
    }

    @Test
    fun `a time with no day is the next time it is that time`() {
        val c = parse("standup 9am")
        assertTrue("no day was chosen", c.date != null)
        assertTrue(c.date == today || c.date == today.plusDays(1))
    }
}
