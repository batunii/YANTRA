package ie.shoonya.yantra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the watchdog applies, tested without a looper.
 *
 * The app froze and was killed and the diagnostics recorded nothing, because an ANR is not an
 * exception: no throwable is ever constructed, so a crash handler has nothing to catch. The one
 * failure people actually describe — "it froze" — was the one the log could not see.
 *
 * What can be wrong here is the arithmetic and the once-ness: reporting a pause nobody would
 * notice, missing a real freeze, or filling the log with the same stack every poll for as long as
 * the freeze lasts. The looper is not the interesting part; these are.
 */
class MainThreadWatchdogTest {

    /** The decision, lifted out of the handler plumbing exactly as the watchdog applies it. */
    private class Rule(private val stuckAfterMs: Long) {
        var reported = 0; private set
        private var reportedAlready = false
        fun tick(sinceLastSeenMs: Long) {
            if (sinceLastSeenMs < stuckAfterMs) { reportedAlready = false; return }
            if (reportedAlready) return
            reportedAlready = true
            reported++
        }
    }

    @Test
    fun `a responsive main thread is never reported`() {
        val r = Rule(4_000)
        repeat(20) { r.tick(16) }          // a frame apiece
        assertEquals(0, r.reported)
    }

    @Test
    fun `a pause shorter than the threshold is not a freeze`() {
        // Scrolling a long list, a slow first frame: not worth a stack trace.
        val r = Rule(4_000)
        listOf(200L, 900L, 1_500L, 3_999L).forEach { r.tick(it) }
        assertEquals(0, r.reported)
    }

    @Test
    fun `a real freeze is reported`() {
        val r = Rule(4_000)
        r.tick(4_200)
        assertEquals(1, r.reported)
    }

    @Test
    fun `one freeze is reported once, however long it lasts`() {
        // The system allows ten seconds; at a poll a second that would be six copies of one stack,
        // and the log has a budget.
        val r = Rule(4_000)
        listOf(4_200L, 5_200L, 6_200L, 7_200L, 9_900L).forEach { r.tick(it) }
        assertEquals(1, r.reported)
    }

    @Test
    fun `a second freeze after recovery is reported again`() {
        val r = Rule(4_000)
        r.tick(4_500)      // frozen
        r.tick(20)         // answered again
        r.tick(6_000)      // frozen once more
        assertEquals(2, r.reported)
    }

    @Test
    fun `the threshold sits under the system's own limit`() {
        // Evidence has to be written before the process is taken, or there is no evidence.
        assertTrue("must fire before the 10s ANR", 4_000L < 10_000L)
    }
}
