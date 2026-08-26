package ie.napkin.supertasks

import ie.napkin.supertasks.data.db.FocusOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "kept its promise" means once a session can be open-ended.
 *
 * The old `completed` boolean could only ask whether a countdown ran out, which is not a question a
 * stopwatch has an answer to — and answering "no" for every stopwatch would have made the history
 * read as a long list of failures.
 */
class FocusOutcomeTest {

    @Test
    fun `a countdown that ran out kept its promise`() {
        assertTrue(FocusOutcome.keptItsPromise(FocusOutcome.RAN_OUT, plannedSecs = 1500))
    }

    @Test
    fun `a countdown stopped early did not`() {
        assertFalse(FocusOutcome.keptItsPromise(FocusOutcome.STOPPED, plannedSecs = 1500))
        assertFalse(FocusOutcome.keptItsPromise(FocusOutcome.INTERRUPTED, plannedSecs = 1500))
    }

    @Test
    fun `an open session never kept a promise, because it never made one`() {
        // The distinction the boolean could not draw. A stopwatch that ran for an hour gave an hour;
        // it simply did not promise anything first, and must not be shown as having fallen short.
        FocusOutcome.run {
            listOf(RAN_OUT, STOPPED, INTERRUPTED, LOST).forEach { outcome ->
                assertFalse("open session claimed a promise via $outcome", keptItsPromise(outcome, plannedSecs = 0))
            }
        }
    }

    @Test
    fun `an unknown outcome from a newer build is not a kept promise`() {
        // The log is append-only and older builds must keep reading it, so an outcome this build has
        // never heard of has to degrade to "some session happened" rather than throw.
        assertFalse(FocusOutcome.keptItsPromise("some_future_thing", plannedSecs = 1500))
    }
}
