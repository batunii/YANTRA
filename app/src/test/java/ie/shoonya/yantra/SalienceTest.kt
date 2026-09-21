package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.BuiltIns
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.filter.DateRel
import ie.shoonya.yantra.data.filter.Field
import ie.shoonya.yantra.data.filter.Filter
import ie.shoonya.yantra.data.filter.Op
import ie.shoonya.yantra.data.filter.Salience
import ie.shoonya.yantra.data.filter.ViewContext
import ie.shoonya.yantra.data.filter.Weight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalienceTest {

    private val due = Field.Prop(BuiltIns.DUE_DEF_ID)
    private val deadline = Field.Prop(BuiltIns.DEADLINE_DEF_ID)
    private val assignee = Field.Prop(BuiltIns.ASSIGNEE_DEF_ID)

    private fun ctx(filter: Filter?, single: Boolean = false) = ViewContext(filter, single)

    @Test
    fun `list page - list is pinned, due takes the title slot, assignee on the sub-line`() {
        val g = Salience.grammar(ctx(filter = null))
        assertEquals(due, g.titleSlot)
        assertEquals(listOf(deadline, assignee), g.subLine)
        assertEquals(Field.Workspace, g.spine)
        assertTrue(Field.OriginList in g.pinned)
    }

    @Test
    fun `Today as seeded - due OR deadline branch, list and assignee follow`() {
        val today = Filter.All(listOf(
            Filter.Type(NodeType.TASK), Filter.Done(false),
            Filter.AnyOf(listOf(
                Filter.Prop(BuiltIns.DUE_DEF_ID, Op.LTE, dateRel = DateRel.TODAY_END),
                Filter.Prop(BuiltIns.DEADLINE_DEF_ID, Op.LTE, dateRel = DateRel.TODAY_END),
            )),
        ))
        val w = Salience.weigh(ctx(today))
        assertEquals(Weight.Branched, w[due])
        assertEquals(Weight.Branched, w[deadline])
        val g = Salience.grammar(ctx(today))
        // Both dates are branched; the first is the slot, the other leads the sub-line. The
        // row shows whichever one the task actually has.
        assertEquals(due, g.titleSlot)
        assertEquals(listOf(deadline, assignee, Field.OriginList), g.subLine)
    }

    @Test
    fun `strict due-equals-today pins due - deadline takes the slot`() {
        val f = Filter.All(listOf(
            Filter.Prop(BuiltIns.DUE_DEF_ID, Op.EQ, dateRel = DateRel.TODAY_START),
        ))
        val g = Salience.grammar(ctx(f))
        assertTrue(due in g.pinned)
        assertEquals(deadline, g.titleSlot)
        assertEquals(listOf(assignee, Field.OriginList), g.subLine)
    }

    @Test
    fun `AND of label and workspace hides both, single workspace hides the spine`() {
        val f = Filter.All(listOf(Filter.HasLabel("work"), Filter.InWorkspace("ws-a")))
        val g = Salience.grammar(ctx(f, single = true))
        assertTrue(Field.Label("work") in g.pinned)
        assertTrue(Field.Workspace in g.pinned)
        assertNull(g.spine)
    }

    @Test
    fun `OR of two workspaces promotes workspace even above due`() {
        val f = Filter.AnyOf(listOf(Filter.InWorkspace("ws-a"), Filter.InWorkspace("ws-b")))
        val g = Salience.grammar(ctx(f))
        assertEquals(Weight.Branched, Salience.weigh(ctx(f))[Field.Workspace])
        // Workspace is the spine, so it does not crowd the sub-line; the rest is default order.
        assertEquals(Field.Workspace, g.spine)
        assertEquals(due, g.titleSlot)
    }

    @Test
    fun `OR of two labels - both branched, and remain the reader's reason`() {
        val f = Filter.AnyOf(listOf(Filter.HasLabel("urgent"), Filter.HasLabel("blocked")))
        val w = Salience.weigh(ctx(f))
        assertEquals(Weight.Branched, w[Field.Label("urgent")])
        assertEquals(Weight.Branched, w[Field.Label("blocked")])
    }

    @Test
    fun `assigned to me pins assignee, due leads`() {
        val f = Filter.All(listOf(Filter.Prop(BuiltIns.ASSIGNEE_DEF_ID, Op.EQ, text = "batunii")))
        val g = Salience.grammar(ctx(f))
        assertTrue(assignee in g.pinned)
        assertEquals(due, g.titleSlot)
        assertEquals(listOf(deadline, Field.OriginList), g.subLine)
    }

    @Test
    fun `no due date - NOT_SET pins due so it never shows`() {
        val f = Filter.All(listOf(Filter.Prop(BuiltIns.DUE_DEF_ID, Op.NOT_SET)))
        val g = Salience.grammar(ctx(f))
        assertTrue(due in g.pinned)
        assertEquals(deadline, g.titleSlot)
    }

    @Test
    fun `a field named in an AND and again in an OR keeps the OR weight`() {
        val f = Filter.All(listOf(
            Filter.Prop(BuiltIns.DUE_DEF_ID, Op.IS_SET),
            Filter.AnyOf(listOf(
                Filter.Prop(BuiltIns.DUE_DEF_ID, Op.LTE, dateRel = DateRel.TODAY_END),
                Filter.HasLabel("urgent"),
            )),
        ))
        assertEquals(Weight.Branched, Salience.weigh(ctx(f))[due])
    }

    @Test
    fun `one-armed AnyOf is an All`() {
        val f = Filter.AnyOf(listOf(Filter.HasLabel("x")))
        assertEquals(Weight.Pinned, Salience.weigh(ctx(f))[Field.Label("x")])
    }

    // ---- what a negation does, which the delivered engine had not been asked ----

    /**
     * `Not(AnyOf(...))` is an AND of negations, so its arms are not branches.
     *
     * Without De Morgan the walk saw an AnyOf with two arms and promoted both dates to the front
     * of the row as the reason it is there — when they are precisely the reason it is not.
     */
    @Test
    fun `a negated OR does not branch`() {
        val f = Filter.Not(Filter.AnyOf(listOf(
            Filter.Prop(BuiltIns.DUE_DEF_ID, Op.LTE, dateRel = DateRel.TODAY_END),
            Filter.Prop(BuiltIns.DEADLINE_DEF_ID, Op.LTE, dateRel = DateRel.TODAY_END),
        )))
        val w = Salience.weigh(ctx(f))
        assertEquals(Weight.Bounded, w[due])
        assertEquals(Weight.Bounded, w[deadline])
        assertTrue("nothing branched, so nothing leads", Salience.grammar(ctx(f)).branched.isEmpty())
    }

    /** `Not(All(...))` is an OR of negations: it fixes nothing, so nothing may be pinned. */
    @Test
    fun `a negated AND pins nothing`() {
        val f = Filter.Not(Filter.All(listOf(
            Filter.Prop(BuiltIns.DUE_DEF_ID, Op.IS_SET),
            Filter.Prop(BuiltIns.ASSIGNEE_DEF_ID, Op.IS_SET),
        )))
        val w = Salience.weigh(ctx(f))
        assertEquals(Weight.Bounded, w[due])
        assertEquals(Weight.Bounded, w[assignee])
        assertTrue(Salience.grammar(ctx(f)).pinned.isEmpty())
    }

    /** Excluding one repository leaves the others, so the workspace still varies — and still spines. */
    @Test
    fun `excluding one workspace still leaves a spine`() {
        val f = Filter.Not(Filter.InWorkspace("ws-a"))
        assertEquals(Weight.Bounded, Salience.weigh(ctx(f))[Field.Workspace])
        assertEquals(Field.Workspace, Salience.grammar(ctx(f)).spine)
    }

    /**
     * A tag absent from every row is as silent as one present on every row.
     *
     * Both forms land there, and the second is why De Morgan matters: `Not(urgent OR blocked)` is
     * `NOT urgent AND NOT blocked`, so neither tag is on anything here and neither can be the
     * reason a row is. Nothing branches, so nothing leads.
     */
    @Test
    fun `a tag excluded is pinned, however the exclusion is spelt`() {
        val one = Filter.Not(Filter.HasLabel("x"))
        assertEquals(Weight.Pinned, Salience.weigh(ctx(one))[Field.Label("x")])

        val many = Filter.Not(Filter.AnyOf(listOf(Filter.HasLabel("urgent"), Filter.HasLabel("blocked"))))
        val w = Salience.weigh(ctx(many))
        assertEquals(Weight.Pinned, w[Field.Label("urgent")])
        assertEquals(Weight.Pinned, w[Field.Label("blocked")])
        assertTrue(Salience.grammar(ctx(many)).branched.isEmpty())
    }

    /**
     * Priority is the enclosure around the task glyph, never a word on the meta line.
     *
     * The row draws it from the unfiltered chips, so an engine that pinned it would be telling the
     * line to stay quiet about something the checkbox is already shouting.
     */
    @Test
    fun `priority is the glyph's business and the engine has no opinion on it`() {
        val f = Filter.All(listOf(Filter.Prop(BuiltIns.PRIORITY_DEF_ID, Op.EQ, text = "P1")))
        val priority = Field.Prop(BuiltIns.PRIORITY_DEF_ID)
        val g = Salience.grammar(ctx(f))
        assertNull(Salience.weigh(ctx(f))[priority])
        assertTrue(priority !in g.pinned)
        assertTrue(priority !in g.subLine)
    }

    /**
     * Labels never reach the sub-line — the row already holds the whole set — so [RowGrammar.branched]
     * is the only way a row can learn which tag is the reason it is on this screen.
     */
    @Test
    fun `the matched tags are reported even though they never reach the sub-line`() {
        val f = Filter.AnyOf(listOf(Filter.HasLabel("urgent"), Filter.HasLabel("blocked")))
        val g = Salience.grammar(ctx(f))
        assertTrue(Field.Label("urgent") in g.branched)
        assertTrue(Field.Label("blocked") in g.branched)
        assertTrue("a tag is never a sub-line field", g.subLine.none { it is Field.Label })
    }
}
