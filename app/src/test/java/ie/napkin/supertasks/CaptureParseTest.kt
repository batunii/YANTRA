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

    // ---- a number earlier in the title must not disable date parsing ----

    @Test
    fun `a number beside a word earlier in the title does not swallow the date`() {
        // Reported: "1 sept" did not work while "sept 1" did. The cause was worse than the symptom —
        // each pattern used the *first* textual match whether or not it parsed, so "6 eggs" was
        // offered as a day-month pair, rejected, and the attempt abandoned before reaching the real
        // date two words later. Any number beside a word, anywhere earlier, silently disabled dates
        // for the whole line.
        listOf(
            "buy 6 eggs 1 sept",
            "buy 6 eggs sept 1",
            "pay 2 bills 1 sept",
            "get 3 things done 4 sep",
        ).forEach { line ->
            assertEquals("failed on: $line", LocalDate.of(2026, 9, 1).month, parse(line).date?.month)
        }
    }

    @Test
    fun `a number earlier does not swallow a plain tomorrow either`() {
        val c = parse("buy 6 eggs tomorrow")
        assertEquals(today.plusDays(1), c.date)
        assertEquals("buy 6 eggs", c.title)
    }

    @Test
    fun `an impossible time does not prevent a real one later`() {
        // Same flaw, same fix: the first match that looks like a time is not necessarily one.
        assertEquals(LocalTime.of(15, 0), parse("call 25:99 at 3pm").time)
    }

    @Test
    fun `ordinals are read the same as bare numbers`() {
        assertEquals(LocalDate.of(2026, 9, 1), parse("1st sept thing").date)
        assertEquals(LocalDate.of(2026, 9, 1), parse("thing sept 1st").date)
        assertEquals(LocalDate.of(2026, 12, 21), parse("thing 21st dec").date)
    }

    // ---- which list it goes to ----

    private val lists = listOf("Groceries", "Work", "Work trips", "Getting started")

    private fun toList(s: String) = CaptureParse.parse(s, today, lists)

    @Test
    fun `a named list is understood and taken out of the title`() {
        val c = toList("buy milk ~ Groceries")
        assertEquals("buy milk", c.title)
        assertEquals("Groceries", c.list)
        assertTrue("an existing list was reported as new", !c.listIsNew)
    }

    @Test
    fun `the mark works with or without a space, and ignores case`() {
        listOf("buy milk ~Groceries", "buy milk ~ groceries", "buy milk ~  GROCERIES").forEach {
            assertEquals("failed on: $it", "Groceries", toList(it).list)
        }
    }

    @Test
    fun `spacing inside the name does not have to match`() {
        // The point of the shortcut is typing it mid-sentence one-handed, and a phone keyboard is
        // not reliable about either spaces or capitals.
        listOf("book flights ~ Work trips", "book flights ~worktrips", "book flights ~ WorkTrips")
            .forEach {
                assertEquals("failed on: $it", "Work trips", toList(it).list)
                assertEquals("failed on: $it", "book flights", toList(it).title)
                assertTrue("failed on: $it", !toList(it).listIsNew)
            }
    }

    @Test
    fun `the longest matching name wins`() {
        // "Work" is a prefix of "Work trips" and must not claim a line that named the longer one.
        assertEquals("Work trips", toList("thing ~ Work trips").list)
        assertEquals("Work", toList("thing ~ Work").list)
    }

    @Test
    fun `a name only matches whole`() {
        // "Work" must not claim "Workshop" — that is a list this workspace does not have, and
        // filing into the wrong existing list is worse than making the right new one.
        val c = toList("build a bench ~ Workshop")
        assertEquals("Workshop", c.list)
        assertTrue("Workshop was matched onto Work", c.listIsNew)
    }

    @Test
    fun `a name the workspace does not have becomes a new list`() {
        // This reverses the original rule. Leaving an unknown name in the title meant the only way
        // to file into a new list was to stop and make it first — the interruption capture exists
        // to remove. The safeguard is that nothing is silent: the name is tinted as it is typed.
        val c = toList("buy milk ~ Camping")
        assertEquals("buy milk", c.title)
        assertEquals("Camping", c.list)
        assertTrue(c.listIsNew)
    }

    @Test
    fun `a new list name may have spaces in it`() {
        // An existing name is matched against the ones that exist, so its extent is known. A new one
        // has no such help: it runs to the end of the line, because that is the only bound that does
        // not need to guess.
        val c = toList("plan the trip ~ Summer holiday")
        assertEquals("plan the trip", c.title)
        assertEquals("Summer holiday", c.list)
        assertTrue(c.listIsNew)
    }

    @Test
    fun `a new list stops where another token starts`() {
        // The reason the new-list name is resolved last: everything else has already staked its
        // claim, so "tomorrow" is still a date and the list is only what is left.
        val c = toList("pack ~ Camping tomorrow #gear !high")
        assertEquals("pack", c.title)
        assertEquals("Camping", c.list)
        assertTrue(c.listIsNew)
        assertEquals(today.plusDays(1), c.date)
        assertEquals(listOf("gear"), c.labels)
        assertEquals("High", c.priority)
    }

    @Test
    fun `a tilde inside a word is not a mark`() {
        val c = toList("rename foo~bar")
        assertEquals("rename foo~bar", c.title)
        assertNull(c.list)
    }

    @Test
    fun `a tilde before a number is an approximation, not a list`() {
        // "~5 mins" is how people write "about five minutes", and it must not quietly become a list
        // called "5 mins" — the one shape where the mark is far more likely to be ordinary text.
        val c = toList("call the plumber ~5 mins")
        assertEquals("call the plumber ~5 mins", c.title)
        assertNull(c.list)
    }

    @Test
    fun `a mark with nothing after it is not a list`() {
        val c = toList("ship it ~")
        assertEquals("ship it ~", c.title)
        assertNull(c.list)
    }

    @Test
    fun `a list combines with everything else`() {
        val c = toList("buy milk ~ Groceries tomorrow #home !high")
        assertEquals("buy milk", c.title)
        assertEquals("Groceries", c.list)
        assertEquals(today.plusDays(1), c.date)
        assertEquals(listOf("home"), c.labels)
        assertEquals("High", c.priority)
    }

    @Test
    fun `the whole line being a list name leaves it as a title`() {
        // The same rule as every other token: naming a destination and nothing else is a task
        // called that, not an empty task filed somewhere.
        val c = toList("~ Groceries")
        assertEquals("~ Groceries", c.title)
    }

    @Test
    fun `with no lists known every name is a new one`() {
        val c = parse("buy milk ~ Groceries")
        assertEquals("buy milk", c.title)
        assertEquals("Groceries", c.list)
        assertTrue(c.listIsNew)
    }

    // ---- offering the lists that match what is being typed ----

    @Test
    fun `the draft is the last mark on the line and what follows it`() {
        val (span, draft) = CaptureParse.listDraft("buy milk ~ Groc")!!
        assertEquals("Groc", draft)
        assertEquals("~ Groc", "buy milk ~ Groc".substring(span.first, span.last + 1))
    }

    @Test
    fun `a bare mark drafts nothing, which is when every list is worth offering`() {
        assertEquals("", CaptureParse.listDraft("buy milk ~")!!.second)
        assertEquals(lists, CaptureParse.listSuggestions("", lists))
    }

    @Test
    fun `suggestions match on the same rule the parser matches on`() {
        // Whatever the field offers has to be something the line would actually resolve to, so both
        // ignore case and spacing.
        assertEquals(listOf("Work", "Work trips"), CaptureParse.listSuggestions("wor", lists))
        assertEquals(listOf("Work trips"), CaptureParse.listSuggestions("worktr", lists))
        assertEquals(listOf("Groceries"), CaptureParse.listSuggestions("GROC", lists))
    }

    @Test
    fun `a name that starts with the draft comes before one that merely contains it`() {
        val out = CaptureParse.listSuggestions("started", listOf("Getting started", "Started work"))
        assertEquals(listOf("Started work", "Getting started"), out)
    }

    @Test
    fun `nothing matching is how the field knows to offer a new list`() {
        assertEquals(emptyList<String>(), CaptureParse.listSuggestions("Camping", lists))
    }

    @Test
    fun `no mark means no draft`() {
        assertNull(CaptureParse.listDraft("buy milk tomorrow"))
    }
}
