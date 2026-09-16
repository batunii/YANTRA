package ie.shoonya.yantra

import androidx.compose.ui.unit.dp
import ie.shoonya.yantra.ui.calendar.DEFAULT_HOUR_HEIGHT
import ie.shoonya.yantra.ui.calendar.MAX_HOUR_HEIGHT
import ie.shoonya.yantra.ui.calendar.MIN_HOUR_HEIGHT
import ie.shoonya.yantra.ui.calendar.clampedHourHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far a pinch may go — CALENDAR_PLAN.md §17.
 *
 * A pinch is an enthusiastic gesture: a two-finger spread can multiply by ten in a few frames, and
 * without a clamp that is a timeline of one hour or of ninety seconds. Both ends have a reason, and
 * the reasons are what these check rather than the numbers.
 */
class ZoomTest {

    @Test
    fun `an enthusiastic pinch cannot make a day unreadable`() {
        assertEquals(MAX_HOUR_HEIGHT, (10_000.dp).clampedHourHeight())
        assertEquals(MIN_HOUR_HEIGHT, (0.dp).clampedHourHeight())
        assertEquals(MIN_HOUR_HEIGHT, ((-40).dp).clampedHourHeight())
    }

    @Test
    fun `anything inside the range is left alone`() {
        assertEquals(DEFAULT_HOUR_HEIGHT, DEFAULT_HOUR_HEIGHT.clampedHourHeight())
        assertEquals(40.dp, (40.dp).clampedHourHeight())
    }

    @Test
    fun `the floor still fits a working day on a phone`() {
        // Twelve hours is a working day with its edges. A phone gives the timeline roughly 380dp
        // once the header, the rail and the system bars have had theirs.
        assertTrue(
            "twelve hours at the floor is ${MIN_HOUR_HEIGHT * 12}",
            MIN_HOUR_HEIGHT * 12 <= 380.dp,
        )
    }

    @Test
    fun `the ceiling makes a quarter of an hour a real target`() {
        // A 15-minute block is a quarter of the hour height, and a touch target wants ~40dp.
        assertTrue("a quarter hour is ${MAX_HOUR_HEIGHT / 4}", MAX_HOUR_HEIGHT / 4 >= 40.dp)
    }

    @Test
    fun `the default sits inside its own range`() {
        assertTrue(DEFAULT_HOUR_HEIGHT in MIN_HOUR_HEIGHT..MAX_HOUR_HEIGHT)
    }
}
