package ie.shoonya.yantra

import ie.shoonya.yantra.data.workspace.awaitPresent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A lookup that misses is asked again before it is believed.
 *
 * The index is rebuilt inside one transaction, and a read landing in that window can see the tables
 * mid-rebuild. So a miss means either "no such row" or "you asked while it was being rewritten",
 * and treating the second as the first is what made moving an event to a list do nothing the first
 * time and work the second — the move gave up silently and wrote nothing anywhere.
 */
class AwaitPresentTest {

    @Test
    fun `a row that is there is returned without waiting at all`() = runBlocking {
        var pauses = 0
        val out = awaitPresent(pause = { pauses++ }) { "row" }
        assertEquals("row", out)
        assertEquals("nothing waits when the answer is already there", 0, pauses)
    }

    @Test
    fun `a row that arrives late is still found`() = runBlocking {
        // The rebuild finishing between two attempts, which is the whole case.
        var calls = 0
        val out = awaitPresent(pause = {}) { if (++calls < 3) null else "row" }
        assertEquals("row", out)
        assertEquals(3, calls)
    }

    @Test
    fun `a row that is genuinely gone gives up rather than hanging`() = runBlocking {
        var calls = 0
        val out = awaitPresent(attempts = 4, pause = {}) { calls++; null }
        assertNull(out)
        assertEquals("tried exactly as often as asked", 4, calls)
    }

    @Test
    fun `it waits between tries, but never after the last one`() = runBlocking {
        var pauses = 0
        awaitPresent(attempts = 3, pause = { pauses++ }) { null }
        assertEquals("three tries, two gaps", 2, pauses)
    }

    @Test
    fun `a single attempt is honoured and never pauses`() = runBlocking {
        var pauses = 0
        val out = awaitPresent(attempts = 1, pause = { pauses++ }) { null }
        assertNull(out)
        assertEquals(0, pauses)
    }
}
