package ie.napkin.supertasks

import ie.napkin.supertasks.ui.focus.DayStat
import ie.napkin.supertasks.ui.focus.Stats
import ie.napkin.supertasks.ui.focus.StrataMark
import ie.napkin.supertasks.ui.focus.WeekReview
import ie.napkin.supertasks.ui.focus.strataMarks
import ie.napkin.supertasks.ui.focus.weekReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The diagram at the top of the focus stats page, and the promise that it draws the same week as
 * the cards and the bars printed under it.
 *
 * The arrangement is the part worth pinning down. The glyph's own grammar is "a trikona opens a
 * day, then one ring per session that day", and this uses it to say something narrower: every
 * trikona is a day you focused, every ring is a session *today*. Get the order wrong and the rings
 * land inside a day they do not belong to, which the glyph would draw quite happily.
 */
class WeekReviewTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 4)

    /** The seven days the stats page builds, oldest first, with [counts] sessions on each. */
    private fun stats(counts: List<Int>): Stats {
        require(counts.size == 7)
        return Stats(
            todayCount = counts.last(),
            days = counts.mapIndexed { i, n ->
                DayStat(today.minusDays((6 - i).toLong()), n, n * 1500)
            },
        )
    }

    @Test
    fun `active days are the days with something on them, not the days in the window`() {
        val r = weekReview(stats(listOf(0, 2, 0, 0, 1, 0, 3)))
        assertEquals(3, r.activeDays)
        assertEquals(3, r.todayCount)
    }

    @Test
    fun `today's count comes from the page's own today`() {
        // It agrees with the last bar by construction, and that is the point: one number, read once.
        val s = stats(listOf(0, 0, 0, 0, 0, 0, 4))
        assertEquals(s.todayCount, weekReview(s).todayCount)
        assertEquals(s.days.last().completed, weekReview(s).todayCount)
    }

    @Test
    fun `the glyph draws one trikona per active day and one ring per session today`() {
        // Two days worked, three sessions today: two trikonas, three rings. The eleven sessions on
        // the other day are deliberately not drawn — see WeekReview.strataDayCounts.
        val r = weekReview(stats(listOf(0, 0, 11, 0, 0, 0, 3)))
        assertEquals(2, r.activeDays)
        val marks = strataMarks(r.strataDayCounts)
        assertEquals(2, marks.count { it is StrataMark.DayOpen })
        assertEquals(3, marks.count { it == StrataMark.Session })
    }

    @Test
    fun `the trikonas come first and today's rings come last`() {
        // The strata read outward from the centre, so a ring drawn before the final trikona would
        // sit inside a day it does not belong to.
        val r = weekReview(stats(listOf(1, 0, 1, 0, 0, 1, 2)))
        assertEquals(listOf(0, 0, 0, 2), r.strataDayCounts)
        val marks = strataMarks(r.strataDayCounts)
        assertEquals(4, marks.count { it is StrataMark.DayOpen })
        assertTrue(marks.take(4).all { it is StrataMark.DayOpen })
        assertTrue(marks.drop(4).all { it == StrataMark.Session })
    }

    @Test
    fun `a week worked with nothing done today draws its days and no rings`() {
        val r = weekReview(stats(listOf(0, 3, 0, 2, 0, 1, 0)))
        assertEquals(3, r.activeDays)
        assertEquals(0, r.todayCount)
        assertEquals(listOf(0, 0, 0), r.strataDayCounts)
        assertEquals(0, strataMarks(r.strataDayCounts).count { it == StrataMark.Session })
    }

    @Test
    fun `today alone is one trikona and its own rings`() {
        val r = weekReview(stats(listOf(0, 0, 0, 0, 0, 0, 3)))
        assertEquals(listOf(3), r.strataDayCounts)
        val marks = strataMarks(r.strataDayCounts)
        assertEquals(1, marks.count { it is StrataMark.DayOpen })
        assertEquals(3, marks.count { it == StrataMark.Session })
    }

    @Test
    fun `a full week is seven trikonas and no more`() {
        // The trikonas are bounded by the window, which is the whole reason this arrangement was
        // chosen over drawing every session in the week.
        val r = weekReview(stats(listOf(1, 1, 1, 1, 1, 1, 1)))
        assertEquals(7, r.activeDays)
        assertEquals(7, strataMarks(r.strataDayCounts).count { it is StrataMark.DayOpen })
    }

    @Test
    fun `an empty week draws nothing at all`() {
        val r = weekReview(stats(listOf(0, 0, 0, 0, 0, 0, 0)))
        assertTrue(r.isEmpty)
        assertEquals(emptyList<Int>(), r.strataDayCounts)
        assertEquals(emptyList<StrataMark>(), strataMarks(r.strataDayCounts))
        // And the default, which is the frame before the first flow emission.
        assertTrue(WeekReview().isEmpty)
        assertEquals(emptyList<Int>(), WeekReview().strataDayCounts)
    }
}
