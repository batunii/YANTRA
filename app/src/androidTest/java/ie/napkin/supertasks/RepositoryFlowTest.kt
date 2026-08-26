package ie.napkin.supertasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.FocusOutcome
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
    private lateinit var focus: ie.napkin.supertasks.data.repo.FocusRepository
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
        focus = ie.napkin.supertasks.data.repo.FocusRepository(db, ws)
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

    // ---- capture from a line of typing ----

    @Test
    fun aTypedLineBecomesATaskWithEverythingItSaid() = runBlocking {
        val list = nodes.create(null, NodeType.LIST, "Errands")
        val id = nodes.captureTask(list, "buy milk tomorrow 6pm #home !high", labels, props)!!

        // The title is what is left, and the file is what proves it.
        val page = ws.primaryStore().pageFile(list).readText()
        assertTrue("the modifiers stayed in the title: $page", page.contains("buy milk"))
        assertTrue("a modifier survived into the title", !page.contains("tomorrow"))

        // Each one landed where it belongs, not merely got stripped from the text.
        assertEquals("High", db.propertyDao().valuesForNodeOnce(id).firstOrNull {
            it.defId == ie.napkin.supertasks.data.db.BuiltIns.PRIORITY_DEF_ID
        }?.vText)
        assertTrue(
            "no due date was set",
            db.propertyDao().valuesForNodeOnce(id).any {
                it.defId == ie.napkin.supertasks.data.db.BuiltIns.DUE_DEF_ID && it.vDate != null
            },
        )
        assertTrue("the label was not attached", page.contains("#home"))
    }

    @Test
    fun plainTypingIsLeftExactlyAsWritten() = runBlocking {
        // Far more common than the clever case, and the one where being wrong is most annoying.
        val list = nodes.create(null, NodeType.LIST, "Notes")
        val id = nodes.captureTask(list, "think about the roadmap", labels, props)!!

        assertEquals("think about the roadmap", db.nodeDao().byId(id)?.title)
        assertTrue(db.propertyDao().valuesForNodeOnce(id).none {
            it.defId == ie.napkin.supertasks.data.db.BuiltIns.DUE_DEF_ID
        })
    }

    @Test
    fun aLineThatIsOnlyModifiersStaysATask() = runBlocking {
        // "today" is a perfectly good task name and must not become an empty task due today.
        val list = nodes.create(null, NodeType.LIST, "Notes")
        val id = nodes.captureTask(list, "today", labels, props)!!
        assertEquals("today", db.nodeDao().byId(id)?.title)
    }

    // ---- focus sessions ----

    @Test
    fun endingASessionEarlyLeavesNoSessionRunning() = runBlocking {
        // The bug this exists for: a session stopped inside a minute was skipped rather than closed,
        // so its opening line stayed in the log with no end. `openSession` kept finding it and the
        // timer resurrected a session that had already been stopped — on the next launch, every
        // widget render, and every worker pass. Starting another simply added a second ghost.
        val list = nodes.create(null, NodeType.LIST, "Work")
        val task = nodes.create(list, NodeType.TASK, "Write the thing")

        val id = focus.startSession(task, plannedSecs = 1500)
        assertNotNull("the session was never opened", focus.openSession())

        focus.endSession(id, actualSecs = FocusOutcome.MIN_KEPT_SECS - 1, outcome = FocusOutcome.STOPPED)

        assertNull("a stopped session is still running", focus.openSession())
    }

    @Test
    fun aMisTapIsClosedButCountsNowhere() = runBlocking {
        val list = nodes.create(null, NodeType.LIST, "Work")
        val task = nodes.create(list, NodeType.TASK, "Thing")
        val id = focus.startSession(task, plannedSecs = 1500)
        focus.endSession(id, actualSecs = FocusOutcome.MIN_KEPT_SECS - 1, outcome = FocusOutcome.STOPPED)

        // Closed, so nothing thinks it is running — and absent from history and totals, because it
        // did not happen in any sense the user would recognise.
        assertNull(focus.openSession())
        assertTrue(focus.forNode(task).first().isEmpty())
        assertEquals(0, focus.secondsOnSubtree(task).first())
    }

    @Test
    fun endingEarlyPastTheThresholdIsRealTimeAndCounts() = runBlocking {
        // "Ending early" is a legitimate thing to do, not a failure — the ledger measures what you
        // gave, not whether you obeyed yourself.
        val list = nodes.create(null, NodeType.LIST, "Work")
        val task = nodes.create(list, NodeType.TASK, "Thing")
        val id = focus.startSession(task, plannedSecs = 1500)

        val gave = FocusOutcome.MIN_KEPT_SECS * 40
        focus.endSession(id, actualSecs = gave, outcome = FocusOutcome.STOPPED)

        assertNull(focus.openSession())
        assertEquals(1, focus.forNode(task).first().size)
        assertEquals(gave, focus.secondsOnSubtree(task).first())
        assertEquals(FocusOutcome.STOPPED, focus.forNode(task).first().single().outcome)
    }

    @Test
    fun aSecondSessionDoesNotStrandTheFirst() = runBlocking {
        // Starting a new session interrupts any running one. If that close is skipped, every start
        // leaves another ghost behind it.
        val list = nodes.create(null, NodeType.LIST, "Work")
        val task = nodes.create(list, NodeType.TASK, "Thing")

        val first = focus.startSession(task, plannedSecs = 1500)
        focus.endSession(first, actualSecs = FocusOutcome.MIN_KEPT_SECS - 1, outcome = FocusOutcome.INTERRUPTED)
        val second = focus.startSession(task, plannedSecs = 1500)

        assertEquals("the wrong session is open", second, focus.openSession()?.id)
    }

    @Test
    fun aTypedLineCanSayWhichListItGoesTo() = runBlocking {
        // The end of the round trip: the parser finds the name, and the task actually lands there
        // rather than wherever the typing happened.
        val groceries = nodes.create(null, NodeType.LIST, "Groceries")
        val elsewhere = nodes.create(null, NodeType.LIST, "Work")

        val id = nodes.captureTask(elsewhere, "buy milk ~ Groceries", labels, props)!!

        assertEquals(groceries, db.nodeDao().byId(id)?.parentId)
        assertEquals("buy milk", db.nodeDao().byId(id)?.title)
        // And it is a routing instruction, not a field: nothing of it survives on the line.
        assertTrue(!ws.primaryStore().pageFile(groceries).readText().contains("~"))
    }

    @Test
    fun anUnknownListIsMadeAndTheTaskLandsInIt() = runBlocking {
        val here = nodes.create(null, NodeType.LIST, "Work")
        val id = nodes.captureTask(here, "buy tent ~ Camping", labels, props)!!

        val parent = db.nodeDao().byId(id)?.parentId
        assertTrue("the task stayed where it was typed", parent != here)
        assertEquals("Camping", db.nodeDao().byId(parent!!)?.title)
        assertEquals(NodeType.LIST, db.nodeDao().byId(parent)?.type)
        assertEquals("buy tent", db.nodeDao().byId(id)?.title)
        // A real list, on disk, not a row someone has to reconcile later.
        assertTrue(ws.primaryStore().pageFile(parent).exists())
    }

    @Test
    fun deletingATopLevelListActuallyDeletesIt() = runBlocking {
        // The bug this covers: a top-level node has no parent, and removeBlock read that as "there
        // is no line to strike out, so there is nothing to do" and returned. Deleting a list did
        // nothing whatsoever — silently, with a confirmation dialog in front of it.
        val list = nodes.create(null, NodeType.LIST, "Doomed")
        val task = nodes.create(list, NodeType.TASK, "Inside it")
        assertTrue(ws.primaryStore().pageFile(list).exists())

        nodes.delete(list)

        assertNull("the list survived", db.nodeDao().byId(list))
        assertNull("the task outlived its list", db.nodeDao().byId(task))
        assertTrue("the page is still on disk", !ws.primaryStore().pageFile(list).exists())
    }

    @Test
    fun deletingAListInsideAGroupStillWorks() = runBlocking {
        // The path that always worked, kept honest now that the other one shares its code.
        val group = nodes.createGroup("Shelf")
        val list = nodes.create(null, NodeType.LIST, "Doomed")
        nodes.moveToGroup(list, group)

        nodes.delete(list)

        assertNull(db.nodeDao().byId(list))
        assertTrue(ws.primaryStore().pageFile(group).exists())
    }

    @Test
    fun aNameThatDiffersOnlyBySpacingFindsTheListThatExists() = runBlocking {
        // The whole reason for normalising: typing this one-handed mid-sentence is the point, and
        // making a second "Worktrips" beside "Work trips" would be the worst possible outcome.
        val trips = nodes.create(null, NodeType.LIST, "Work trips")
        val here = nodes.create(null, NodeType.LIST, "Work")
        val before = db.nodeDao().allListsOnce().size

        val id = nodes.captureTask(here, "book flights ~worktrips", labels, props)!!

        assertEquals(trips, db.nodeDao().byId(id)?.parentId)
        assertEquals("book flights", db.nodeDao().byId(id)?.title)
        // The count, not a fixed number — the fixture seeds lists of its own. What matters is that
        // matching an existing list made nothing.
        assertEquals("a duplicate list was created", before, db.nodeDao().allListsOnce().size)
    }
}
