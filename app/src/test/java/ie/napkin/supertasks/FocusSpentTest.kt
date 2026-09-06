package ie.napkin.supertasks

import ie.napkin.supertasks.domain.FocusTimer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a committed session's moment has passed but nothing has closed it yet.
 *
 * The widgets hand their countdown to the launcher to tick, which is what keeps them live for free —
 * and a launcher counting down has no idea what it is counting to. It goes straight through zero.
 * So every surface that draws a live clock has to be able to ask whether the promise is already
 * spent, because the thing that would normally close the session — the in-process ticker, or the
 * finalize worker — cannot run while the process is dead.
 */
class FocusSpentTest {

    private fun state(planned: Int, remaining: Int, elapsed: Int) = FocusTimer.State(
        sessionId = "s", nodeId = "n", nodeTitle = "Write the thing",
        plannedSecs = planned, remainingSecs = remaining, elapsedSecs = elapsed,
        isRunning = true,
    )

    @Test
    fun `a committed session with time left is not spent`() {
        assertFalse(state(planned = 1500, remaining = 900, elapsed = 600).isSpent)
    }

    @Test
    fun `a committed session at zero is spent`() {
        assertTrue(state(planned = 1500, remaining = 0, elapsed = 1500).isSpent)
    }

    /** The case that produced a widget reading minus four minutes. */
    @Test
    fun `a committed session past zero is spent`() {
        assertTrue(state(planned = 1500, remaining = -240, elapsed = 1740).isSpent)
    }

    /**
     * A stopwatch can never be spent. It counts up, promised nothing, and its remainingSecs is a
     * meaningless zero — reading that as "arrived" would end every open session the instant it began.
     */
    @Test
    fun `an open session is never spent`() {
        assertFalse(state(planned = 0, remaining = 0, elapsed = 0).isSpent)
        assertFalse(state(planned = 0, remaining = 0, elapsed = 99_999).isSpent)
    }
}
