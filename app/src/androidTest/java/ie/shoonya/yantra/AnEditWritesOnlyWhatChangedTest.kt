package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.db.SystemKey
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **A keystroke writes the rows it changed, and nothing else.**
 *
 * The index used to be refilled wholesale whenever any node changed: every node in the workspace
 * deleted and inserted again, `node_label` and `event` rewritten because they cascade from it, and
 * `property_value` rewritten because every value on the edited page carried the page's new
 * modified time. Room invalidates per table, so one renamed task woke every flow in the app that
 * watched any of the four.
 *
 * Two properties, both needed:
 *  - **Same result.** After any run of edits, the rows are exactly what a from-scratch rebuild of
 *    the same files produces — the index is still a pure function of the working tree.
 *  - **Less written.** Renaming a task invalidates `node` and leaves the tables it did not change
 *    alone.
 */
@RunWith(AndroidJUnit4::class)
class AnEditWritesOnlyWhatChangedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore
    private lateinit var indexer: Indexer
    private lateinit var writer: WorkspaceWriter

    private val watched = arrayOf("node", "property_value", "node_label", "event", "label")

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "diff-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = WorkspaceStore(root).also { it.scaffold("diff", 1_787_000_000_000L) }
        indexer = Indexer(db)
        // No scope: every write rebuilds inline, so the rows are current when the call returns.
        writer = WorkspaceWriter(store, db, indexer, device = "test")
        indexer.rebuild(store)
        // Written by SQLite itself, in the same statement as the change — unlike Room's
        // invalidation callbacks, which arrive later on another thread and would blur one write
        // into the next.
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TEMP TABLE write_log (tbl TEXT NOT NULL)")
        for (t in watched) for (op in listOf("INSERT", "UPDATE", "DELETE")) {
            sql.execSQL(
                "CREATE TEMP TRIGGER log_${t}_$op AFTER $op ON main.$t " +
                    "BEGIN INSERT INTO write_log VALUES ('$t'); END"
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    /** Which watched tables had a row inserted, updated or deleted while [block] ran. */
    private suspend fun tablesTouchedBy(block: suspend () -> Unit): Set<String> {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM write_log")
        block()
        return sql.query("SELECT DISTINCT tbl FROM write_log").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    /** Every row of the four diffed tables, as text, in a stable order. */
    private fun dump(): List<String> {
        val queries = listOf(
            "SELECT * FROM node ORDER BY id",
            // updated_at is the page's modified time; unread, and deliberately not rewritten alone.
            "SELECT node_id, def_id, workspace_id, v_text, v_number, v_reminders, v_date, v_bool, v_duration_min FROM property_value ORDER BY node_id, def_id",
            "SELECT * FROM node_label ORDER BY node_id, label_id",
            "SELECT * FROM event ORDER BY node_id",
            "SELECT * FROM label ORDER BY id",
        )
        return queries.flatMap { sql ->
            db.openHelper.readableDatabase.query(sql).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "∅" })
                    }
                }
            }
        }
    }

    /** The rows a rebuild with no memory writes for the same files. */
    private suspend fun fromScratch(): List<String> {
        indexer.forget(store.id)
        indexer.rebuild(store)
        return dump()
    }

    @Test
    fun aRunOfEditsLeavesExactlyWhatARebuildWould() = runBlocking {
        val inbox = writer.createTopLevel(NodeType.LIST, "Inbox", systemKey = SystemKey.INBOX)
        val errands = writer.createTopLevel(NodeType.LIST, "Errands")
        val milk = writer.addBlock(inbox, NodeType.TASK, "milk")
        val bread = writer.addBlock(inbox, NodeType.TASK, "bread")
        val eggs = writer.addBlock(errands, NodeType.TASK, "eggs")

        writer.editTask(milk) { it.copy(labels = listOf("shop"), priority = "High") }
        writer.editTask(bread) { it.copy(title = "sourdough") }
        writer.editTask(eggs) { it.copy(labels = listOf("shop", "fridge")) }
        writer.editTask(milk) { it.copy(labels = listOf("fridge"), priority = null) }
        writer.removeBlock(bread)
        writer.reparent(eggs, inbox)
        writer.addBlock(errands, NodeType.TASK, "stamps")

        val incremental = dump()
        assertEquals("the diffed rows drifted from the files", fromScratch(), incremental)
    }

    @Test
    fun renamingATaskTouchesOnlyTheNodeTable() = runBlocking {
        val inbox = writer.createTopLevel(NodeType.LIST, "Inbox", systemKey = SystemKey.INBOX)
        val task = writer.addBlock(inbox, NodeType.TASK, "milk")
        writer.editTask(task) { it.copy(labels = listOf("shop"), priority = "High") }

        val touched = tablesTouchedBy { writer.editTask(task) { it.copy(title = "oat milk") } }

        assertTrue("the rename itself must reach node: $touched", "node" in touched)
        assertFalse("tags did not change: $touched", "node_label" in touched)
        assertFalse("the priority did not change: $touched", "property_value" in touched)
        assertFalse("no meeting was involved: $touched", "event" in touched)
        assertEquals(fromScratch(), dump())
    }

    @Test
    fun aRenameKeepsTheTaskItsTags() = runBlocking {
        val inbox = writer.createTopLevel(NodeType.LIST, "Inbox", systemKey = SystemKey.INBOX)
        val task = writer.addBlock(inbox, NodeType.TASK, "milk")
        writer.editTask(task) { it.copy(labels = listOf("shop")) }
        val before = db.labelDao().allNodeLabels().first().filter { it.nodeId == task }

        writer.editTask(task) { it.copy(title = "oat milk") }

        val after = db.labelDao().allNodeLabels().first().filter { it.nodeId == task }
        assertEquals(1, before.size)
        assertEquals(before, after)
    }

    @Test
    fun aSystemKeyThatMovesToANewRowDoesNotCollide() = runBlocking {
        val first = writer.createTopLevel(NodeType.LIST, "Inbox", systemKey = SystemKey.INBOX)
        writer.addBlock(first, NodeType.TASK, "milk")
        // A second Inbox arriving — what a pull or a second device produces. The reconciler settles
        // which one holds the key; whichever it picks, the write must not trip the unique index.
        val second = writer.createTopLevel(NodeType.LIST, "Inbox", systemKey = SystemKey.INBOX)
        writer.removeBlock(first)

        assertEquals(fromScratch(), dump())
        assertEquals(second, db.nodeDao().bySystemKey(SystemKey.INBOX)?.id)
    }
}
