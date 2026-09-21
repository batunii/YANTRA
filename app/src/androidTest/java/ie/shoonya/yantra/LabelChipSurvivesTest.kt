package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.LabelDef
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A tag stays on its tasks when the tag itself is edited.
 *
 * **The bug this exists for, and why it is the second time.** The indexer skips a table whose rows
 * are identical to last time. `node_label` has *two* foreign keys with `ON DELETE CASCADE` — one to
 * `node` and one to `label` — so either parent being cleared takes its rows with it, and the skip
 * then declines to write them back. [EventRowSurvivesTest] covers the `node` edge. This covers the
 * `label` edge, which was missed when that one was fixed: the comment there said "hang off `node`",
 * the fix OR'd in `nodesChanged`, and the second key went unmentioned.
 *
 * On a phone it read as: pick a new colour for one tag, and every chip on every task disappears at
 * once. Recolouring changes `label` and nothing else, so `linksChanged` was false, every attachment
 * in the workspace was cascaded away, and none came back. Permanent, because the indexer's memory
 * had already recorded them as written — until the process restarted and rebuilt from the files,
 * which is the only reason the tags were never actually lost.
 *
 * Every test here edits *the label* and then asks whether the attachment is still there, because
 * that is the whole of the failure.
 */
@RunWith(AndroidJUnit4::class)
class LabelChipSurvivesTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore
    private lateinit var indexer: Indexer
    private lateinit var writer: WorkspaceWriter

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "labelchip-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = WorkspaceStore(root).also { it.scaffold("test", 1_787_000_000_000L) }
        // One indexer for the life of the test, deliberately: the skip it does is remembered across
        // rebuilds, and a fresh one per write would have no memory to be wrong about.
        indexer = Indexer(db)
        writer = WorkspaceWriter(store, db, indexer, device = "test-device")
        writer.reindex()
    }

    @After
    fun tearDown() = db.close()

    /** A task wearing one tag, as typing `#projects` on a line produces. */
    private suspend fun aTaggedTask(): String {
        val page = writer.createTopLevel(NodeType.LIST, "Inbox")
        val task = writer.addBlock(page, NodeType.TASK, "Ship the deck")
        writer.editTask(task) { it.copy(labels = listOf("projects")) }
        return task
    }

    private suspend fun chipsOn(nodeId: String) = db.labelDao().forNode(nodeId).first()

    private suspend fun theLabel() =
        db.labelDao().byName("projects") ?: error("the tag was never registered")

    @Test
    fun `a tag is attached as soon as it is typed`() = runBlocking {
        val task = aTaggedTask()
        assertEquals("the line carries the tag, so the chip is there", 1, chipsOn(task).size)
    }

    @Test
    fun `recolouring a tag does not take it off the task`() = runBlocking {
        val task = aTaggedTask()
        val label = theLabel()

        // The gesture: one tap on a swatch. It changes `label` and nothing else — which is exactly
        // the case the skip got wrong.
        writer.upsertLabel(LabelDef(id = label.id, name = label.name, color = 0xFF3D88B8))

        assertEquals("the chip vanished from the task", 1, chipsOn(task).size)
        assertEquals(0xFF3D88B8, theLabel().color)
    }

    @Test
    fun `it survives every later recolour too and not just the first`() = runBlocking {
        val task = aTaggedTask()
        // The failure was sticky: once the rows were cascaded away the indexer's memory insisted
        // they were written, so no later rebuild put them back. Repetition is the point.
        listOf(0xFF5D8F52, 0xFF00948E, 0xFF8075BA, 0xFFA66799).forEachIndexed { i, colour ->
            val label = theLabel()
            writer.upsertLabel(LabelDef(id = label.id, name = label.name, color = colour))
            assertEquals("gone after ${i + 1} recolours", 1, chipsOn(task).size)
        }
    }

    @Test
    fun `clearing the colour of a tag does not take it off either`() = runBlocking {
        val task = aTaggedTask()
        val label = theLabel()
        writer.upsertLabel(LabelDef(id = label.id, name = label.name, color = 0xFF3D88B8))
        // Back to the neutral chip, which is a different write and the same cascade.
        writer.upsertLabel(LabelDef(id = label.id, name = label.name, color = null))

        assertEquals(1, chipsOn(task).size)
    }

    /**
     * A second tag's colour is not the first tag's business.
     *
     * The clear is per workspace, not per label, so one swatch tap took every attachment in the
     * repo — including tasks the edited tag had never been near.
     */
    @Test
    fun `recolouring one tag leaves the tasks of another tag alone`() = runBlocking {
        val first = aTaggedTask()
        val page = writer.createTopLevel(NodeType.LIST, "Work")
        val second = writer.addBlock(page, NodeType.TASK, "Write the memo")
        writer.editTask(second) { it.copy(labels = listOf("writing")) }

        val other = db.labelDao().byName("writing") ?: error("the second tag was never registered")
        writer.upsertLabel(LabelDef(id = other.id, name = other.name, color = 0xFFA66799))

        assertEquals("the untouched tag lost its chip", 1, chipsOn(first).size)
        assertEquals(1, chipsOn(second).size)
        assertTrue("both tags are still registered", db.labelDao().allOnce().size >= 2)
    }
}
