package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.EventTime
import ie.shoonya.yantra.data.sync.Change
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.LocalDateTime

/**
 * Moving a sitting — CALENDAR_PLAN.md §11, §12.
 *
 * A sitting is the one block on the timeline with no words of its own, which makes it the one whose
 * line can lose something without anybody noticing: `for:` is what makes it a sitting at all, and a
 * move rewrites the whole line. Everything here asserts against the **file** as well as the index,
 * because a move that reached one and not the other is exactly a block that vanishes and comes back
 * the next time something forces a reindex.
 */
@RunWith(AndroidJUnit4::class)
class SittingMoveTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore
    private lateinit var writer: WorkspaceWriter

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "sitting-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = WorkspaceStore(root).also { it.scaffold("test", 1_787_000_000_000L) }
        writer = WorkspaceWriter(store, db, Indexer(db), device = "test-device")
        writer.reindex()
    }

    @After
    fun tearDown() = db.close()

    private val start: LocalDateTime = LocalDateTime.parse("2026-09-13T14:00")

    /** A list holding one task and one sitting for it, the way the calendar writes them. */
    private suspend fun aSitting(): Triple<String, String, String> {
        val page = writer.createTopLevel(NodeType.LIST, "Work")
        val task = writer.addBlock(page, NodeType.TASK, "Write the deck")
        val sitting = writer.addEvent(
            pageId = page,
            event = EventRef(
                id = "",
                title = "",
                time = EventTime(start = start, end = start.plusHours(1), zone = null, allDay = false),
                forTaskId = task,
                reminderMin = 0,
            ),
            afterId = task,
        )
        return Triple(page, task, sitting)
    }

    @Test
    fun aSittingIsWrittenAsOne() = runBlocking {
        val (page, task, sitting) = aSitting()
        val line = store.pageFile(page).readText().lines().first { it.startsWith("@ ") }
        assertEquals("the line must say which task it is for", true, line.contains("for:$task"))
        assertEquals(task, db.eventDao().byId(sitting)?.forNodeId)
    }

    /** The report: move it and it is gone until something else forces a reindex. */
    @Test
    fun movingASittingKeepsItASitting() = runBlocking {
        val (page, task, sitting) = aSitting()
        val to = start.plusHours(3)

        writer.editEvent(sitting) { e ->
            e.copy(time = e.time.copy(start = to, end = to.plus(Duration.ofHours(1))), raw = null)
        }
        writer.flushIndex()

        val line = store.pageFile(page).readText().lines().firstOrNull { it.startsWith("@ ") }
        assertNotNull("the event line must still be on the page", line)
        assertEquals("it must still be for the task", true, line!!.contains("for:$task"))

        val row = db.eventDao().byId(sitting)
        assertNotNull("the sitting must still be indexed after a move", row)
        assertEquals(to.toString(), row!!.startLocal)
        assertEquals(task, row.forNodeId)
    }

    /**
     * A move has to reach the index **now**, not two hundred milliseconds from now.
     *
     * This is the one the report was about. A writer with a scope defers a plain edit — right for
     * typing, where the letters are already on screen from the field's own state — but a calendar
     * has no second copy of anything: every block it draws comes from the index. Deferred, the block
     * you just dropped springs back to where it was, and if the next thing you do restarts the timer
     * it stays wrong. So a move is structural, and this asserts it without flushing anything.
     */
    @Test
    fun aMoveIsVisibleImmediatelyEvenWhenPlainEditsDefer() = runBlocking {
        val scoped = WorkspaceWriter(
            store, db, Indexer(db), device = "test-device",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val page = scoped.createTopLevel(NodeType.LIST, "Work")
        val task = scoped.addBlock(page, NodeType.TASK, "Write the deck")
        val sitting = scoped.addEvent(
            pageId = page,
            event = EventRef(
                id = "",
                title = "",
                time = EventTime(start = start, end = start.plusHours(1), zone = null, allDay = false),
                forTaskId = task,
                reminderMin = 0,
            ),
            afterId = task,
        )

        val to = start.plusHours(3)
        scoped.editEvent(sitting, Change.STRUCTURAL) { e ->
            e.copy(time = e.time.copy(start = to, end = to.plus(Duration.ofHours(1))), raw = null)
        }

        // No flushIndex, no waiting: this is what the screen sees on the frame after the drop.
        val row = db.eventDao().byId(sitting)
        assertNotNull("the moved sitting must be in the index already", row)
        assertEquals(to.toString(), row!!.startLocal)
        assertEquals(task, row.forNodeId)
    }

    /**
     * Resizing, which the recording shows reverting and never coming back — unlike a move, which
     * reverted and then landed. Same gesture, same writer, different outcome, so the length is
     * asserted on the file as well as the row.
     */
    @Test
    fun resizingASittingKeepsTheNewLength() = runBlocking {
        val (page, _, sitting) = aSitting()
        val longer = start.plusMinutes(75)

        writer.editEvent(sitting, Change.STRUCTURAL) { e ->
            e.copy(time = e.time.copy(end = maxOf(longer, e.time.start)), raw = null)
        }
        writer.flushIndex()

        val line = store.pageFile(page).readText().lines().first { it.startsWith("@ ") }
        assertEquals("the line must carry the new length: $line", true, line.contains("PT1H15M"))
        assertEquals(longer.toString(), db.eventDao().byId(sitting)?.endLocal)
    }

    /**
     * A colour picked in the sheet has to reach the file and come back through the index.
     *
     * Three places it could be dropped without a word — the renderer, the mapper, or the edit that
     * rewrites the line for some other reason — so all three are checked here rather than trusted.
     */
    @Test
    fun aColourReachesTheFileAndComesBack() = runBlocking {
        val (page, _, sitting) = aSitting()

        writer.editEvent(sitting, Change.STRUCTURAL) { it.copy(color = "Teal", raw = null) }
        writer.flushIndex()

        val line = store.pageFile(page).readText().lines().first { it.startsWith("@ ") }
        assertEquals("the line must carry the colour: $line", true, line.contains("col:Teal"))
        assertEquals("Teal", db.eventDao().byId(sitting)?.color)

        // And it survives being moved, which rewrites the whole line.
        val to = start.plusHours(2)
        writer.editEvent(sitting, Change.STRUCTURAL) { e ->
            e.copy(time = e.time.copy(start = to, end = to.plusHours(1)), raw = null)
        }
        writer.flushIndex()
        assertEquals("a move must not lose the colour", "Teal", db.eventDao().byId(sitting)?.color)
    }

    /** And the index must agree with a cold read of the files, not merely be non-empty. */
    @Test
    fun theIndexAgreesWithTheFileAfterAMove() = runBlocking {
        val (_, task, sitting) = aSitting()
        val to = start.plusHours(3)
        writer.editEvent(sitting) { e ->
            e.copy(time = e.time.copy(start = to, end = to.plus(Duration.ofHours(1))), raw = null)
        }
        writer.flushIndex()
        val afterEdit = db.eventDao().byId(sitting)

        writer.reindex()
        val afterCold = db.eventDao().byId(sitting)

        assertEquals(afterCold?.startLocal, afterEdit?.startLocal)
        assertEquals(afterCold?.forNodeId, afterEdit?.forNodeId)
        assertEquals(task, afterCold?.forNodeId)
    }
}
