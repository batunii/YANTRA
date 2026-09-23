package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.SmartListDef
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Two pages claiming one system key do not kill the rebuild — and so do not kill sync.
 *
 * **The bug this exists for.** `node` has a unique index on `(workspace_id, system_key)` and
 * `NodeDao.insertAll` is `OnConflictStrategy.REPLACE`. A workspace holding two `today` pages
 * therefore does not fail loudly on insert: SQLite *deletes* the first row to make room for the
 * second, halfway through the rebuild. Everything that pointed at the deleted node is then
 * dangling, and `smart_list_def` — whose key onto `node` takes no action on delete — refuses on
 * its own insert. The whole transaction dies with `FOREIGN KEY constraint failed (code 787)`.
 *
 * That transaction is the one sync waits on, so nothing is ever pushed again. On a phone it read
 * as a toast saying `Not synced: FOREIGN KEY constraint failed` and a repository that simply
 * stopped receiving commits, with no network or credential fault anywhere — the app was signed in,
 * the remote was reachable, and every push still failed.
 *
 * Duplicates are earned honestly: a fresh install scaffolds its own `today` and `inbox` before the
 * repository's copies have been pulled, and then the workspace has two of each. So this is a state
 * the reconciler has to survive, not one it can declare impossible.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateSystemKeySurvivesReindexTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore
    private lateinit var indexer: Indexer

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val older = "11111111-1111-4111-8111-111111111111"
    private val newer = "22222222-2222-4222-8222-222222222222"

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "dupkey-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = WorkspaceStore(root).also { it.scaffold("test", 1_787_000_000_000L) }
        indexer = Indexer(db)
    }

    @After
    fun tearDown() = db.close()

    /** A smart list page written straight to disk, which is how a duplicate actually arrives. */
    private fun writeSmartListPage(id: String, modifiedAt: String) {
        store.pageFile(id).writeText(
            """
            ---
            id: $id
            type: smart_list
            title: Today
            system_key: today
            modified_at: $modifiedAt
            ---

            """.trimIndent()
        )
        store.writeSmartList(SmartListDef(nodeId = id, filterJson = "{}"))
    }

    private fun bothTodayPages() {
        writeSmartListPage(older, "2026-09-21T16:01:27.480Z")
        writeSmartListPage(newer, "2026-09-21T20:25:18.901Z")
    }

    @Test
    fun aWorkspaceWithTwoTodayPagesStillRebuilds() = runBlocking {
        bothTodayPages()
        // The assertion is that this returns at all. Before the fix it threw
        // SQLiteConstraintException 787 from SmartListDao_Impl.insertAll.
        val problems = indexer.rebuild(store)
        assertTrue(
            "the clash is reported rather than swallowed, and names both pages:\n$problems",
            problems.any { it.contains("system key") && it.contains(older) && it.contains(newer) },
        )
    }

    @Test
    fun onlyTheNewerPageIsIndexedAndItKeepsItsSmartList() = runBlocking {
        bothTodayPages()
        indexer.rebuild(store)

        assertEquals("one `today`, not two", 1, countWhere("node", "system_key = 'today'"))
        assertEquals("and it is the newer page", 1, countWhere("node", "id = '$newer'"))
        // The def that survives is the surviving node's. A def for a node that is not there is the
        // row that refused in the first place, so the index must not contain one.
        assertEquals("one definition", 1, countWhere("smart_list_def", "1 = 1"))
        assertEquals("belonging to the kept page", 1, countWhere("smart_list_def", "node_id = '$newer'"))
    }

    @Test
    fun itKeepsRebuildingAfterwards() = runBlocking {
        bothTodayPages()
        // The indexer memoises what it wrote, so surviving once is not evidence the next one does.
        repeat(3) { indexer.rebuild(store) }
        assertEquals("still one `today`", 1, countWhere("node", "system_key = 'today'"))
    }

    private fun countWhere(table: String, where: String): Int =
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table WHERE $where")
            .use { it.moveToFirst(); it.getInt(0) }
}
