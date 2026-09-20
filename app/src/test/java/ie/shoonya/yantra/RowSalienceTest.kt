package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.BuiltIns
import ie.shoonya.yantra.data.filter.Field
import ie.shoonya.yantra.data.filter.Filter
import ie.shoonya.yantra.data.filter.Op
import ie.shoonya.yantra.data.filter.DateRel
import ie.shoonya.yantra.data.filter.Salience
import ie.shoonya.yantra.data.filter.ViewContext
import ie.shoonya.yantra.data.sync.Credentials
import ie.shoonya.yantra.ui.components.ChipData
import ie.shoonya.yantra.ui.components.ChipStatus
import ie.shoonya.yantra.ui.components.Expected
import ie.shoonya.yantra.ui.components.UNASSIGNED
import ie.shoonya.yantra.ui.components.planRow
import ie.shoonya.yantra.ui.components.resolve
import ie.shoonya.yantra.ui.components.spineHue
import ie.shoonya.yantra.ui.smart.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a row says once the grammar has spoken — the half of row salience that is not the walk.
 *
 * [planRow] is pure and knows nothing about Compose, which is the whole point: every decision about
 * *which* field a row carries is made here and tested here, and the draw site is left with spans.
 */
class RowSalienceTest {

    private val WS = "ws-a"

    private fun grammarFor(filter: Filter?, single: Boolean = false) =
        Salience.grammar(ViewContext(filter, single))

    /** A plain list page: no rule at all, so every field is Free. */
    private val listPage = grammarFor(null)

    /** Today, as the app seeds it. */
    private val today = grammarFor(
        Filter.All(
            listOf(
                Filter.AnyOf(
                    listOf(
                        Filter.Prop(BuiltIns.DUE_DEF_ID, Op.LTE, dateRel = DateRel.TODAY_END),
                        Filter.Prop(BuiltIns.DEADLINE_DEF_ID, Op.LTE, dateRel = DateRel.TODAY_END),
                    )
                ),
            )
        )
    )

    /**
     * Today as this device actually seeds it: one `due ≤ today`, not an OR of two dates.
     *
     * It matters because it ranks the **deadline** into the title slot — the deadline is Free and
     * the due date is only Bounded — which is the case that lost a row its deadline.
     */
    private val dueOnOrBeforeToday = grammarFor(
        Filter.All(
            listOf(Filter.Prop(BuiltIns.DUE_DEF_ID, Op.LTE, dateRel = DateRel.TODAY_END))
        )
    )

    private fun assignee(login: String, status: ChipStatus = ChipStatus.None) = ChipData(
        defId = BuiltIns.ASSIGNEE_DEF_ID,
        label = if (status == ChipStatus.Warn) "@$login · no access" else "@$login",
        color = null,
        status = status,
        raw = login,
    )

    private fun date(defId: String, label: String, status: ChipStatus = ChipStatus.None) =
        ChipData(defId = defId, label = label, color = null, status = status)

    private fun tag(id: String) =
        ChipData(defId = id, label = id, color = null, isLabel = true)

    private fun plan(
        grammar: ie.shoonya.yantra.data.filter.RowGrammar,
        chips: List<ChipData>,
        expected: Expected = Expected.NONE,
        origin: Origin? = null,
        done: Boolean = false,
        timing: Boolean = false,
    ) = planRow(grammar, expected, chips, origin, WS, done, timing)

    // ---- who the reader is ----

    @Test
    fun `my own name on my own task is the reader's name`() {
        val mine = Expected(mapOf(WS to "batunii"))
        val p = plan(listPage, listOf(assignee("batunii")), expected = mine)
        assertTrue("my login is not news to me", p.props.isEmpty())
    }

    @Test
    fun `the account login answers for a workspace that has none of its own`() {
        val fallback = Expected(mapOf(Credentials.ACCOUNT to "batunii"))
        assertTrue(plan(listPage, listOf(assignee("batunii")), expected = fallback).props.isEmpty())
        // …and case is not the difference between me and somebody else.
        assertTrue(plan(listPage, listOf(assignee("BatuNii")), expected = fallback).props.isEmpty())
    }

    @Test
    fun `somebody else's name is the whole row`() {
        val mine = Expected(mapOf(WS to "batunii"))
        // The motivating case: a list page, no dates, assigned to somebody else. Before the engine
        // this row said nothing at all.
        val p = plan(listPage, listOf(assignee("saieeshward")), expected = mine)
        assertEquals(listOf("@saieeshward"), p.props.map { it.label })
    }

    @Test
    fun `a view that is already about one person says nothing about the person`() {
        val assigned = grammarFor(
            Filter.All(listOf(Filter.Prop(BuiltIns.ASSIGNEE_DEF_ID, Op.EQ, text = "saieeshward")))
        )
        val p = plan(assigned, listOf(assignee("saieeshward")))
        assertTrue("the view already said it", p.props.none { it.defId == BuiltIns.ASSIGNEE_DEF_ID })
    }

    @Test
    fun `an off-roster assignee keeps its own words, verbatim`() {
        val mine = Expected(mapOf(WS to "batunii"))
        val p = plan(listPage, listOf(assignee("saieeshward", ChipStatus.Warn)), expected = mine)
        // The row re-words nothing: amber alone at 10sp is a colour with no name.
        assertEquals("@saieeshward · no access", p.props.single().label)
    }

    @Test
    fun `a finished task has no one to chase`() {
        val mine = Expected(mapOf(WS to "batunii"))
        val p = plan(listPage, listOf(assignee("saieeshward")), expected = mine, done = true)
        assertTrue(p.props.isEmpty())
    }

    // ---- the absence ----

    @Test
    fun `an unclaimed task is silent unless the view is asking who has it`() {
        // An ordinary view: nothing is said, and no room is reserved for saying it.
        assertFalse(plan(listPage, emptyList()).unassigned)

        // The view whose question *is* the absence.
        val unclaimed = grammarFor(
            Filter.AnyOf(
                listOf(
                    Filter.Prop(BuiltIns.ASSIGNEE_DEF_ID, Op.NOT_SET),
                    Filter.Prop(BuiltIns.DUE_DEF_ID, Op.IS_SET),
                )
            )
        )
        assertTrue(Field.Prop(BuiltIns.ASSIGNEE_DEF_ID) in unclaimed.branched)
        assertTrue(plan(unclaimed, emptyList()).unassigned)
        // …and only while it is still a question.
        assertFalse(plan(unclaimed, emptyList(), done = true).unassigned)
        // A task that *has* somebody is not an absence.
        assertFalse(plan(unclaimed, listOf(assignee("saieeshward"))).unassigned)
    }

    @Test
    fun `the absence is two characters and never the word`() {
        assertEquals("@?", UNASSIGNED)
    }

    // ---- the title slot ----

    @Test
    fun `the slot falls through to the date the task actually has`() {
        // Today names `due`; this task is here for its deadline and has no due date at all.
        val deadline = date(BuiltIns.DEADLINE_DEF_ID, "3d left")
        val p = plan(today, listOf(deadline))
        assertEquals(deadline, p.slot)
        assertTrue("printed once, structurally", deadline !in p.props)
    }

    @Test
    fun `a date the view already implied leaves the slot empty`() {
        val due = date(BuiltIns.DUE_DEF_ID, "Today", ChipStatus.Due)
        val deadline = date(BuiltIns.DEADLINE_DEF_ID, "3d left")
        val p = plan(today, listOf(due, deadline))
        assertNull("due IS today, which is what Today already said", p.slot)
        assertTrue("and it does not fall through to the line below", due !in p.props)
        // The deadline is different news, so it survives.
        assertTrue(deadline in p.props)
    }

    @Test
    fun `on a list page no date is ever silenced`() {
        val due = date(BuiltIns.DUE_DEF_ID, "Today", ChipStatus.Due)
        // Every date is Free here, so "today" is the one thing the page is scanned for.
        assertEquals(due, plan(listPage, listOf(due)).slot)
    }

    @Test
    fun `overdue is re-admitted over a rule that pinned it`() {
        val strict = grammarFor(
            Filter.All(listOf(Filter.Prop(BuiltIns.DUE_DEF_ID, Op.EQ, dateRel = DateRel.TODAY_START)))
        )
        assertTrue(Field.Prop(BuiltIns.DUE_DEF_ID) in strict.pinned)
        val late = date(BuiltIns.DUE_DEF_ID, "18 Sep · overdue", ChipStatus.Overdue)
        assertEquals("the world is asking, whatever the rule said", late, plan(strict, listOf(late)).slot)
    }

    /**
     * An override displaces a date; it does not delete it.
     *
     * Caught on a phone, not here: in Today an overdue task put its due date in the slot and its
     * **deadline vanished from the row** — consumed by a slot it never reached. The same task on
     * its own list page showed both, which is what made the difference visible.
     */
    @Test
    fun `a date an override displaced still has a line to go to`() {
        val late = date(BuiltIns.DUE_DEF_ID, "Yesterday · overdue", ChipStatus.Overdue)
        val deadline = date(BuiltIns.DEADLINE_DEF_ID, "9d left")

        // Both shapes of Today, because they rank the two dates differently and only one of them
        // puts the deadline in the slot the override then takes.
        for (grammar in listOf(today, dueOnOrBeforeToday)) {
            val p = plan(grammar, listOf(late, deadline))
            assertEquals(late, p.slot)
            assertTrue("the deadline is still news", deadline in p.props)
            assertTrue("and the date in the slot is not said twice", late !in p.props)
        }
    }

    @Test
    fun `a struck row is not chased`() {
        val late = date(BuiltIns.DUE_DEF_ID, "18 Sep · overdue", ChipStatus.Overdue)
        assertNull(plan(listPage, listOf(late), done = true).slot)
    }

    @Test
    fun `a timed row draws its elapsed count and not its date`() {
        val due = date(BuiltIns.DUE_DEF_ID, "Tomorrow")
        val p = plan(listPage, listOf(due), timing = true)
        assertNull("the trailing slot is the clock", p.slot)
        assertTrue("and the date does not reappear below it", due in p.consumed)
    }

    // ---- the tags ----

    @Test
    fun `the matched tag leads, and only by position`() {
        val branchy = grammarFor(
            Filter.AnyOf(listOf(Filter.HasLabel("urgent"), Filter.HasLabel("blocked")))
        )
        val chips = listOf(tag("someday"), tag("urgent"))
        assertEquals(listOf("urgent", "someday"), plan(branchy, chips).tags.map { it.label })
        // The same task, on a view with no opinion, keeps the order it was tagged in.
        assertEquals(listOf("someday", "urgent"), plan(listPage, chips).tags.map { it.label })
    }

    @Test
    fun `a tag every row here carries stops printing on every row`() {
        val pinned = grammarFor(Filter.All(listOf(Filter.HasLabel("work"))))
        val p = plan(pinned, listOf(tag("work"), tag("urgent")))
        assertEquals(listOf("urgent"), p.tags.map { it.label })
    }

    // ---- where a task lives ----

    @Test
    fun `the page you are standing in is not news`() {
        val origin = Origin(list = "Napkin Tasks", listHue = null, workspace = null, workspaceHue = null)
        // A list page has no rule, so its rule pins the list: the page you are standing in never
        // names itself, and nothing had to be told that it is the page you are standing in.
        assertNull(plan(listPage, emptyList(), origin = origin).place)
        // A smart list draws from many lists, so where a row lives is the news.
        assertEquals("Napkin Tasks", plan(today, emptyList(), origin = origin).place?.text)
    }

    // ---- the laws ----

    @Test
    fun `the workspace is a hue and never a word`() {
        val origin = Origin(list = "L", listHue = 1L, workspace = "Personal", workspaceHue = 2L)
        // Structurally, not conditionally: there is no grammar that makes this print a name.
        assertNull(resolve(Field.Workspace, listOf(assignee("x")), origin))
        assertNull(resolve(Field.Workspace, emptyList(), null))
    }

    @Test
    fun `a page never draws a stripe`() {
        val withSpine = grammarFor(Filter.AnyOf(listOf(Filter.InWorkspace("a"), Filter.InWorkspace("b"))))
        assertEquals(Field.Workspace, withSpine.spine)
        // `origin` is null on every node page, so the second gate closes on its own.
        assertNull(spineHue(withSpine, origin = null))

        val origin = Origin(list = "L", listHue = null, workspace = null, workspaceHue = 7L)
        assertEquals(7L, spineHue(withSpine, origin))
        // One repository open: the engine pins the workspace and the stripe goes, hue or no hue.
        assertNull(spineHue(grammarFor(null, single = true), origin))
    }
}
