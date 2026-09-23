package ie.shoonya.yantra

import ie.shoonya.yantra.ui.components.settleSections
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticking a box does not move the row — [settleSections].
 *
 * The behaviour these pin down is a timing one: which half a row belongs to is decided when the
 * list is built, so completing something strikes it where it stands and the reorder waits for the
 * next build. Every case below is written as "the list was drawn like *this*, then something
 * changed, and here is where the rows are now".
 */
class SettledSectionsTest {

    private data class Row(val id: String, val done: Boolean)

    private fun sections(settled: Set<String>, live: List<Row>) =
        settleSections(settled, live) { it.id }

    /** What the list looked like when it was drawn: a, b open; c finished. */
    private val settled = setOf("c")

    @Test
    fun `a freshly drawn list splits on what is actually done`() {
        val live = listOf(Row("a", false), Row("b", false), Row("c", true))
        val s = sections(settled, live)
        assertEquals(listOf("a", "b"), s.todo.map { it.id })
        assertEquals(listOf("c"), s.done.map { it.id })
    }

    @Test
    fun `ticking a row leaves it exactly where it was`() {
        // `b` is now done, and the settling predates that.
        val live = listOf(Row("a", false), Row("b", true), Row("c", true))
        val s = sections(settled, live)
        assertEquals("b stays in place rather than dropping into DONE", listOf("a", "b"), s.todo.map { it.id })
        assertEquals(listOf("c"), s.done.map { it.id })
    }

    @Test
    fun `un-ticking a finished row is symmetric`() {
        val live = listOf(Row("a", false), Row("b", false), Row("c", false))
        val s = sections(settled, live)
        assertEquals("c stays under DONE until the list is built again", listOf("c"), s.done.map { it.id })
    }

    @Test
    fun `building the list again is what moves it`() {
        // The same rows, settled afresh: now `b` is genuinely in the finished half.
        val live = listOf(Row("a", false), Row("b", true), Row("c", true))
        val s = sections(setOf("b", "c"), live)
        assertEquals(listOf("a"), s.todo.map { it.id })
        assertEquals(listOf("b", "c"), s.done.map { it.id })
    }

    @Test
    fun `a row that arrives after the settling goes after the ones that were there`() {
        val live = listOf(Row("a", false), Row("b", false), Row("c", true), Row("new", false))
        val s = sections(settled, live)
        assertEquals(listOf("a", "b", "new"), s.todo.map { it.id })
    }

    @Test
    fun `a new row that is already done is to-do until the next build`() {
        // Completing from somewhere else — a widget, the player — must not make a row appear
        // under DONE on a list that never showed it open.
        val live = listOf(Row("a", false), Row("b", false), Row("c", true), Row("new", true))
        val s = sections(settled, live)
        assertEquals(listOf("a", "b", "new"), s.todo.map { it.id })
        assertEquals(listOf("c"), s.done.map { it.id })
    }

    @Test
    fun `a row that has gone is simply gone`() {
        val live = listOf(Row("a", false), Row("c", true))
        val s = sections(settled, live)
        assertEquals(listOf("a"), s.todo.map { it.id })
        assertEquals(listOf("c"), s.done.map { it.id })
    }

    @Test
    fun `order within a half is the caller's, not this function's`() {
        // A drag previews where it will land, and a smart list floats what is running to the top.
        // Settling membership must not quietly overrule either.
        val live = listOf(Row("b", false), Row("a", false), Row("c", true))
        val s = sections(settled, live)
        assertEquals(listOf("b", "a"), s.todo.map { it.id })
    }

    @Test
    fun `nothing settled yet means everything is to-do`() {
        val live = listOf(Row("a", false), Row("c", true))
        val s = sections(emptySet(), live)
        assertEquals(listOf("a", "c"), s.todo.map { it.id })
        assertEquals(emptyList<String>(), s.done.map { it.id })
    }

    @Test
    fun `an empty list stays empty`() {
        val s = sections(settled, emptyList())
        assertEquals(emptyList<String>(), s.todo.map { it.id })
        assertEquals(emptyList<String>(), s.done.map { it.id })
    }
}
