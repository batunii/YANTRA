package ie.napkin.supertasks

import ie.napkin.supertasks.data.filter.DateRel
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterCompiler
import ie.napkin.supertasks.data.filter.Op
import ie.napkin.supertasks.data.filter.completedVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `completedVariant` decides whether a smart list grows a DONE section and what goes in it.
 * It is three mutually-recursive walks over the filter tree and it was the only part of the
 * query layer with no coverage — a wrong answer here either hides the day's finished work or
 * hangs an empty heading under every view.
 */
class CompletedVariantTest {

    private val todayOpen = Filter.All(
        listOf(
            Filter.Type("task"),
            Filter.Done(false),
            Filter.Prop(defId = "due", op = Op.LTE, dateRel = DateRel.TODAY_END),
        )
    )

    @Test
    fun `an open-tasks rule flips to its completed counterpart`() {
        val flipped = completedVariant(todayOpen)
        assertEquals(
            Filter.All(
                listOf(
                    Filter.Type("task"),
                    Filter.Done(true),
                    Filter.Prop(defId = "due", op = Op.LTE, dateRel = DateRel.TODAY_END),
                )
            ),
            flipped,
        )
    }

    @Test
    fun `a rule with no done clause has no separate completed half`() {
        // It already returns both halves, so the caller counts the done ones itself.
        val f = Filter.All(listOf(Filter.Type("task"), Filter.Prop("pri", Op.EQ, text = "High")))
        assertNull(completedVariant(f))
    }

    @Test
    fun `a rule asking only for completed tasks is not flipped back to open`() {
        // Done(true) is not a "still open" clause, so there is nothing to ask the other way.
        assertNull(completedVariant(Filter.All(listOf(Filter.Type("task"), Filter.Done(true)))))
    }

    @Test
    fun `a started-tasks rule has no completed half`() {
        // setDone clears in_progress in the same UPDATE, so "started AND done" is a set that
        // cannot exist — flipping would render an empty DONE heading under every such view.
        val started = Filter.All(
            listOf(Filter.Type("task"), Filter.Done(false), Filter.InProgress(true))
        )
        assertNull(completedVariant(started))
    }

    @Test
    fun `a not-started rule still has a completed half`() {
        // InProgress(false) says nothing about completion, so the flip is meaningful.
        val notStarted = Filter.All(
            listOf(Filter.Type("task"), Filter.Done(false), Filter.InProgress(false))
        )
        val flipped = completedVariant(notStarted)
        assertEquals(
            Filter.All(
                listOf(Filter.Type("task"), Filter.Done(true), Filter.InProgress(false))
            ),
            flipped,
        )
    }

    @Test
    fun `the flip reaches into nested branches`() {
        val nested = Filter.AnyOf(
            listOf(
                Filter.All(listOf(Filter.Done(false), Filter.Prop("due", Op.IS_SET))),
                Filter.All(listOf(Filter.Done(false), Filter.HasLabel("l1"))),
            )
        )
        assertEquals(
            Filter.AnyOf(
                listOf(
                    Filter.All(listOf(Filter.Done(true), Filter.Prop("due", Op.IS_SET))),
                    Filter.All(listOf(Filter.Done(true), Filter.HasLabel("l1"))),
                )
            ),
            completedVariant(nested),
        )
    }

    @Test
    fun `a negated done clause flips inside the negation`() {
        // Not(Done(false)) reads "done"; flipping the inner clause gives Not(Done(true)) = "open".
        val f = Filter.All(listOf(Filter.Type("task"), Filter.Not(Filter.Done(false))))
        assertEquals(
            Filter.All(listOf(Filter.Type("task"), Filter.Not(Filter.Done(true)))),
            completedVariant(f),
        )
    }

    @Test
    fun `the flipped rule still compiles`() {
        val q = FilterCompiler.compile(null, completedVariant(todayOpen))
        assertEquals(q.sql.count { it == '?' }, q.args.size)
        // done = 1 is bound where done = 0 used to be
        assertEquals(listOf<Any>("task", 1L, "due", q.args[3]), q.args)
    }
}
