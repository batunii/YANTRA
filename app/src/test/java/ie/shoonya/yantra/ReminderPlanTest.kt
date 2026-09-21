package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.ReminderRow
import ie.shoonya.yantra.reminders.ReminderManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reminder manager decides to arm and cancel.
 *
 * The whole of the decision is eight lines, and until now none of it had ever been tested, because
 * reaching it meant going through Room, a Flow and AlarmManager. Everything it can get wrong is
 * silent in the same way: an alarm not armed is a reminder that does not arrive, and an alarm
 * cancelled early is the same thing — neither leaves a trace, and both look from the outside like
 * the phone being the phone.
 */
class ReminderPlanTest {

    private val now = 1_800_000_000_000L
    private val soon = now + 60_000L
    private val later = now + 600_000L
    private val past = now - 60_000L

    private fun rows(vararg pairs: Pair<String, Long>) = pairs.map { ReminderRow(it.first, it.second) }

    private fun plan(scheduled: Map<String, Long>, rows: List<ReminderRow>) =
        ReminderManager.Plan.from(scheduled, rows, now)

    @Test
    fun `a new future reminder is armed`() {
        val p = plan(emptyMap(), rows("a" to soon))
        assertEquals(mapOf("a" to soon), p.arm)
        assertTrue(p.cancel.isEmpty())
    }

    /** The reason the memory exists: every keystroke in the app re-emits this list. */
    @Test
    fun `a reminder already armed at the same instant is left alone`() {
        val p = plan(mapOf("a" to soon), rows("a" to soon))
        assertTrue("re-armed for no reason: ${p.arm}", p.arm.isEmpty())
        assertTrue(p.cancel.isEmpty())
    }

    @Test
    fun `a reminder whose instant moved is armed again at the new one`() {
        val p = plan(mapOf("a" to soon), rows("a" to later))
        assertEquals(mapOf("a" to later), p.arm)
        // Not cancelled: arming the same node replaces an equal PendingIntent, and a cancel here
        // would be a window in which the reminder is armed nowhere.
        assertTrue(p.cancel.isEmpty())
    }

    @Test
    fun `a reminder that left the set is cancelled`() {
        // Cleared, completed or deleted — the query drops all three the same way.
        val p = plan(mapOf("a" to soon, "b" to later), rows("b" to later))
        assertEquals(listOf("a"), p.cancel)
        assertTrue(p.arm.isEmpty())
    }

    /**
     * A moment that has passed is not armed, and not cancelled either.
     *
     * Arming it would fire immediately for something whose time was yesterday. Cancelling it would
     * eat a reminder mid-flight: Doze and `setWindow` can deliver after the nominal instant, so an
     * alarm whose time is "past" may still be about to arrive. Leaving it is safe because the
     * receiver re-reads the database before it shows anything.
     */
    @Test
    fun `a past instant is neither armed nor cancelled`() {
        val p = plan(mapOf("a" to past), rows("a" to past))
        assertTrue(p.arm.isEmpty())
        assertTrue(p.cancel.isEmpty())
    }

    @Test
    fun `a reminder dragged backwards into the past is not armed`() {
        val p = plan(mapOf("a" to later), rows("a" to past))
        assertTrue(p.arm.isEmpty())
        // The old alarm stays armed and will fire at the old time, where the receiver finds the
        // instant no longer matches and shows nothing. That is the design, not an oversight.
        assertTrue(p.cancel.isEmpty())
    }

    /**
     * The memory is the row set, not the set that was armed.
     *
     * If it recorded only what was armed, a past reminder would be absent from it, and the very
     * next emission would read that absence as "this one left" and cancel an alarm that is still
     * in flight.
     */
    @Test
    fun `what is remembered is every row, including the ones not armed`() {
        val p = plan(emptyMap(), rows("a" to soon, "b" to past))
        assertEquals(mapOf("a" to soon, "b" to past), p.armed)
        assertEquals(mapOf("a" to soon), p.arm)
    }

    /**
     * Re-arming from an empty memory is what a reboot does, and what revoking exact alarms needs.
     *
     * Android cancels every exact alarm the app holds when that access is withdrawn. Asking the
     * ordinary sync to fix it would compare the rows against a memory that still says "armed",
     * change nothing, and leave the person with no reminders at all — which is why the manager
     * clears the memory first rather than calling sync.
     */
    @Test
    fun `an empty memory arms everything still in the future`() {
        val p = plan(emptyMap(), rows("a" to soon, "b" to later, "c" to past))
        assertEquals(mapOf("a" to soon, "b" to later), p.arm)
        assertTrue("nothing should be cancelled on a rebuild: ${p.cancel}", p.cancel.isEmpty())
    }

    @Test
    fun `an emptied set cancels everything it was holding`() {
        val p = plan(mapOf("a" to soon, "b" to later), emptyList())
        assertEquals(setOf("a", "b"), p.cancel.toSet())
        assertTrue(p.arm.isEmpty())
        assertTrue(p.armed.isEmpty())
    }

    /** Two sources, one plan — a task's reminder and an event's are the same kind of alarm. */
    @Test
    fun `rows from both sources are planned together`() {
        val tasks = rows("task" to soon)
        val events = rows("event" to later)
        val p = plan(emptyMap(), tasks + events)
        assertEquals(mapOf("task" to soon, "event" to later), p.arm)
    }
}
