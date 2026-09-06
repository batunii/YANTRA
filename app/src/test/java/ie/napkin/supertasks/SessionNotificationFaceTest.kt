package ie.napkin.supertasks

import ie.napkin.supertasks.domain.FocusTimer
import ie.napkin.supertasks.domain.SessionNotification
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which way the session's clock runs, on the one surface that used to get it wrong.
 *
 * The notification drew every session as a count-up from its start. For an open stopwatch that is
 * correct and for a committed session it is the opposite of the point: a 25-minute promise reported
 * how long you had been at it rather than how long was left, and disagreed with the widget beside it
 * about the same session.
 */
class SessionNotificationFaceTest {

    private fun state(
        planned: Int,
        remaining: Int,
        elapsed: Int,
        running: Boolean = true,
    ) = FocusTimer.State(
        sessionId = "s", nodeId = "n", nodeTitle = "Write the thing",
        plannedSecs = planned, remainingSecs = remaining, elapsedSecs = elapsed,
        isRunning = running,
    )

    @Test
    fun `a committed session counts down to its promise`() {
        val f = SessionNotification.face(state(planned = 1500, remaining = 900, elapsed = 600))
        assertEquals(SessionNotification.Face.Countdown(900), f)
    }

    @Test
    fun `an open session counts up from its start`() {
        val f = SessionNotification.face(state(planned = 0, remaining = 0, elapsed = 600))
        assertEquals(SessionNotification.Face.CountUp(600), f)
    }

    /**
     * The bug this pair exists to pin. Both sessions are ten minutes old; only one of them has
     * anything to count down to, and reading the same number off both is how the old builder
     * turned a promise into a stopwatch.
     */
    @Test
    fun `the two instruments do not report the same number`() {
        val committed = SessionNotification.face(state(planned = 1500, remaining = 900, elapsed = 600))
        val open = SessionNotification.face(state(planned = 0, remaining = 0, elapsed = 600))
        assertEquals(SessionNotification.Face.Countdown(900), committed)
        assertEquals(SessionNotification.Face.CountUp(600), open)
    }

    @Test
    fun `a paused session shows a frozen number and no clock`() {
        val committed = SessionNotification.face(
            state(planned = 1500, remaining = 900, elapsed = 600, running = false)
        )
        assertEquals(SessionNotification.Face.Frozen("Paused · 15:00 left"), committed)

        // A stopwatch freezes what it was counting, which is the other number entirely.
        val open = SessionNotification.face(
            state(planned = 0, remaining = 0, elapsed = 600, running = false)
        )
        assertEquals(SessionNotification.Face.Frozen("Paused · 10:00"), open)
    }

    @Test
    fun `the clock grows an hours field only once there is one`() {
        assertEquals("0:07", SessionNotification.clock(7))
        assertEquals("9:05", SessionNotification.clock(545))
        assertEquals("59:59", SessionNotification.clock(3599))
        assertEquals("1:00:00", SessionNotification.clock(3600))
        assertEquals("2:03:04", SessionNotification.clock(7384))
    }

    /** A restore that overshoots the end must not render a negative clock. */
    @Test
    fun `a negative reading floors at zero`() {
        assertEquals("0:00", SessionNotification.clock(-30))
    }
}
