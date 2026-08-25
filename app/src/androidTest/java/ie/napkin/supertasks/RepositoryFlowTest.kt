package ie.napkin.supertasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.repo.InkRepository
import ie.napkin.supertasks.data.repo.LabelRepository
import ie.napkin.supertasks.data.repo.NodeRepository
import ie.napkin.supertasks.data.repo.PropertyRepository
import ie.napkin.supertasks.data.seed.WorkspaceSeeder
import ie.napkin.supertasks.data.workspace.Indexer
import ie.napkin.supertasks.data.workspace.Workspaces
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The repositories, driven the way the app drives them.
 *
 * Two bugs got through a hundred and fifty tests and made the app unusable, and both had the same
 * shape: **create a block, then edit it.** Every existing test either built a page wholesale or
 * edited a block that already had content, so neither the missing page file nor the unrepresentable
 * empty block ever came up. These go through NodeRepository rather than the writer beneath it,
 * because the seam where the app meets the workspace is where they were hiding.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var ws: Workspaces
    private lateinit var nodes: NodeRepository
    private lateinit var props: PropertyRepository
    private lateinit var labels: LabelRepository
    private lateinit var ink: InkRepository
    private lateinit var root: File

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "flow-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        ws = Workspaces(db, Indexer(db), "test-device")
        ws.open("", root, "Test")
        WorkspaceSeeder.seed(ws.primaryStore(), 1_787_000_000_000L)
        ws.reindexAll()

        nodes = NodeRepository(db, ws)
        props = PropertyRepository(db, ws)
        labels = LabelRepository(db, ws)
        ink = InkRepository(db, ws)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun aTaskWithNoPage(): String {
        val list = nodes.create(null, NodeType.LIST, "Host")
        return nodes.create(list, NodeType.TASK, "A task nothing hangs off yet")
    }

    private fun pageText(id: String) =
        ws.primaryStore().pageFile(id).takeIf { it.exists() }?.readText()

    // ---- the sequence that broke ----

    @Test
    fun writeSomethingOnATaskThatHasNoPageYet() = runBlocking {
        // Exactly what the write line does: make an empty paragraph, then type into it. The task
        // has no file at all at this point, which is what made the first tap do nothing.
        val task = aTaskWithNoPage()
        assertNull("a task with nothing on it should not have a page", pageText(task))

        val block = nodes.create(task, NodeType.PARAGRAPH, "")
        assertTrue("the block was not created", block.isNotEmpty())
        assertNotNull("adding a block did not create the page", pageText(task))

        nodes.rename(block, "Typed after the fact")
        assertTrue(
            "the text never reached the file",
            pageText(task)!!.contains("Typed after the fact"),
        )
        assertEquals("Typed after the fact", db.nodeDao().byId(block)?.title)
    }

    @Test
    fun anEmptyBlockSurvivesUntilItIsTypedInto() = runBlocking {
        // The block has to exist while it is still empty, or the caret has nothing to land on.
        val task = aTaskWithNoPage()
        val block = nodes.create(task, NodeType.PARAGRAPH, "")

        ws.reindexAll()
        assertNotNull("an empty block vanished on reindex", db.nodeDao().byId(block))
        assertEquals(NodeType.PARAGRAPH, db.nodeDao().byId(block)?.type)
    }

    @Test
    fun everyBlockKindCanBeCreatedEmptyAndThenFilled() = runBlocking {
        val task = aTaskWithNoPage()
        listOf(NodeType.PARAGRAPH, NodeType.HEADING, NodeType.BULLET, NodeType.NUMBERED)
            .forEach { kind ->
                val id = nodes.create(task, kind, "")
                nodes.rename(id, "filled $kind")
                ws.reindexAll()
                val row = db.nodeDao().byId(id)
                assertEquals("$kind lost its kind", kind, row?.type)
                assertEquals("$kind lost its text", "filled $kind", row?.title)
            }
    }

    @Test
    fun anInkBlockOnAPagelessTaskGetsAPageAndThenStrokes() = runBlocking {
        val task = aTaskWithNoPage()
        val inkId = nodes.create(task, NodeType.INK, null)
        assertNotNull("the ink block did not create a page", pageText(task))
        assertTrue(pageText(task)!!.contains("![[ink:$inkId]]"))

        // No sidecar until something is drawn — an empty file in every clone for nothing.
        assertTrue(!ws.primaryStore().inkFile(inkId).exists())

        val codec = ie.napkin.supertasks.data.ink.StrokeCodec
        val stroke = androidx.ink.strokes.Stroke(
            brush = codec.brush(codec.FAMILY_PRESSURE_PEN, 0xFF000000L, 4f),
            inputs = codec.shapeInputs(
                ie.napkin.supertasks.data.ink.ShapeKind.LINE, 0f, 0f, 10f, 10f
            ),
        )
        // The drawing screen holds the session and hands over the whole set; there is no append.
        ink.replace(inkId, listOf(codec.encode(stroke, codec.FAMILY_PRESSURE_PEN)))
        assertTrue("the stroke never reached a sidecar", ws.primaryStore().inkFile(inkId).exists())
        assertEquals(1, ink.strokes(inkId).first().size)
    }

    // ---- create then change, for everything a block can carry ----

    @Test
    fun aTaskCreatedThenCompleted() = runBlocking {
        val list = nodes.create(null, NodeType.LIST, "L")
        val task = nodes.create(list, NodeType.TASK, "Do the thing")
        nodes.setDone(task, true)

        assertTrue(pageText(list)!!.contains("- [x] Do the thing"))
        assertEquals(true, db.nodeDao().byId(task)?.done)
    }

    @Test
    fun aTaskCreatedThenGivenEveryProperty() = runBlocking {
        val list = nodes.create(null, NodeType.LIST, "L")
        val task = nodes.create(list, NodeType.TASK, "Loaded")

        props.setDue(task, 1_787_000_000_000L, hasTime = false, reminderOffsetMin = -540)
        props.setValue(task, BuiltIns.PRIORITY_DEF_ID, text = "High")
        val label = labels.getOrCreate("shop")
        labels.attach(task, label.id)

        val text = pageText(list)!!
        assertTrue("due missing: $text", text.contains("due:"))
        assertTrue("reminder missing: $text", text.contains("+r-540"))
        assertTrue("priority missing: $text", text.contains("!High"))
        assertTrue("label missing: $text", text.contains("#shop"))

        ws.reindexAll()
        assertEquals(2, db.propertyDao().valuesForNodeOnce(task).size)
    }

    @Test
    fun aPropertyCanBeClearedAgain() = runBlocking {
        val list = nodes.create(null, NodeType.LIST, "L")
        val task = nodes.create(list, NodeType.TASK, "Loaded")
        props.setValue(task, BuiltIns.PRIORITY_DEF_ID, text = "High")
        props.clearValue(task, BuiltIns.PRIORITY_DEF_ID)

        assertTrue(!pageText(list)!!.contains("!High"))
        ws.reindexAll()
        assertTrue(db.propertyDao().valuesForNodeOnce(task).isEmpty())
    }

    @Test
    fun aBlockCreatedThenIndentedThenOutdented() = runBlocking {
        val task = aTaskWithNoPage()
        nodes.create(task, NodeType.PARAGRAPH, "first")
        val second = nodes.create(task, NodeType.PARAGRAPH, "second")

        nodes.setIndent(db.nodeDao().byId(second)!!, 1)
        assertTrue(pageText(task)!!.contains("${ie.napkin.supertasks.data.format.PageCodec.INDENT} second"))

        nodes.setIndent(db.nodeDao().byId(second)!!, 0)
        assertTrue(!pageText(task)!!.contains("${ie.napkin.supertasks.data.format.PageCodec.INDENT} second"))
    }

    @Test
    fun aBlockCreatedThenConvertedToAnotherKind() = runBlocking {
        val task = aTaskWithNoPage()
        val block = nodes.create(task, NodeType.PARAGRAPH, "was prose")
        nodes.setType(block, NodeType.HEADING)

        assertTrue(pageText(task)!!.contains("# was prose"))
        ws.reindexAll()
        assertEquals(NodeType.HEADING, db.nodeDao().byId(block)?.type)
    }

    @Test
    fun aBlockCreatedThenDeleted() = runBlocking {
        val task = aTaskWithNoPage()
        val block = nodes.create(task, NodeType.PARAGRAPH, "here and gone")
        nodes.delete(block)

        assertTrue(!pageText(task)!!.contains("here and gone"))
        assertNull(db.nodeDao().byId(block))
    }

    @Test
    fun blocksCreatedThenReordered() = runBlocking {
        val task = aTaskWithNoPage()
        nodes.create(task, NodeType.PARAGRAPH, "a")
        val b = nodes.create(task, NodeType.PARAGRAPH, "b")
        nodes.create(task, NodeType.PARAGRAPH, "c")

        nodes.moveToIndex(db.nodeDao().byId(b)!!, 0)
        val order = db.nodeDao().childrenOnce(task).map { it.title }
        assertEquals(listOf("b", "a", "c"), order)
    }

    @Test
    fun aTaskCreatedThenGivenChildrenOfItsOwn() = runBlocking {
        // A task earning a page, then that task's child earning one too.
        val list = nodes.create(null, NodeType.LIST, "L")
        val parent = nodes.create(list, NodeType.TASK, "Parent")
        val child = nodes.create(parent, NodeType.TASK, "Child")
        val grandchild = nodes.create(child, NodeType.PARAGRAPH, "Note under the child")

        assertNotNull(pageText(parent))
        assertNotNull(pageText(child))
        ws.reindexAll()
        assertEquals(parent, db.nodeDao().byId(child)?.parentId)
        assertEquals(child, db.nodeDao().byId(grandchild)?.parentId)
        assertEquals("Note under the child", db.nodeDao().byId(grandchild)?.title)
    }

    @Test
    fun theWholeSequenceSurvivesLosingTheIndex() = runBlocking {
        val list = nodes.create(null, NodeType.LIST, "Project")
        val task = nodes.create(list, NodeType.TASK, "Ship it")
        props.setValue(task, BuiltIns.PRIORITY_DEF_ID, text = "High")
        val note = nodes.create(task, NodeType.PARAGRAPH, "")
        nodes.rename(note, "Some notes")
        nodes.setDone(task, true)

        db.clearAllTables()
        ws.reindexAll()

        assertEquals("Ship it", db.nodeDao().byId(task)?.title)
        assertEquals(true, db.nodeDao().byId(task)?.done)
        assertEquals("Some notes", db.nodeDao().byId(note)?.title)
        assertEquals("High", db.propertyDao().valuesForNodeOnce(task).single().vText)
    }
}
