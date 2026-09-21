package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every `ON DELETE CASCADE` in the schema is one the indexer knows about.
 *
 * **Why this is a test and not a comment.** [ie.shoonya.yantra.data.workspace.Indexer] skips
 * rewriting a table whose rows are identical to last time — the optimisation that stops a keystroke
 * waking every flow in the app. A cascading foreign key is the one thing that can empty such a table
 * behind its back: the parent is cleared, SQLite takes the child's rows with it, and the skip then
 * declines to write them back. `PRAGMA defer_foreign_keys` postpones the *check*, not the *action*.
 * Worse, the indexer's memo has already recorded those rows as written, so no later rebuild
 * disagrees and the loss is permanent until the process restarts.
 *
 * It has happened twice, and the second time is why this exists. The first was `event` losing its
 * row when anything on the page was typed, which read on a phone as a meeting's header vanishing
 * mid-sentence. It was fixed by OR-ing `nodesChanged` into `eventsChanged` and `linksChanged` — and
 * the fix was written against `node`, the parent in front of us, rather than against the rule. The
 * comment left behind said `node_label` "hangs off `node`". It hangs off `label` as well, nobody
 * noticed, and recolouring a single tag went on clearing every tag from every task in the workspace
 * for as long as that second edge stayed unguarded.
 *
 * So the thing worth guarding is not either table. It is the *shape*: a cascading edge whose parent
 * the indexer clears, and whose child the indexer may skip. This reads the edges out of SQLite
 * itself, which cannot be out of date with the schema the way a comment can, and fails the moment
 * one appears that nobody has thought about.
 *
 * **What to do when this fails.** Adding a cascading key is not forbidden — it is a decision that
 * costs two lines. In `Indexer.apply()`, OR the parent's `…Changed` flag into the child's, exactly
 * as `linksChanged` does for both of its parents. Then write a behavioural test for the edge, like
 * [EventRowSurvivesTest] and [LabelChipSurvivesTest]: change *only the parent*, and assert the
 * child's rows are still there. Then add the edge below. This test proves nobody forgot; those two
 * prove the guard works.
 */
@RunWith(AndroidJUnit4::class)
class CascadeEdgesAreGuardedTest {

    /**
     * The cascading edges this schema has, and the flag in `Indexer.apply()` that guards each.
     *
     * Written `child.column -> parent` because that is how `PRAGMA foreign_key_list` reports it, so
     * a failure can be read straight against the assertion without translating anything.
     */
    private val guarded = mapOf(
        // `linksChanged = nodesChanged || labelsChanged || …`
        "node_label.node_id -> node" to "linksChanged, via nodesChanged",
        "node_label.label_id -> label" to "linksChanged, via labelsChanged",
        // `eventsChanged = nodesChanged || …`
        "event.node_id -> node" to "eventsChanged, via nodesChanged",
    )

    private lateinit var db: AppDatabase
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        // Room creates the tables lazily, on the first request for a database.
        db.openHelper.readableDatabase
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun everyCascadingForeignKeyIsOneTheIndexerGuards() {
        val found = cascadeEdges()

        assertEquals(
            "The schema's cascading foreign keys are not the ones the indexer was written for.\n" +
                "Anything listed under 'found' and not under 'guarded' can have its rows deleted\n" +
                "by a parent wipe and then skipped by the indexer, which loses them until the\n" +
                "process restarts. See this test's KDoc for the two lines that fix it.\n",
            guarded.keys.sorted(),
            found.sorted(),
        )
    }

    /** Asked of SQLite rather than of the entity classes, so it cannot drift from what shipped. */
    private fun cascadeEdges(): List<String> {
        val sqlite = db.openHelper.readableDatabase
        val tables = mutableListOf<String>()
        sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%'"
        ).use { c -> while (c.moveToNext()) tables += c.getString(0) }

        val edges = mutableListOf<String>()
        tables.forEach { table ->
            sqlite.query("PRAGMA foreign_key_list(`$table`)").use { c ->
                val parent = c.getColumnIndex("table")
                val from = c.getColumnIndex("from")
                val onDelete = c.getColumnIndex("on_delete")
                while (c.moveToNext()) {
                    if (!c.getString(onDelete).equals("CASCADE", ignoreCase = true)) continue
                    edges += "$table.${c.getString(from)} -> ${c.getString(parent)}"
                }
            }
        }
        return edges
    }
}
