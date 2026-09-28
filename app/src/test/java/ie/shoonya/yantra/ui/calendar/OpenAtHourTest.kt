package ie.shoonya.yantra.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Where a day or three-day timeline opens.
 *
 * It opened at seven whatever the time was, so a calendar checked at four in the afternoon began
 * with nine hours of morning and the answer scrolled off the bottom — every single look at the day
 * started with a scroll to where you already were.
 *
 * The rule has three parts and each is a thing that can be got wrong silently, because being
 * scrolled to the wrong hour reads as the calendar being empty rather than as a bug: today opens at
 * now, any other day opens at seven, and the small hours do not scroll to a negative offset.
 */
class OpenAtHourTest {

    private val today = LocalDate.of(2026, 9, 21)
    private fun at(hour: Int, minute: Int = 0) = today.atTime(hour, minute)

    @Test
    fun `today opens an hour before now`() {
        assertEquals(13.5f, openAtHour(listOf(today), at(14, 30)), 0.001f)
    }

    /** A fraction, not a whole hour, so the line lands in the same place whatever the minute. */
    @Test
    fun `the minutes are kept`() {
        assertEquals(8.25f, openAtHour(listOf(today), at(9, 15)), 0.001f)
    }

    /**
     * The small hours do not scroll above midnight.
     *
     * `scrollTo` would clamp a negative, but relying on that means the rule is only correct by
     * accident of the caller — and this function is the one that claims to know where to open.
     */
    @Test
    fun `before one in the morning opens at midnight rather than yesterday`() {
        assertEquals(0f, openAtHour(listOf(today), at(0, 20)), 0.001f)
        assertEquals(0f, openAtHour(listOf(today), at(0, 0)), 0.001f)
    }

    @Test
    fun `another day opens on the working morning`() {
        assertEquals(7f, openAtHour(listOf(today.plusDays(1)), at(14, 30)), 0.001f)
        assertEquals(7f, openAtHour(listOf(today.minusDays(3)), at(14, 30)), 0.001f)
    }

    /**
     * Three days share one scroll, so the question is whether *any* of them is today.
     *
     * Asking whether the first one is would leave the three-day view opening at seven for two days
     * out of three — including the one where today is the middle column, which is where it sits
     * most of the time.
     */
    @Test
    fun `a three-day view containing today opens at now`() {
        val week = listOf(today.minusDays(1), today, today.plusDays(1))
        assertEquals(13.5f, openAtHour(week, at(14, 30)), 0.001f)
    }

    @Test
    fun `a three-day view with no today in it opens on the working morning`() {
        val week = listOf(today.plusDays(3), today.plusDays(4), today.plusDays(5))
        assertEquals(7f, openAtHour(week, at(14, 30)), 0.001f)
    }

    @Test
    fun `late in the evening still opens at now rather than being clamped to seven`() {
        assertEquals(22.5f, openAtHour(listOf(today), at(23, 30)), 0.001f)
    }
}
