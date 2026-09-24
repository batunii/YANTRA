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
        settleSections(settled, live, { it.id }, { it.done })

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
    fun `un-ticking a finished row returns it to to-do at once`() {
        // Not symmetric with ticking, on purpose: you have just said this is still to do, and
        // leaving it under a heading reading DONE states the opposite. Nothing is lost under a
        // finger either — you do not work down the finished half.
        val live = listOf(Row("a", false), Row("b", false), Row("c", false))
        val s = sections(settled, live)
        assertEquals(listOf("a", "b", "c"), s.todo.map { it.id })
        assertEquals(emptyList<String>(), s.done.map { it.id })
    }

    @Test
    fun `a row settled as done that is still done stays there`() {
        val live = listOf(Row("a", false), Row("b", false), Row("c", true))
        val s = sections(settled, live)
        assertEquals(listOf("c"), s.done.map { it.id })
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

    /**
     * The half a row belongs to is decided when *that row* first appears, not when the list first
     * has anything in it. A smart list reads its open tasks and its finished ones from two separate
     * queries which arrive independently, so "settle the whole list once it is non-empty" settled
     * DONE as empty whenever the open half won the race — and the finished tasks then drew as
     * things still to do, with no DONE heading. It depended on which query answered first, so it
     * happened intermittently.
     *
     * These walk that sequence the way the composable does: fold each emission in, then split.
     */
    private fun seenInOrder(vararg emissions: List<Row>): Pair<List<String>, List<String>> {
        val seen = HashSet<String>()
        val settled = HashSet<String>()
        var last: List<Row> = emptyList()
        emissions.forEach { live ->
            live.forEach { r -> if (seen.add(r.id) && r.done) settled += r.id }
            last = live
        }
        val s = sections(settled, last)
        return s.todo.map { it.id } to s.done.map { it.id }
    }

    @Test
    fun `finished tasks arriving after the open ones still land under DONE`() {
        val (todo, done) = seenInOrder(
            emptyList(),                                          // nothing yet
            listOf(Row("a", false), Row("b", false)),             // the open query answers first
            listOf(Row("a", false), Row("b", false), Row("c", true)),  // the finished one follows
        )
        assertEquals(listOf("a", "b"), todo)
        assertEquals("c belongs under DONE whenever it turns up", listOf("c"), done)
    }

    @Test
    fun `finished tasks arriving first are equally fine`() {
        val (todo, done) = seenInOrder(
            listOf(Row("c", true)),
            listOf(Row("a", false), Row("b", false), Row("c", true)),
        )
        assertEquals(listOf("a", "b"), todo)
        assertEquals(listOf("c"), done)
    }

    @Test
    fun `a task ticked after it was first seen open stays where it is`() {
        // The whole point of settling, and it has to survive the per-row rule.
        val (todo, done) = seenInOrder(
            listOf(Row("a", false), Row("b", false)),
            listOf(Row("a", false), Row("b", true)),
        )
        assertEquals(listOf("a", "b"), todo)
        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun `a task created while the list is open is to-do even if it arrives done`() {
        val (todo, done) = seenInOrder(
            listOf(Row("a", false), Row("c", true)),
            listOf(Row("a", false), Row("c", true), Row("new", false)),
        )
        assertEquals(listOf("a", "new"), todo)
        assertEquals(listOf("c"), done)
    }

    /**
     * A row whose answer has not arrived is left unsettled rather than settled wrongly.
     *
     * An event's end time comes from a different query than its row, so for a frame a finished
     * meeting is indistinguishable from an unfinished one — the same race that put finished tasks
     * in the to-do half, one layer down.
     */
    private fun seenWhenJudgeable(
        vararg emissions: Pair<List<Row>, Set<String>>,
    ): Pair<List<String>, List<String>> {
        val seen = HashSet<String>()
        val settled = HashSet<String>()
        var last: List<Row> = emptyList()
        emissions.forEach { (live, judgeable) ->
            live.forEach { r ->
                if (r.id !in judgeable) return@forEach
                if (seen.add(r.id) && r.done) settled += r.id
            }
            last = live
        }
        val s = sections(settled, last)
        return s.todo.map { it.id } to s.done.map { it.id }
    }

    @Test
    fun `a finished meeting whose times arrive late still reaches DONE`() {
        val (todo, done) = seenWhenJudgeable(
            // The row is there; its end time is not, so nothing is decided about it yet.
            listOf(Row("a", false), Row("meeting", true)) to setOf("a"),
            // Times arrive, and only now is it filed.
            listOf(Row("a", false), Row("meeting", true)) to setOf("a", "meeting"),
        )
        assertEquals(listOf("a"), todo)
        assertEquals(listOf("meeting"), done)
    }

    @Test
    fun `a meeting still to come stays in to-do once its times are known`() {
        val (todo, done) = seenWhenJudgeable(
            listOf(Row("a", false), Row("meeting", false)) to setOf("a"),
            listOf(Row("a", false), Row("meeting", false)) to setOf("a", "meeting"),
        )
        assertEquals(listOf("a", "meeting"), todo)
        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun `a meeting that ends while you are looking does not move`() {
        // The clock is not allowed to pull a row out from under you when your own finger is not.
        val (todo, done) = seenWhenJudgeable(
            listOf(Row("meeting", false)) to setOf("meeting"),   // still running when first seen
            listOf(Row("meeting", true)) to setOf("meeting"),    // ends a moment later
        )
        assertEquals("it joins DONE the next time the list is built", listOf("meeting"), todo)
        assertEquals(emptyList<String>(), done)
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
