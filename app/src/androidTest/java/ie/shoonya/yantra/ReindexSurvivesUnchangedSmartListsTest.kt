package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.FocusSessionEntity
import ie.shoonya.yantra.data.db.InkStrokeEntity
import ie.shoonya.yantra.data.db.NodeEntity
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.db.PropertyDefEntity
import ie.shoonya.yantra.data.db.PropertyValueEntity
import ie.shoonya.yantra.data.db.SmartListDefEntity
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceIndex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A rebuild that leaves the smart lists alone still succeeds.
 *
 * **The bug this exists for.** [Indexer.apply] skips rewriting a table whose rows are identical to
 * last time, and it wipes `node` whenever nodes changed. A dependent that is *not* being rewritten
 * keeps its rows while `node` is emptied underneath them, which SQLite refuses — so the indexer
 * asks for `PRAGMA defer_foreign_keys` in exactly that case, and the check then holds again at the
 * end of the transaction because the same node ids are back.
 *
 * The condition that decides it listed five of the six tables with a foreign key onto `node`.
 * `smart_list_def` was missing, and it is the one dependent an ordinary day leaves untouched: you
 * edit tasks, so nodes, values, ink and focus all move, while the smart lists themselves sit still.
 * With every other dependent rewritten the condition came out `false`, nothing was deferred, and
 * `clearNodes()` deleted rows that `smart_list_def` still pointed at.
 *
 * On a phone it read as a toast — `Not synced: FOREIGN KEY constraint failed (code 787
 * SQLITE_CONSTRAINT_FOREIGNKEY[787])` — and nothing reaching GitHub, because the throw takes the
 * whole transaction down and with it the rebuild the sync was waiting on. Note which way this one
 * fails: the cascading edges in [CascadeEdgesAreGuardedTest] lose rows *silently*, whereas a key
 * with no `ON DELETE` action refuses out loud. Same skip, opposite symptom, which is why that test
 * could not have caught this.
 */
@RunWith(AndroidJUnit4::class)
class ReindexSurvivesUnchangedSmartListsTest {

    private lateinit var db: AppDatabase
    private lateinit var indexer: Indexer

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ws = "test"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        db.openHelper.readableDatabase
        // One indexer across both rebuilds, deliberately: the skip is remembered between them, and
        // a fresh one per call would have no memory to be wrong about.
        indexer = Indexer(db)
    }

    @After
    fun tearDown() = db.close()

    /**
     * Every table with a foreign key onto `node`, and the flag in `Indexer.apply()` that has to
     * name it in `leavingDependents`.
     *
     * Asked of SQLite rather than of the entity classes, so it cannot drift from what shipped. A
     * new dependent table fails this until somebody decides which flag rewrites it — which is the
     * step that was skipped when `smart_list_def` was added.
     */
    private val dependents = mapOf(
        "property_value" to "valuesChanged",
        "node_label" to "linksChanged",
        "ink_stroke" to "inkChanged",
        "focus_session" to "focusChanged",
        "event" to "eventsChanged",
        "smart_list_def" to "smartChanged",
    )

    @Test
    fun everyTableThatPointsAtNodeIsOneTheRebuildAccountsFor() {
        assertEquals(
            "The tables with a foreign key onto `node` are not the ones `leavingDependents` was\n" +
                "written for. Anything found here and missing there keeps its rows while `node` is\n" +
                "emptied and nothing defers the check, so the rebuild dies on a FOREIGN KEY\n" +
                "constraint and takes the sync with it.\n",
            dependents.keys.sorted(),
            tablesPointingAtNode().sorted(),
        )
    }

    @Test
    fun aRebuildThatChangesEverythingButTheSmartListsDoesNotFail() = runBlocking {
        indexer.apply(indexOf(title = "Write the deck", stamp = 1L), ws)

        // The gesture: you edit tasks. Nodes, values, ink and focus all move; the smart lists are
        // the same four they were. That combination is the whole bug — with anything else also
        // unchanged the indexer would have deferred the check and survived by luck.
        indexer.apply(indexOf(title = "Write the deck again", stamp = 2L), ws)

        assertEquals("the smart list is still indexed", 1, smartListCount())
        assertEquals("and the nodes came back", 2, nodeCount())
    }

    @Test
    fun itSurvivesEveryLaterRebuildAndNotJustTheFirst() = runBlocking {
        indexer.apply(indexOf(title = "Write the deck", stamp = 1L), ws)
        // Repetition is the point: the memo records what it believes it wrote, so a rebuild that
        // survives once is not evidence the next one does.
        repeat(4) { i ->
            indexer.apply(indexOf(title = "edit $i", stamp = i + 2L), ws)
            assertEquals("lost after ${i + 1} edits", 1, smartListCount())
        }
    }

    /**
     * One smart list and one task, where only the task moves.
     *
     * [stamp] is what makes values, ink and focus differ between two calls while `smartLists` stays
     * equal — the indexer compares the row lists by value, so a changed timestamp is a changed
     * table.
     */
    private fun indexOf(title: String, stamp: Long): WorkspaceIndex {
        val smart = "smart-node"
        val task = "task-node"
        return WorkspaceIndex(
            nodes = listOf(
                NodeEntity(
                    id = smart, workspaceId = ws, parentId = null, type = NodeType.SMART_LIST,
                    title = "Today", rank = "a0", createdAt = 1L, updatedAt = 1L,
                ),
                NodeEntity(
                    id = task, workspaceId = ws, parentId = null, type = NodeType.TASK,
                    title = title, rank = "a1", createdAt = 1L, updatedAt = stamp,
                ),
            ),
            defs = listOf(
                PropertyDefEntity(
                    id = "def-priority", name = "Priority", kind = "select", config = null,
                    createdAt = 1L, updatedAt = 1L,
                ),
            ),
            values = listOf(
                PropertyValueEntity(
                    nodeId = task, defId = "def-priority", workspaceId = ws, vText = "High",
                    updatedAt = stamp,
                ),
            ),
            ink = listOf(
                InkStrokeEntity(
                    id = "stroke-1", workspaceId = ws, nodeId = task, data = ByteArray(1),
                    rank = "a0", createdAt = 1L, updatedAt = stamp,
                ),
            ),
            focus = listOf(
                FocusSessionEntity(
                    id = "focus-1", workspaceId = ws, nodeId = task, startedAt = 1L,
                    plannedSecs = 1500, actualSecs = stamp.toInt(), createdAt = 1L,
                    updatedAt = stamp,
                ),
            ),
            // Byte for byte the same on every rebuild. That is the point of the test: this is the
            // table the indexer skips, and the one whose rows the node wipe then trips over.
            smartLists = listOf(
                SmartListDefEntity(
                    nodeId = smart, workspaceId = ws, scopeRootId = null,
                    filterJson = "{}", sortJson = null, homeParentId = null,
                    applyOnCreateJson = null,
                ),
            ),
        )
    }

    private fun tablesPointingAtNode(): List<String> {
        val sqlite = db.openHelper.readableDatabase
        val tables = mutableListOf<String>()
        sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%'"
        ).use { c -> while (c.moveToNext()) tables += c.getString(0) }

        // `node.parent_id -> node` is left out: a table cannot be a dependent of itself in the
        // sense that matters here. The wipe takes the whole table at once, so there is no moment
        // where a surviving row points at a deleted one, and the *insert* side of that edge is
        // handled by `inParentOrder()` sorting parents ahead of children rather than by deferral.
        return tables.filter { table ->
            table != "node" && sqlite.query("PRAGMA foreign_key_list(`$table`)").use { c ->
                val parent = c.getColumnIndex("table")
                var hit = false
                while (c.moveToNext()) if (c.getString(parent) == "node") hit = true
                hit
            }
        }
    }

    private fun smartListCount(): Int =
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM smart_list_def WHERE workspace_id = '$ws'")
            .use { it.moveToFirst(); it.getInt(0) }

    private fun nodeCount(): Int =
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM node WHERE workspace_id = '$ws'")
            .use { it.moveToFirst(); it.getInt(0) }
}
