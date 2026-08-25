package ie.napkin.supertasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.format.PageCodec
import ie.napkin.supertasks.data.format.TaskStatus
import ie.napkin.supertasks.data.workspace.Indexer
import ie.napkin.supertasks.data.workspace.WorkspaceReconciler
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import ie.napkin.supertasks.data.workspace.WorkspaceWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The write path, inverted.
 *
 * Every test here asserts the same two things about a mutation: that it reached the **file**, and
 * that the index Room ends up holding is the index a cold read of those files produces. The second
 * is what stops the index quietly becoming a place where things live — if a write reached Room but
 * not disk, a reindex would erase it, and the only way to notice is to check.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceWriterTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore
    private lateinit var writer: WorkspaceWriter

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "writer-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = WorkspaceStore(root).also { it.scaffold("test", 1_787_000_000_000L) }
        writer = WorkspaceWriter(store, db, Indexer(db), device = "test-device")
        writer.reindex()
    }

    @After
    fun tearDown() = db.close()

    /** What a cold read of the files says, which is what the index must agree with. */
    private fun onDisk() = WorkspaceReconciler.read(store, 0L)

    private fun pageText(id: String) = store.pageFile(id).readText()

    // ---- creating ----

    @Test
    fun aTopLevelListBecomesItsOwnFile() = runBlocking {
        val id = writer.createTopLevel(NodeType.LIST, "Groceries")
        assertTrue(store.pageFile(id).exists())
        assertTrue(pageText(id).contains("title: Groceries"))
        assertEquals("Groceries", db.nodeDao().byId(id)?.title)
    }

    @Test
    fun aTaskBecomesALineOnItsParentAndNotAFileOfItsOwn() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "Groceries")
        val task = writer.addBlock(list, NodeType.TASK, "Buy milk")

        assertTrue("the line is missing", pageText(list).contains("- [ ] Buy milk ^$task"))
        // A task holding nothing does not earn a document; empty files in every clone for nothing.
        assertTrue("an empty page was written", !store.pageFile(task).exists())
        assertEquals("Buy milk", db.nodeDao().byId(task)?.title)
    }

    @Test
    fun blocksLandWhereTheyWereAskedFor() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "L")
        val a = writer.addBlock(list, NodeType.TASK, "first")
        val c = writer.addBlock(list, NodeType.TASK, "third")
        writer.addBlock(list, NodeType.TASK, "second", afterId = a)

        val order = PageCodec.decode(pageText(list)).blocks
            .filterIsInstance<ie.napkin.supertasks.data.format.TaskRef>().map { it.title }
        assertEquals(listOf("first", "second", "third"), order)
        assertTrue(c.isNotEmpty())
    }

    // ---- editing a line ----

    @Test
    fun completingATaskRewritesTheLineNotTheDatabase() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "L")
        val task = writer.addBlock(list, NodeType.TASK, "Buy milk")

        writer.editTask(task) { it.copy(status = TaskStatus.DONE) }

        assertTrue("the file still says open", pageText(list).contains("- [x] Buy milk ^$task"))
        assertEquals(true, db.nodeDao().byId(task)?.done)
        // And a cold read agrees, so the index holds nothing the repo does not.
        assertEquals(true, onDisk().nodes.single { it.id == task }.done)
    }

    @Test
    fun renamingRewritesTheLine() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "L")
        val task = writer.addBlock(list, NodeType.TASK, "old name")
        writer.editTask(task) { it.copy(title = "new name") }

        assertTrue(pageText(list).contains("- [ ] new name ^$task"))
        assertEquals("new name", db.nodeDao().byId(task)?.title)
    }

    @Test
    fun indentIsWrittenAsIndentAndStaysAChildOfThePage() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "L")
        writer.addBlock(list, NodeType.TASK, "parent line")
        val child = writer.addBlock(list, NodeType.TASK, "under it")
        writer.editTask(child) { it.copy(indent = 1) }

        assertTrue(pageText(list).contains("${PageCodec.INDENT} - [ ] under it"))
        val row = db.nodeDao().byId(child)!!
        assertEquals(1, row.indent)
        assertEquals("indent moved it in the tree", list, row.parentId)
    }

    @Test
    fun aPropertyOnALineSurvivesAReindex() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "L")
        val task = writer.addBlock(list, NodeType.TASK, "with a due date")
        writer.editTask(task) {
            it.copy(
                due = ie.napkin.supertasks.data.format.DueSpec(
                    ie.napkin.supertasks.data.format.DueValue.AllDay(java.time.LocalDate.of(2026, 9, 1))
                ),
                priority = "high",
                labels = listOf("shop"),
            )
        }
        assertTrue(pageText(list).contains("due:2026-09-01"))

        // The real question: does it come back after the index is thrown away and rebuilt?
        writer.reindex()
        val values = onDisk().values.filter { it.nodeId == task }
        assertEquals(2, values.size)
        assertTrue(onDisk().labels.any { it.name == "shop" })
    }

    // ---- moving and removing ----

    @Test
    fun reorderingRewritesLineOrder() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "L")
        writer.addBlock(list, NodeType.TASK, "a")
        val b = writer.addBlock(list, NodeType.TASK, "b")
        writer.addBlock(list, NodeType.TASK, "c")

        writer.moveBlock(b, 0)

        val titles = PageCodec.decode(pageText(list)).blocks
            .filterIsInstance<ie.napkin.supertasks.data.format.TaskRef>().map { it.title }
        assertEquals(listOf("b", "a", "c"), titles)
        // Ranks are regenerated from line order, so the index agrees without being told separately.
        val rows = onDisk().nodes.filter { it.parentId == list }.sortedBy { it.rank }
        assertEquals(listOf("b", "a", "c"), rows.map { it.title })
    }

    @Test
    fun removingATaskTakesItsLineItsPageAndItsInk() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "L")
        val task = writer.addBlock(list, NodeType.TASK, "doomed")
        val ink = writer.addBlock(list, NodeType.INK, null)
        writer.writeInk(ink, listOf(byteArrayOf(1, 2, 3)))

        writer.removeBlock(task)
        assertTrue("the line survived", !pageText(list).contains("^$task"))
        assertEquals(null, db.nodeDao().byId(task))

        writer.removeBlock(ink)
        assertTrue("the sidecar outlived the block", !store.inkFile(ink).exists())
    }

    @Test
    fun inkBytesGoToASidecarAndComeBackUnchanged() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "L")
        val ink = writer.addBlock(list, NodeType.INK, null)
        writer.writeInk(ink, listOf(byteArrayOf(4, 5, 6), byteArrayOf(7)))

        assertTrue(store.inkFile(ink).exists())
        val strokes = onDisk().ink.filter { it.nodeId == ink }
        assertEquals(2, strokes.size)
        assertEquals(listOf<Byte>(4, 5, 6), strokes[0].data.toList())
    }

    // ---- the property that matters ----

    @Test
    fun throwingTheIndexAwayLosesNothing() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "Groceries")
        val t1 = writer.addBlock(list, NodeType.TASK, "Buy milk")
        val t2 = writer.addBlock(list, NodeType.TASK, "Buy bread")
        writer.editTask(t2) { it.copy(status = TaskStatus.DONE, priority = "high") }
        writer.addBlock(list, NodeType.HEADING, "A heading")

        val before = onDisk()
        db.clearAllTables()
        writer.reindex()

        val after = onDisk()
        assertEquals(
            before.nodes.sortedBy { it.id }.map { "${it.id}|${it.title}|${it.done}|${it.type}" },
            after.nodes.sortedBy { it.id }.map { "${it.id}|${it.title}|${it.done}|${it.type}" },
        )
        assertEquals("Buy milk", db.nodeDao().byId(t1)?.title)
        assertEquals(true, db.nodeDao().byId(t2)?.done)
    }

    @Test
    fun concurrentWholeListWritesLeaveOneOfThemIntact() = runBlocking {
        // Whole-list writes cannot lose a stroke the way an append could, but they can still tear if
        // they are not serialised — a file holding half of one drawing and half of another. The
        // guarantee is that whichever write lands last, the file is exactly what that caller passed.
        val a = (1..12).map { byteArrayOf(1, it.toByte()) }
        val b = (1..7).map { byteArrayOf(2, it.toByte()) }
        coroutineScope {
            listOf(
                async { writer.writeInk("ink-block", a) },
                async { writer.writeInk("ink-block", b) },
            ).awaitAll()
        }

        val onDisk = store.readInk("ink-block")
        assertTrue("torn: ${onDisk.size} strokes", onDisk.size == a.size || onDisk.size == b.size)
        assertEquals(1, onDisk.map { it.first() }.toSet().size)
    }

}
