package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.FocusSessionEntity
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.data.format.PageDoc
import ie.shoonya.yantra.data.format.TaskRef
import ie.shoonya.yantra.data.format.TaskStatus
import ie.shoonya.yantra.data.workspace.WorkspaceReconciler
import ie.shoonya.yantra.domain.isMineToPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * In progress belongs to whoever started it, and focus time to whoever spent it.
 *
 * Both used to be facts about the task alone: `- [~]` put a teammate's started task in your player,
 * and a session in the shared log counted as yours in Stats — or, after a restart, came back as your
 * running timer. The file now says who, in a way every older build reads exactly as before.
 */
class InProgressIsPerPersonTest {

    private fun page(vararg tasks: TaskRef) = PageDoc(
        id = "p1", type = "list", parent = null, title = "Shared",
        modifiedAt = Instant.parse("2026-09-28T10:00:00Z"), device = "test",
        blocks = tasks.toList(),
    )

    private fun roundTrip(t: TaskRef): TaskRef =
        PageCodec.decode(PageCodec.encode(page(t))).blocks.single() as TaskRef

    @Test
    fun `everybody on a task is named on its line`() {
        val t = TaskRef(
            id = "t1", title = "Draft the doc", status = TaskStatus.IN_PROGRESS,
            startedBy = listOf("sai", "batunii"), author = "sai",
        )
        assertTrue(PageCodec.encode(page(t)).contains("- [~] Draft the doc ^t1 on:sai on:batunii by:sai"))
        assertEquals(listOf("sai", "batunii"), roundTrip(t).startedBy)
        assertEquals("Draft the doc", roundTrip(t).title)
    }

    @Test
    fun `a task nobody is on carries no claim`() {
        // Put down or finished, a stale name would say somebody is still on it.
        for (status in listOf(TaskStatus.OPEN, TaskStatus.DONE)) {
            val t = TaskRef(id = "t1", title = "Draft", status = status, startedBy = listOf("sai"))
            assertFalse(PageCodec.encode(page(t)).contains("on:"))
            assertTrue(roundTrip(t).startedBy.isEmpty())
        }
    }

    @Test
    fun `an old in-progress line is nobody in particular`() {
        val t = TaskRef(id = "t1", title = "Draft", status = TaskStatus.IN_PROGRESS)
        assertTrue(PageCodec.encode(page(t)).contains("- [~] Draft ^t1"))
        assertTrue(roundTrip(t).startedBy.isEmpty())
    }

    @Test
    fun `your player has what you are on and what nobody claimed`() {
        assertTrue(isMineToPlay(emptyList(), "batunii"))
        assertTrue(isMineToPlay(listOf("sai", "Batunii"), "batunii"))
        assertFalse(isMineToPlay(listOf("sai"), "batunii"))
        // Signed in as nobody, only an unclaimed task can be yours.
        assertFalse(isMineToPlay(listOf("sai"), null))
        assertTrue(isMineToPlay(emptyList(), null))
    }

    private fun session(by: String?) = FocusSessionEntity(
        id = "s1", nodeId = "t1", startedAt = 1_000L, endedAt = 61_000L, plannedSecs = 0,
        actualSecs = 60, outcome = "stopped", by = by, createdAt = 1_000L, updatedAt = 61_000L,
    )

    @Test
    fun `a session nobody is named on writes exactly the line it always wrote`() {
        // Personal's, and the byte-for-byte promise to every older build reading the log.
        assertEquals("s1\tt1\t1000\t61000\t0\t60\t0\tstopped", WorkspaceReconciler.sessionLine(session(null)))
    }

    @Test
    fun `a session says who timed it in a field older builds skip`() {
        val line = WorkspaceReconciler.sessionLine(session("sai"))
        assertEquals("s1\tt1\t1000\t61000\t0\t60\t0\tstopped\tsai", line)
        // An older build reads up to field 8; the ninth is past everything it looks at.
        assertEquals("stopped", line.split('\t')[7])
    }
}
