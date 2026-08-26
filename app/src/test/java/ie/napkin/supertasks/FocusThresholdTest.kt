package ie.napkin.supertasks

import ie.napkin.supertasks.data.db.FocusOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the warning and the recorder both read.
 *
 * They used to be two facts — a constant inside the repository and a sentence on a screen — which is
 * exactly the shape that drifts. One says whether a session will be kept; the other asks it.
 */
class FocusThresholdTest {

    @Test
    fun `a session below the threshold is not kept`() {
        assertFalse(FocusOutcome.wouldBeKept(elapsedSecs = 0, plannedSecs = 1500))
        assertFalse(FocusOutcome.wouldBeKept(elapsedSecs = FocusOutcome.MIN_KEPT_SECS - 1, plannedSecs = 1500))
    }

    @Test
    fun `at the threshold it is kept`() {
        assertTrue(FocusOutcome.wouldBeKept(FocusOutcome.MIN_KEPT_SECS, plannedSecs = 1500))
    }

    @Test
    fun `a countdown that reached its target is kept however short it was`() {
        // A deliberate five-second timer that ran out is a promise kept, not a mis-tap. Only a
        // session stopped or interrupted early is ever discarded.
        assertTrue(FocusOutcome.wouldBeKept(elapsedSecs = 5, plannedSecs = 5))
        assertTrue(FocusOutcome.wouldBeKept(elapsedSecs = 6, plannedSecs = 5))
    }

    @Test
    fun `an open session has no target to reach and must earn its place on time alone`() {
        assertFalse(FocusOutcome.wouldBeKept(elapsedSecs = 5, plannedSecs = 0))
        assertTrue(FocusOutcome.wouldBeKept(FocusOutcome.MIN_KEPT_SECS, plannedSecs = 0))
    }

    @Test
    fun `only a mis-tap is uncounted`() {
        assertTrue(FocusOutcome.countsAsTime(FocusOutcome.RAN_OUT))
        assertTrue(FocusOutcome.countsAsTime(FocusOutcome.STOPPED))
        assertTrue(FocusOutcome.countsAsTime(FocusOutcome.INTERRUPTED))
        assertTrue(FocusOutcome.countsAsTime(FocusOutcome.LOST))
        assertFalse(FocusOutcome.countsAsTime(FocusOutcome.DISCARDED))
    }
}
