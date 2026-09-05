package ie.napkin.supertasks

import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterCompiler
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.filter.workspacesNamed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a smart list may see, and whether it can say so.
 *
 * Smart lists live in Personal and are no longer fenced to the repo their file sits in, so reach is
 * something the rule states rather than something its directory decides. These pin both halves:
 * that a rule naming nothing spans everything, and that a rule naming a workspace can be asked
 * which ones — which is the only way a view can warn that it is answering partially.
 */
class SmartListScopeTest {

    @Test fun `a rule naming no workspace is not fenced to one`() {
        val q = FilterCompiler.compile(null, Filter.Done(false), workspaceId = null)
        assertFalse("the rule was fenced to a workspace: ${q.sql}", q.sql.contains("n.workspace_id"))
    }

    @Test fun `a rule naming one workspace asks the column`() {
        val q = FilterCompiler.compile(null, Filter.InWorkspace("ws-work"), workspaceId = null)
        assertTrue(q.sql.contains("n.workspace_id = ?"))
        assertEquals(listOf<Any>("ws-work"), q.args)
    }

    @Test fun `workspaces are collected through every nesting a rule can have`() {
        val f = Filter.All(
            listOf(
                Filter.Done(false),
                Filter.AnyOf(listOf(Filter.InWorkspace("a"), Filter.InWorkspace("b"))),
                Filter.Not(Filter.InWorkspace("c")),
            )
        )
        assertEquals(setOf("a", "b", "c"), f.workspacesNamed())
    }

    @Test fun `a rule about no workspace in particular names none`() {
        assertEquals(emptySet<String>(), Filter.All(listOf(Filter.Done(false))).workspacesNamed())
    }

    /** The definition is what syncs, so the reach has to survive the round trip. */
    @Test fun `a workspace clause survives serialisation`() {
        val f: Filter = Filter.All(listOf(Filter.Done(false), Filter.InWorkspace("ws-work")))
        val json = FilterJson.encodeToString(Filter.serializer(), f)
        assertTrue(json.contains("in_workspace"))
        assertEquals(f, FilterJson.decodeFromString(Filter.serializer(), json))
        assertEquals(setOf("ws-work"), FilterJson.decodeFromString(Filter.serializer(), json).workspacesNamed())
    }
}
