package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.EventTime
import ie.shoonya.yantra.data.format.ExternalRef
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime

/**
 * An event keeps its row when something else on the page changes — CALENDAR_PLAN.md §27.
 *
 * **The bug this exists for.** The indexer skips a table whose rows are identical to last time,
 * which is what stops a keystroke from waking every flow in the app. But `event` and `node_label`
 * hang off `node` with `ON DELETE CASCADE`, so the node wipe that a keystroke *does* cause took
 * their rows with it — and the skip then declined to write them back. `PRAGMA defer_foreign_keys`
 * postpones the *check*; it does not cancel the *action*, which is why the other dependent tables
 * survived this and these two did not.
 *
 * On a phone it read as: tap somebody's meeting, the page opens with its header, type one word, and
 * the header is gone — permanently, because the indexer's memory had already recorded the rows as
 * written. Every test here writes *something unrelated* and then asks whether the event is still
 * there, because that is the whole of the failure.
 */
@RunWith(AndroidJUnit4::class)
class EventRowSurvivesTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore
    private lateinit var indexer: Indexer
    private lateinit var writer: WorkspaceWriter

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "eventrow-${System.nanoTime()}").apply { mkdirs() }
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

    private val start: LocalDateTime = LocalDateTime.parse("2026-09-16T10:00")

    /** A page holding one event line about somebody else's meeting, as a tap on one writes it. */
    private suspend fun aMeetingNote(): Pair<String, String> {
        val page = writer.createTopLevel(NodeType.LIST, "Inbox")
        val event = writer.addEvent(
            pageId = page,
            event = EventRef(
                id = "",
                title = "Pluto x Napkin",
                time = EventTime(start = start, end = start.plusHours(1), zone = null, allDay = false),
                location = "Microsoft Teams Meeting",
                external = ExternalRef(uid = "_60q30@napkin.ie", occurrence = null),
            ),
        )
        return page to event
    }

    private suspend fun eventRow(nodeId: String) = db.eventDao().observeById(nodeId).first()

    @Test
    fun `an event has a row as soon as its line exists`() = runBlocking {
        val (_, event) = aMeetingNote()
        assertNotNull("the line is written, so the row is there", eventRow(event))
        assertEquals("_60q30@napkin.ie", eventRow(event)?.nodeExtUid)
    }

    @Test
    fun `writing the first note on a meeting does not take its row away`() = runBlocking {
        val (_, event) = aMeetingNote()
        // The gesture: you type into the meeting's own page. That creates a node and changes no
        // event at all — which is exactly the case the skip got wrong.
        writer.addBlock(event, NodeType.PARAGRAPH, "agenda: pricing")

        val row = eventRow(event)
        assertNotNull("the header still has something to draw", row)
        assertEquals("and still knows whose meeting it is", "_60q30@napkin.ie", row?.nodeExtUid)
    }

    @Test
    fun `it survives every following keystroke too and not just the first`() = runBlocking {
        val (_, event) = aMeetingNote()
        // The failure was sticky: once the rows were cascaded away the indexer's memory insisted
        // they were written, so no later rebuild put them back. Repetition is the point.
        repeat(4) { i ->
            writer.addBlock(event, NodeType.PARAGRAPH, "line $i")
            assertNotNull("gone after ${i + 1} edits", eventRow(event))
        }
    }

    @Test
    fun `a change on a different page cannot take it away either`() = runBlocking {
        val (_, event) = aMeetingNote()
        val other = writer.createTopLevel(NodeType.LIST, "Work")
        writer.addBlock(other, NodeType.TASK, "Write the deck")
        assertNotNull("an unrelated page's edit is not this event's business", eventRow(event))
    }
}
