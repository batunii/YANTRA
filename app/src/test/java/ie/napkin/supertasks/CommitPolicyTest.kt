package ie.napkin.supertasks

import ie.napkin.supertasks.data.sync.Change
import ie.napkin.supertasks.data.sync.CommitPolicy
import ie.napkin.supertasks.data.sync.CommitPolicy.EDIT_MAX_AGE_MS
import ie.napkin.supertasks.data.sync.CommitPolicy.EDIT_QUIET_MS
import ie.napkin.supertasks.data.sync.CommitPolicy.INK_MAX_AGE_MS
import ie.napkin.supertasks.data.sync.CommitPolicy.INK_QUIET_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cadence, tested as arithmetic rather than by waiting.
 *
 * This is the part of sync with judgement in it and the part most likely to be argued about later,
 * so it is worth being able to change a number and see exactly what moves — without a test that
 * sleeps for fifteen minutes to find out.
 */
class CommitPolicyTest {

    private val t0 = 1_000_000L

    private fun pending(vararg changes: Pair<Change, Long>) =
        changes.fold(CommitPolicy.Pending()) { p, (c, at) -> p.plus(c, at) }

    @Test
    fun `nothing pending never asks for a commit`() {
        assertNull(CommitPolicy.reasonToFlush(CommitPolicy.Pending(), t0))
        assertNull(CommitPolicy.nextCheckDelay(CommitPolicy.Pending(), t0))
    }

    @Test
    fun `a single edit waits`() {
        val p = pending(Change.EDIT to t0)
        assertNull(CommitPolicy.reasonToFlush(p, t0 + 1))
        assertNull("committed while still typing", CommitPolicy.reasonToFlush(p, t0 + EDIT_QUIET_MS - 1))
    }

    @Test
    fun `edits commit once you stop`() {
        val p = pending(Change.EDIT to t0, Change.EDIT to t0 + 100)
        val reason = CommitPolicy.reasonToFlush(p, t0 + 100 + EDIT_QUIET_MS)
        assertNotNull(reason)
        assertTrue("the message should say how many: $reason", reason!!.contains("2 edits"))
    }

    @Test
    fun `continuous typing still commits eventually`() {
        // Every keystroke pushes the quiet deadline out, so without a ceiling a long writing
        // session would never reach the repo at all.
        // Derived from the ceiling rather than guessed, so changing the constant cannot silently
        // turn this into a test that proves nothing.
        val step = 1_000L
        var p = CommitPolicy.Pending()
        var t = t0
        repeat((EDIT_MAX_AGE_MS / step).toInt() + 1) { p = p.plus(Change.EDIT, t); t += step }
        assertTrue(t - p.oldestAt >= EDIT_MAX_AGE_MS)
        assertNotNull("a long session never committed", CommitPolicy.reasonToFlush(p, t))
    }

    @Test
    fun `a structural change is never left to a timer`() {
        // Handled by the scheduler at record time rather than here, so the policy is asked about a
        // batch that only contains one — and it must not claim it is ready before its time.
        val p = pending(Change.STRUCTURAL to t0)
        assertTrue("a structural change alone should not count as pending work", p.isEmpty)
    }

    @Test
    fun `ink settles faster than typing does`() {
        // Strokes arrive in bursts and each is large; waiting a full editing pause would leave a
        // finished drawing uncommitted for far longer than it needs to be.
        val p = pending(Change.INK to t0)
        assertNull(CommitPolicy.reasonToFlush(p, t0 + INK_QUIET_MS - 1))
        val reason = CommitPolicy.reasonToFlush(p, t0 + INK_QUIET_MS)
        assertNotNull(reason)
        assertTrue(reason!!.contains("drawing"))
        assertTrue("ink should settle sooner than text", INK_QUIET_MS < EDIT_QUIET_MS)
    }

    @Test
    fun `a long drawing session commits before it is forgotten`() {
        val step = 2_000L
        var p = CommitPolicy.Pending()
        var t = t0
        repeat((INK_MAX_AGE_MS / step).toInt() + 1) { p = p.plus(Change.INK, t); t += step }
        assertTrue(t - p.oldestAt >= INK_MAX_AGE_MS)
        assertNotNull(CommitPolicy.reasonToFlush(p, t))
    }

    @Test
    fun `leaving the app flushes whatever is waiting`() {
        // The process can be killed while backgrounded, and work that reached a file but never a
        // commit would sit there until the app happened to be opened again.
        val p = pending(Change.EDIT to t0)
        assertNotNull(CommitPolicy.reasonToFlush(p, t0 + 1, leavingApp = true))
        assertNull("nothing pending is still nothing to do",
            CommitPolicy.reasonToFlush(CommitPolicy.Pending(), t0, leavingApp = true))
    }

    @Test
    fun `the next check is the soonest deadline that has not passed`() {
        val p = pending(Change.EDIT to t0)
        assertEquals(EDIT_QUIET_MS, CommitPolicy.nextCheckDelay(p, t0))
        // Once a deadline is behind us the answer is "now", not a negative wait.
        assertEquals(0L, CommitPolicy.nextCheckDelay(p, t0 + EDIT_MAX_AGE_MS + 1))
    }

    @Test
    fun `mixed ink and edits are decided by whichever settles first`() {
        val p = pending(Change.EDIT to t0, Change.INK to t0)
        val reason = CommitPolicy.reasonToFlush(p, t0 + INK_QUIET_MS)
        assertNotNull("ink was ready and nothing happened", reason)
        assertTrue(reason!!.contains("drawing"))
    }

    @Test
    fun `the oldest timestamp is the first change, not the latest`() {
        // The ceiling is measured from when the batch started; measuring from the last change would
        // mean it never expires while anyone keeps typing.
        val p = pending(Change.EDIT to t0, Change.EDIT to t0 + 5_000, Change.EDIT to t0 + 9_000)
        assertEquals(t0, p.oldestAt)
        assertEquals(t0 + 9_000, p.lastAt)
        assertEquals(3, p.edits)
    }
}
