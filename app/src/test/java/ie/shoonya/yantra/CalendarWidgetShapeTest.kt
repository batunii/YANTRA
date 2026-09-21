package ie.shoonya.yantra

import ie.shoonya.yantra.ui.calendar.DayItem
import ie.shoonya.yantra.widget.CalendarWidgetShape
import ie.shoonya.yantra.widget.CalendarWidgetView
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the calendar widget asks for, and what it prints on a line.
 *
 * The edges are where a widget goes wrong quietly: a three-day view that starts on the 30th, a
 * month grid that has to reach into both neighbours, a meeting that began yesterday and has no
 * honest start time on today's column. Nobody is looking at a home screen when it is wrong.
 */
class CalendarWidgetShapeTest {

    private val en = Locale.UK
    private val sept18 = LocalDate.of(2026, 9, 18)

    // ---- the window a view asks the database for ----

    @Test
    fun `a month asks for its whole grid, not its own days`() {
        val (from, to) = CalendarWidgetShape.window(CalendarWidgetView.MONTH, sept18)
        // September 2026 begins on a Tuesday, so the grid opens on Monday 31 August.
        assertEquals(LocalDate.of(2026, 8, 31), from)
        assertEquals(42, java.time.temporal.ChronoUnit.DAYS.between(from, to))
    }

    @Test
    fun `three days draws three and reads eighteen`() {
        val (from, to) = CalendarWidgetShape.window(CalendarWidgetView.THREE_DAY, LocalDate.of(2026, 9, 30))
        assertEquals(LocalDate.of(2026, 9, 30), from)
        // Three on screen, and the fortnight past them that an empty span points at.
        assertEquals(18L, java.time.temporal.ChronoUnit.DAYS.between(from, to))
    }

    // ---- paging steps by what you are looking at ----

    @Test
    fun `each view steps by its own unit`() {
        assertEquals(LocalDate.of(2026, 10, 18), CalendarWidgetView.MONTH.step(sept18, 1))
        assertEquals(LocalDate.of(2026, 9, 21), CalendarWidgetView.THREE_DAY.step(sept18, 1))
        assertEquals(LocalDate.of(2026, 9, 17), CalendarWidgetView.DAY.step(sept18, -1))
    }

    @Test
    fun `a stored view survives being reordered, and an unknown one is not a crash`() {
        CalendarWidgetView.entries.forEach { assertEquals(it, CalendarWidgetView.of(it.store)) }
        // A widget placed by a newer build, or a corrupt preference: it opens on the day.
        assertEquals(CalendarWidgetView.DAY, CalendarWidgetView.of("something else"))
        assertEquals(CalendarWidgetView.DAY, CalendarWidgetView.of(null))
    }

    // ---- headings ----

    /** Whatever this JVM's CLDR calls a month — asserting "Sep" or "Sept" is asserting a JDK. */
    private fun short(month: Int) =
        java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, en)

    private fun full(month: Int) =
        java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, en)

    /**
     * The masthead is two lines in every view — the container, then the position inside it — and
     * the two halves must not both carry the same fact.
     */
    @Test
    fun `the eyebrow names the container and the hero names the position`() {
        val other = LocalDate.of(2026, 9, 11)

        // A day: the weekday over the date, and TODAY when it is.
        assertEquals("TODAY", CalendarWidgetShape.eyebrow(CalendarWidgetView.DAY, sept18, sept18, en))
        assertEquals("FRIDAY", CalendarWidgetShape.eyebrow(CalendarWidgetView.DAY, sept18, other, en))
        assertEquals("18 ${full(9)}", CalendarWidgetShape.heading(CalendarWidgetView.DAY, sept18, sept18, en))

        // A month: the year over the month. The year is the one constant that earns its line.
        assertEquals("2026", CalendarWidgetShape.eyebrow(CalendarWidgetView.MONTH, sept18, sept18, en))
        assertEquals(full(9), CalendarWidgetShape.heading(CalendarWidgetView.MONTH, sept18, sept18, en))
    }

    @Test
    fun `the year appears only when it is not the one you are living in`() {
        val nextYear = LocalDate.of(2027, 9, 18)
        assertEquals(
            "18 ${full(9)}",
            CalendarWidgetShape.heading(CalendarWidgetView.DAY, sept18, sept18, en),
        )
        assertEquals(
            "18 ${full(9)} 2027",
            CalendarWidgetShape.heading(CalendarWidgetView.DAY, nextYear, sept18, en),
        )
    }

    @Test
    fun `a three-day span names the month once, unless it crosses one`() {
        // The month is in the eyebrow, so the hero is bare numbers…
        assertEquals(full(9).uppercase(en), CalendarWidgetShape.eyebrow(CalendarWidgetView.THREE_DAY, sept18, sept18, en))
        assertEquals("18 – 20", CalendarWidgetShape.heading(CalendarWidgetView.THREE_DAY, sept18, sept18, en))

        // …until the span crosses a boundary, which is the one case where repeating the month is
        // the only way to be unambiguous.
        val spanning = LocalDate.of(2026, 9, 30)
        assertEquals(
            "${full(9).uppercase(en)} – ${short(10).uppercase(en)}",
            CalendarWidgetShape.eyebrow(CalendarWidgetView.THREE_DAY, spanning, sept18, en),
        )
        assertEquals(
            "30 ${full(9)} – 2 ${full(10)}",
            CalendarWidgetShape.heading(CalendarWidgetView.THREE_DAY, spanning, sept18, en),
        )
    }

    // ---- when the quiet ends ----

    @Test
    fun `an empty day is told what comes after it`() {
        val days = mapOf(
            LocalDate.of(2026, 9, 22) to listOf(
                event(LocalDateTime.of(2026, 9, 22, 9, 0), title = "Dentist")
            )
        )
        val next = CalendarWidgetShape.nextUp(days, after = sept18, locale = en)
        assertEquals(LocalDate.of(2026, 9, 22), next?.date)
        assertEquals("Dentist", next?.title)
        // Strictly after the days on screen: a thing on the day itself is not "next".
        assertNull(CalendarWidgetShape.nextUp(days, after = LocalDate.of(2026, 9, 22), locale = en))
    }

    @Test
    fun `the agenda views read past their own days to find it`() {
        val (from, to) = CalendarWidgetShape.window(CalendarWidgetView.DAY, sept18)
        assertEquals(sept18, from)
        // One day drawn, a fortnight read — so "nothing in the next two weeks" is a true sentence
        // and not a guess.
        assertEquals(16L, java.time.temporal.ChronoUnit.DAYS.between(from, to))
    }

    // ---- what a line says ----

    private fun event(
        start: LocalDateTime,
        end: LocalDateTime = start.plusHours(1),
        allDay: Boolean = false,
        title: String = "A thing",
    ) = DayItem.Event(
        nodeId = "e1",
        title = title,
        start = start,
        end = end,
        allDay = allDay,
        location = null,
        repeating = false,
        cancelled = false,
        sortKey = 0,
    )

    @Test
    fun `something that began yesterday prints no start time on today`() {
        val overnight = event(
            start = LocalDateTime.of(2026, 9, 17, 22, 0),
            end = LocalDateTime.of(2026, 9, 18, 6, 0),
        )
        assertEquals("22:00", CalendarWidgetShape.rows(listOf(overnight), LocalDate.of(2026, 9, 17), en)[0].time)
        // The honest answer on the second day is nothing: printing 22:00 at the top of a morning
        // says the thing starts then, and it does not.
        assertNull(CalendarWidgetShape.rows(listOf(overnight), sept18, en)[0].time)
    }

    @Test
    fun `an all-day thing has no hour`() {
        val row = CalendarWidgetShape.rows(
            listOf(event(LocalDateTime.of(2026, 9, 18, 0, 0), allDay = true)),
            sept18,
            en,
        )[0]
        assertNull(row.time)
        assertTrue(row.allDay)
    }

    @Test
    fun `a task owed by the end of the day is not an all-day event`() {
        val task = DayItem.Task(
            nodeId = "t1",
            title = "Owed",
            at = LocalDateTime.of(2026, 9, 18, 0, 0),
            hasTime = false,
            done = false,
            sortKey = 0,
        )
        val row = CalendarWidgetShape.rows(listOf(task), sept18, en)[0]
        assertNull("no time to print", row.time)
        assertTrue(row.isTask)
        // The distinction the day view makes too: an all-day event occupies the day, a due task is
        // owed by the end of it, and the widget must not file the second under the first.
        assertTrue("a due task is not an all-day band entry", !row.allDay)
    }

    @Test
    fun `a cancelled event is not counted under a date`() {
        val days = mapOf(
            sept18 to listOf(
                event(LocalDateTime.of(2026, 9, 18, 9, 0)),
                event(LocalDateTime.of(2026, 9, 18, 11, 0)).copy(cancelled = true),
            )
        )
        val cell = CalendarWidgetShape.cells(sept18, sept18, days).first { it.date == sept18 }
        // A mark under a numeral cannot be struck through, so a cancelled thing would read as a
        // thing that is happening.
        assertEquals(1, cell.count)
    }

    @Test
    fun `the month grid marks today, the selection and the neighbours`() {
        val cells = CalendarWidgetShape.cells(
            anchor = sept18,
            today = sept18,
            days = emptyMap(),
        )
        assertEquals(42, cells.size)
        assertEquals(1, cells.count { it.isToday })
        assertEquals(30, cells.count { it.inMonth })
        assertTrue("the grid opens before the 1st", !cells.first().inMonth)
    }

    @Test
    fun `a column counts what would not fit rather than dropping it`() {
        val many = (9..14).map { event(LocalDateTime.of(2026, 9, 18, it, 0), title = "at $it") }
        val column = CalendarWidgetShape.columns(
            CalendarWidgetView.DAY, sept18, sept18, mapOf(sept18 to many), en, limit = 4,
        ).single()
        assertEquals(4, column.rows.size)
        assertEquals(2, column.more)
    }

    @Test
    fun `a month asks for no columns at all`() {
        // The grid is the whole body; there is no agenda under it to fill.
        assertEquals(
            emptyList<Any>(),
            CalendarWidgetShape.columns(
                CalendarWidgetView.MONTH, sept18, sept18, emptyMap(), en, limit = 4,
            ),
        )
    }

    /**
     * The hour a row is at, as a number.
     *
     * The renderer decides which line is "next" from this rather than from [WidgetAgendaRow.time],
     * which is formatted for a reader: in a twelve-hour locale `2:00p` parsed back as an hour is
     * two in the morning, and the mark landed on the wrong line for half of every day.
     */
    @Test
    fun `a row carries its hour as a number, whatever the clock prints`() {
        val afternoon = CalendarWidgetShape.rows(
            listOf(event(LocalDateTime.of(2026, 9, 18, 14, 30))), sept18, Locale.US,
        )[0]
        assertEquals(14 * 60 + 30, afternoon.startMin)
        // …and the printed form is genuinely the ambiguous one, which is the point.
        assertEquals("2:30p", afternoon.time)

        val allDay = CalendarWidgetShape.rows(
            listOf(event(LocalDateTime.of(2026, 9, 18, 0, 0), allDay = true)), sept18, en,
        )[0]
        assertNull(allDay.startMin)
    }
}
