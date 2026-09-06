package ie.napkin.supertasks

import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.widget.WidgetLabel
import ie.napkin.supertasks.widget.buildRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a widget row carries beyond its title.
 *
 * [buildRows] is the pure half of the list widget and the only part of it that can be tested
 * without a launcher, so the mapping is worth pinning here: a row that silently loses its labels
 * or picks up the wrong repository's colour looks exactly like a row that never had them.
 */
class WidgetRowDetailTest {

    private fun task(id: String, workspace: String = "", parent: String? = "list-1") = NodeEntity(
        id = id,
        workspaceId = workspace,
        parentId = parent,
        type = NodeType.TASK,
        title = "Write the thing",
        rank = "a",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `labels reach the row in order, with their own colours`() {
        val rows = buildRows(
            nodes = listOf(task("t1")),
            values = emptyList(),
            due = null, deadline = null, priority = null,
            priorityColors = emptyMap(),
            labels = mapOf("t1" to listOf(WidgetLabel("work", 0xFF112233), WidgetLabel("urgent", null))),
        )
        assertEquals(listOf("work", "urgent"), rows.single().labels.map { it.name })
        assertEquals(0xFF112233, rows.single().labels.first().color)
        // A label with no colour of its own stays null here; the row resolves it at draw time
        // against the theme rather than baking one in.
        assertNull(rows.single().labels[1].color)
    }

    @Test
    fun `a task with no labels gets an empty list rather than a null`() {
        val rows = buildRows(
            nodes = listOf(task("t1")), values = emptyList(),
            due = null, deadline = null, priority = null, priorityColors = emptyMap(),
            labels = mapOf("other" to listOf(WidgetLabel("work", null))),
        )
        assertTrue(rows.single().labels.isEmpty())
    }

    @Test
    fun `each row takes the hue of the workspace it came from`() {
        val rows = buildRows(
            nodes = listOf(task("t1", workspace = "personal"), task("t2", workspace = "work")),
            values = emptyList(),
            due = null, deadline = null, priority = null, priorityColors = emptyMap(),
            workspaceHues = mapOf("personal" to 0xFFAA0000, "work" to 0xFF0000BB),
        )
        assertEquals(0xFFAA0000, rows[0].workspaceHue)
        assertEquals(0xFF0000BB, rows[1].workspaceHue)
    }

    /**
     * The single-workspace case, which the caller expresses by passing no hues at all. A colour
     * that always means the same thing means nothing, so the rule is not drawn — and the row must
     * report that as an absence rather than as some default.
     */
    @Test
    fun `no hue when the caller offers none`() {
        val rows = buildRows(
            nodes = listOf(task("t1", workspace = "personal")), values = emptyList(),
            due = null, deadline = null, priority = null, priorityColors = emptyMap(),
        )
        assertNull(rows.single().workspaceHue)
    }
}
