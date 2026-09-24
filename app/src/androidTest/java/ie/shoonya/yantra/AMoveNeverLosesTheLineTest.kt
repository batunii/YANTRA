package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A line is written where it is going before it is taken from where it was.
 *
 * **The loss this exists for.** Moving a node across workspaces copied its files, took the line out
 * of the source, put it on the destination, then deleted the source files. The note beside it said
 * a failure in the middle leaves two copies rather than none — true of a node with a *page*, since
 * `adopt` copies those first, and false of a node that is only a line. A meeting tapped off a
 * calendar is exactly that: nothing to adopt, so the take deleted the only copy, and anything that
 * stopped the put lost it outright. One did — an event node created at 21:30 was in neither list by
 * 21:43, and the second tap on the same meeting made a fresh node because the first was gone.
 *
 * `peekLine`/`dropLine` exist so the copy can land first. The order is the fix; these hold it.
 */
@RunWith(AndroidJUnit4::class)
class AMoveNeverLosesTheLineTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore
    private lateinit var writer: WorkspaceWriter

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "move-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = WorkspaceStore(root).also { it.scaffold("test", 1_787_000_000_000L) }
        writer = WorkspaceWriter(store, db, Indexer(db), device = "test-device")
        writer.reindex()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun peekingLeavesTheLineWhereItIs() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "Inbox")
        val task = writer.addBlock(list, NodeType.TASK, "Ring the bank")

        assertNotNull("the line can be read", writer.peekLine(task))
        assertNotNull("and is still there afterwards", writer.peekLine(task))
        assertEquals("Ring the bank", db.nodeDao().byId(task)?.title)
    }

    @Test
    fun droppingRemovesItOnlyWhenAsked() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "Inbox")
        val task = writer.addBlock(list, NodeType.TASK, "Ring the bank")

        assertTrue("there was a line to remove", writer.dropLine(task))
        assertEquals("and now there is not", null, writer.peekLine(task))
        assertTrue("removing it twice is not an error", !writer.dropLine(task))
    }

    @Test
    fun aDestinationThatWillNotTakeItLeavesTheOriginalAlone() = runBlocking {
        // The case that lost the event: the put fails. Nothing may be removed on the strength of a
        // write that did not happen.
        val list = writer.createTopLevel(NodeType.LIST, "Inbox")
        val task = writer.addBlock(list, NodeType.TASK, "Ring the bank")
        val line = writer.peekLine(task)!!

        val landed = writer.putLine(line, parentId = "no-such-page", nodeId = task)

        assertTrue("it reports that it did not land", !landed)
        assertNotNull("so the original is untouched", writer.peekLine(task))
        assertEquals("Ring the bank", db.nodeDao().byId(task)?.title)
    }

    @Test
    fun aGoodMoveEndsWithExactlyOneCopy() = runBlocking {
        val from = writer.createTopLevel(NodeType.LIST, "Inbox")
        val to = writer.createTopLevel(NodeType.LIST, "Later")
        val task = writer.addBlock(from, NodeType.TASK, "Ring the bank")

        val line = writer.peekLine(task)!!
        assertTrue(writer.putLine(line, to, task))
        writer.dropLine(task)

        assertEquals("it lives under its new home", to, db.nodeDao().byId(task)?.parentId)
        assertEquals("and nowhere else", 1, db.nodeDao().childrenOnce(to).count { it.id == task })
        assertEquals("the old list has let it go", 0, db.nodeDao().childrenOnce(from).count { it.id == task })
    }
}
