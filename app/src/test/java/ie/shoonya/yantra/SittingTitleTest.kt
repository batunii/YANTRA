package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A sitting is drawn with its task's words, and an event with its own.
 *
 * **The bug this exists for.** A sitting is a reminder about a task — it has no title of its own,
 * by design, because its words *are* the task's. Three surfaces knew that and read
 * `EventWithTitle.displayTitle`. Home's next-up row read `title`, got the empty string a sitting
 * genuinely has, and announced the one thing you are next expected at as "Untitled".
 *
 * The rule was never wrong; one caller simply reached past it, and the field it reached for was
 * called `title`, which reads as the obvious choice. It is `ownTitle` now, so reaching for it is a
 * decision rather than an accident — and these tests are what makes the rule itself checkable
 * instead of a property nobody had ever asserted on.
 */
class SittingTitleTest {

    private fun row(
        title: String?,
        forNodeId: String? = null,
        forTitle: String? = null,
    ) = indexed(
        event = EventEntity(
            nodeId = "e1",
            startLocal = "2026-09-21T09:00",
            endLocal = "2026-09-21T09:30",
            allDay = false,
            startUtc = 0,
            endUtc = 0,
            workspaceId = "",
            forNodeId = forNodeId,
        ),
        title = title,
        forTitle = forTitle,
    )

    @Test
    fun `an event is drawn with its own title`() {
        assertEquals("Pluto x Napkin", row("Pluto x Napkin").displayTitle)
    }

    @Test
    fun `a sitting is drawn with the words of the task it is for`() {
        // The case Home got wrong: the event's own title is empty, and the task's is the answer.
        val sitting = row(title = "", forNodeId = "t1", forTitle = "Write the deck")
        assertEquals("Write the deck", sitting.displayTitle)
    }

    /**
     * A sitting whose task has gone falls back rather than resolving to nothing.
     *
     * The join is a LEFT JOIN filtered on `deleted_at IS NULL`, so a deleted task yields a null
     * `forTitle` while `for_node_id` still points at it. There is nothing better to show than the
     * event's own words, and callers turn a blank into "Untitled" — which is the honest answer when
     * the thing it was about is gone.
     */
    @Test
    fun `a sitting whose task is gone falls back to its own title`() {
        val orphan = row(title = "", forNodeId = "t1", forTitle = null)
        assertEquals("", orphan.displayTitle)
    }

    /** An ordinary event is never given somebody else's words, even if the column is populated. */
    @Test
    fun `an event ignores a for-title it has no business with`() {
        val odd = row(title = "Standup", forNodeId = null, forTitle = "Write the deck")
        assertEquals("Standup", odd.displayTitle)
    }
}
