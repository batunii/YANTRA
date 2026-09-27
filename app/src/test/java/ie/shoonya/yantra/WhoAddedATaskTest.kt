package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.NodeAuthor
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.data.format.PageDoc
import ie.shoonya.yantra.data.format.TaskRef
import ie.shoonya.yantra.data.people.addedByOthers
import ie.shoonya.yantra.data.sync.TaskOrigins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * A task on a shared list says who added it — from its own line when it can, from the history when
 * it cannot, and never by guessing.
 *
 * The line's `by:` is the stamp; the history is the fallback for every task written before there was
 * one. The history can only name somebody from a commit signed with a login: every commit the app
 * made before that was "Yantra" from every phone, and treating that as a person would credit every
 * old task to nobody in particular with total confidence.
 */
class WhoAddedATaskTest {

    private fun page(vararg tasks: TaskRef) = PageDoc(
        id = "p1", type = "list", parent = null, title = "Shared",
        modifiedAt = Instant.parse("2026-09-27T10:00:00Z"), device = "test",
        blocks = tasks.toList(),
    )

    private fun roundTrip(t: TaskRef): TaskRef =
        PageCodec.decode(PageCodec.encode(page(t))).blocks.single() as TaskRef

    @Test
    fun `a stamped task says who added it, and keeps saying it`() {
        val t = TaskRef(id = "t1", title = "Draft the API doc", assignee = "sai", author = "batunii")
        assertTrue(PageCodec.encode(page(t)).contains("- [ ] Draft the API doc ^t1 @sai by:batunii"))
        assertEquals("batunii", roundTrip(t).author)
        assertEquals("sai", roundTrip(t).assignee)
        assertEquals("Draft the API doc", roundTrip(t).title)
    }

    @Test
    fun `a task nobody stamped writes exactly what it always wrote`() {
        // The whole of the compatibility promise: every file that predates this is byte-identical
        // after a round trip, so upgrading is not a commit that touches every line.
        val t = TaskRef(id = "t1", title = "Buy milk", labels = listOf("home"))
        val encoded = PageCodec.encode(page(t))
        assertFalse(encoded.contains("by:"))
        assertNull(roundTrip(t).author)
    }

    @Test
    fun `by-colon in the middle of a title is still the title`() {
        val decoded = PageCodec.decode(
            """
            ---
            id: p1
            type: list
            title: Shared
            modified_at: 2026-09-27T10:00:00Z
            ---
            - [ ] Pay by: Friday ^t1
            """.trimIndent()
        ).blocks.single() as TaskRef
        assertEquals("Pay by: Friday", decoded.title)
        assertNull(decoded.author)
    }

    @Test
    fun `the history reads task ids, not the links that point at them`() {
        val text = """
            - [ ] Draft the API doc ^t1 by:sai
            - [x] Ship it ^t2 done:2026-09-20
            A note that mentions [[Draft the API doc|^t1]] and [[Other|^t9]]
            - [ ] Links to [[Draft|^t1]] in its title ^t3
            @ 2026-09-28T09:00 Standup ^e1
        """.trimIndent()
        assertEquals(setOf("t1", "t2", "t3"), TaskOrigins.idsIn(text))
    }

    @Test
    fun `a commit signed by the app names the login it was signed with`() {
        assertEquals("sai", TaskOrigins.loginOf("sai", "sai@users.noreply.github.com"))
        // GitHub's newer no-reply form carries the account id in front.
        assertEquals("sai", TaskOrigins.loginOf("Sai K", "12345+sai@users.noreply.github.com"))
    }

    @Test
    fun `the old signature names nobody`() {
        assertNull(TaskOrigins.loginOf("Yantra", "yantra@shoonya.ie"))
        // From before the rename. v2-tasks has 44 of these, and reading them as a person credited
        // twelve tasks to somebody called Yantra.
        assertNull(TaskOrigins.loginOf("Yantra", "yantra@napkin.ie"))
    }

    @Test
    fun `a commit made elsewhere is taken at its author name`() {
        assertEquals("Sai K", TaskOrigins.loginOf("Sai K", "sai@example.com"))
    }

    @Test
    fun `a row names somebody else, and says nothing for you`() {
        val rows = listOf(
            NodeAuthor("t1", "ws", "sai"),
            NodeAuthor("t2", "ws", "batunii"),
            NodeAuthor("t3", "ws", "Batunii"),
        )
        assertEquals(mapOf("t1" to "sai"), addedByOthers(rows) { "batunii" })
    }

    @Test
    fun `you are whoever you are signed in to that repository as`() {
        val rows = listOf(NodeAuthor("t1", "work", "batunii"), NodeAuthor("t2", "oss", "batunii"))
        val logins = mapOf("work" to "batunii", "oss" to "shrey-oss")
        assertEquals(mapOf("t2" to "batunii"), addedByOthers(rows) { logins[it] })
    }

    @Test
    fun `nobody signed in means nobody is you`() {
        val rows = listOf(NodeAuthor("t1", "ws", "sai"))
        assertEquals(mapOf("t1" to "sai"), addedByOthers(rows) { null })
    }
}
