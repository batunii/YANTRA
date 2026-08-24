package ie.napkin.supertasks

import ie.napkin.supertasks.data.filter.ApplyOnCreate
import ie.napkin.supertasks.data.filter.DateRel
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterCompiler
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.filter.Op
import ie.napkin.supertasks.data.filter.SortBy
import ie.napkin.supertasks.data.filter.SortSpec
import ie.napkin.supertasks.data.filter.deriveApplyOnCreate
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCompilerTest {

    private val filter = Filter.All(
        listOf(
            Filter.Type("task"),
            Filter.Done(false),
            Filter.Prop(defId = "pri", op = Op.EQ, text = "High"),
        )
    )

    @Test
    fun `workspace scope drops the recursive CTE`() {
        val q = FilterCompiler.compile(null, filter)
        assertFalse(q.sql.contains("WITH RECURSIVE"))
        assertTrue(q.sql.contains("FROM node n"))
    }

    @Test
    fun `scoped query uses the recursive CTE and binds the root`() {
        val q = FilterCompiler.compile("root-1", filter)
        assertTrue(q.sql.contains("WITH RECURSIVE subtree"))
        assertEquals("root-1", q.args.first())
    }

    @Test
    fun `prop clauses become EXISTS subqueries with typed columns`() {
        val q = FilterCompiler.compile(null, filter)
        assertTrue(q.sql.contains("EXISTS (SELECT 1 FROM property_value pv"))
        assertTrue(q.sql.contains("pv.v_text = ?"))
        assertTrue(q.args.containsAll(listOf("pri", "High")))
    }

    @Test
    fun `args order matches placeholder order`() {
        val q = FilterCompiler.compile(
            "root-9",
            filter,
            sort = listOf(SortSpec(by = SortBy.PROP_DATE, defId = "due")),
        )
        assertEquals(q.sql.count { it == '?' }, q.args.size)
        assertEquals("root-9", q.args[0])
        // where args before sort args
        assertTrue(q.args.indexOf("High") < q.args.indexOf("due"))
    }

    @Test
    fun `dateRel resolves against provided now`() {
        val now = 1_750_000_000_000L
        val q = FilterCompiler.compile(
            null,
            Filter.Prop(defId = "due", op = Op.LTE, dateRel = DateRel.TODAY_END),
            nowMillis = now,
        )
        val bound = q.args.last() as Long
        // end of local "today" is within 24h+ of now, and after now's midnight
        assertTrue(bound in (now - 24L * 3600 * 1000)..(now + 24L * 3600 * 1000))
    }

    @Test
    fun `not and any compose`() {
        val f = Filter.AnyOf(
            listOf(
                Filter.Not(Filter.Prop(defId = "p", op = Op.IS_SET)),
                Filter.Prop(defId = "n", op = Op.GT, number = 3.0),
            )
        )
        val q = FilterCompiler.compile(null, f)
        assertTrue(q.sql.contains("NOT ("))
        assertTrue(q.sql.contains(" OR "))
        assertTrue(q.sql.contains("pv.v_number > ?"))
    }

    @Test
    fun `filter roundtrips through json`() {
        val json = FilterJson.encodeToString(Filter.serializer(), filter)
        val back = FilterJson.decodeFromString(Filter.serializer(), json)
        assertEquals(filter, back)
    }

    @Test
    fun `apply_on_create derives EQ clauses and today-relative date clauses`() {
        val f = Filter.All(
            listOf(
                Filter.Done(false),
                Filter.Prop(defId = "pri", op = Op.EQ, text = "High"),
                Filter.Prop(defId = "due", op = Op.LTE, dateRel = DateRel.TODAY_END),
            )
        )
        val apply = deriveApplyOnCreate(f)
        assertEquals(2, apply.size)
        assertEquals("pri", apply[0].defId)
        assertEquals("High", apply[0].text)
        // "due today or earlier" is satisfied by Due = today — deferred via dateRel
        assertEquals("due", apply[1].defId)
        assertEquals(DateRel.TODAY_START, apply[1].dateRel)
        assertEquals(false, apply[1].bool)
        assertEquals(null, apply[1].date)
    }

    @Test
    fun `apply_on_create takes the first derivable AnyOf branch`() {
        val f = Filter.AnyOf(
            listOf(
                Filter.Prop(defId = "due", op = Op.LTE, dateRel = DateRel.TODAY_END),
                Filter.Prop(defId = "deadline", op = Op.LTE, dateRel = DateRel.TODAY_END),
            )
        )
        val apply = deriveApplyOnCreate(f)
        assertEquals(1, apply.size)
        assertEquals("due", apply[0].defId)
    }

    @Test
    fun `apply_on_create ignores non-derivable comparisons`() {
        val f = Filter.All(
            listOf(
                Filter.Prop(defId = "due", op = Op.LT, dateRel = DateRel.TODAY_START),
                Filter.Prop(defId = "pri", op = Op.NEQ, text = "Low"),
            )
        )
        assertEquals(0, deriveApplyOnCreate(f).size)
    }

    @Test
    fun `apply_on_create with dateRel roundtrips through json`() {
        val apply = listOf(ApplyOnCreate(defId = "due", bool = false, dateRel = DateRel.TODAY_START))
        val json = FilterJson.encodeToString(ListSerializer(ApplyOnCreate.serializer()), apply)
        val back = FilterJson.decodeFromString(ListSerializer(ApplyOnCreate.serializer()), json)
        assertEquals(apply, back)
    }

    @Test
    fun `today filter with deadline disjunct compiles to OR of two EXISTS`() {
        val f = Filter.All(
            listOf(
                Filter.Type("task"),
                Filter.Done(false),
                Filter.AnyOf(
                    listOf(
                        Filter.Prop(defId = "due", op = Op.LTE, dateRel = DateRel.TODAY_END),
                        Filter.Prop(defId = "deadline", op = Op.LTE, dateRel = DateRel.TODAY_END),
                    )
                ),
            )
        )
        val q = FilterCompiler.compile(null, f)
        assertTrue(q.sql.contains(" OR "))
        assertEquals(2, Regex("EXISTS \\(SELECT 1 FROM property_value pv").findAll(q.sql).count())
        assertTrue(q.args.indexOf("due") < q.args.indexOf("deadline"))
    }

    @Test
    fun `sort nulls last emits IS NULL guard first`() {
        val q = FilterCompiler.compile(
            null, null,
            sort = listOf(SortSpec(by = SortBy.PROP_DATE, defId = "due", nullsLast = true)),
        )
        val orderBy = q.sql.substringAfter("ORDER BY")
        assertTrue(orderBy.contains("IS NULL"))
    }
}
