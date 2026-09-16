package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.db.SystemKey
import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.EventTime
import ie.shoonya.yantra.data.format.ExternalRef
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.Workspaces
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
import java.time.LocalDateTime

/**
 * Filing a node onto a list in another workspace — CALENDAR_PLAN.md §28.
 *
 * **The bug this exists for.** `WorkspaceWriter.reparent` owns one store. Across workspaces it
 * looked the destination page up in the *source* repo, found nothing, invented one there, and
 * appended the line to it — so the node ended up in neither place a person could reach. Nothing
 * caught it because no screen offered the move; the page menu does now.
 *
 * A move also has to carry what a node owns, not only the line that names it. The notes written on
 * a meeting are a page file of their own, and a move that left them behind would be indisinguishable
 * from a move that lost them.
 */
@RunWith(AndroidJUnit4::class)
class MoveAcrossWorkspacesTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var ws: Workspaces

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "across-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        ws = Workspaces(db, Indexer(db), "test-device")
        ws.open("", File(root, "personal"), "Personal")
        ws.open("ws-work", File(root, "work"), "Work")
        ws.reindexAll()
    }

    @After
    fun tearDown() = db.close()

    private val start: LocalDateTime = LocalDateTime.parse("2026-09-16T10:00")

    /** An Inbox in Personal holding a meeting note, and a list in Work to file it onto. */
    private suspend fun setUpMove(): Triple<String, String, String> {
        val personal = ws.writer("")!!
        val work = ws.writer("ws-work")!!
        val inbox = personal.createTopLevel(NodeType.LIST, "Inbox", systemKey = SystemKey.INBOX)
        val event = personal.addEvent(
            pageId = inbox,
            event = EventRef(
                id = "",
                title = "Pluto x Napkin",
                time = EventTime(start = start, end = start.plusHours(1), zone = null, allDay = false),
                location = "Microsoft Teams Meeting",
                external = ExternalRef(uid = "_60q30@napkin.ie", occurrence = null),
            ),
        )
        val clients = work.createTopLevel(NodeType.LIST, "Clients")
        return Triple(inbox, event, clients)
    }

    private suspend fun node(id: String) = db.nodeDao().byId(id)

    @Test
    fun `a node moved to another workspace is still reachable`() = runBlocking {
        val (_, event, clients) = setUpMove()
        ws.moveAcross(event, clients)

        val moved = node(event)
        assertNotNull("the node still exists", moved)
        assertEquals("on the list it was filed onto", clients, moved?.parentId)
        assertEquals("and in that list's workspace", "ws-work", moved?.workspaceId)
    }

    @Test
    fun `the meeting it is about survives the move`() = runBlocking {
        val (_, event, clients) = setUpMove()
        ws.moveAcross(event, clients)

        // The whole point of the node. A move that dropped `ext:` would leave the calendar unable
        // to recognise the meeting, and the next tap on it would make a second page.
        assertEquals("_60q30@napkin.ie", node(event)?.extUid)
        val row = db.eventDao().observeById(event).first()
        assertNotNull("it is still an event, at its own time", row)
        assertEquals("2026-09-16T10:00", row?.event?.startLocal)
    }

    @Test
    fun `the notes written on it come with it`() = runBlocking {
        val (_, event, clients) = setUpMove()
        ws.writer("")!!.addBlock(event, NodeType.PARAGRAPH, "agenda: pricing")
        ws.moveAcross(event, clients)

        val kids = db.nodeDao().childrenOnce(event)
        assertEquals("the note is still on the page", 1, kids.size)
        assertEquals("agenda: pricing", kids.single().title)
        assertEquals("and moved workspace with it", "ws-work", kids.single().workspaceId)
    }

    @Test
    fun `it is gone from the workspace it left`() = runBlocking {
        val (inbox, event, clients) = setUpMove()
        // Written on first, so there is a page file to follow it. An event nobody has written on
        // owns no page at all, which is why this cannot be asserted on a bare one.
        ws.writer("")!!.addBlock(event, NodeType.PARAGRAPH, "agenda: pricing")
        ws.moveAcross(event, clients)

        assertTrue(
            "no line left behind in the old Inbox",
            db.nodeDao().childrenOnce(inbox).none { it.id == event },
        )
        // The file too, or the old repo carries a page nothing names — invisible, and committed.
        assertNull("the page is gone from the old repo", ws.store("")!!.readPage(event))
        assertNotNull("and is in the new one", ws.store("ws-work")!!.readPage(event))
    }

    @Test
    fun `a move within one workspace still works the way it always did`() = runBlocking {
        val (_, event, _) = setUpMove()
        val other = ws.writer("")!!.createTopLevel(NodeType.LIST, "Later")
        ws.moveAcross(event, other)

        assertEquals(other, node(event)?.parentId)
        assertEquals("", node(event)?.workspaceId)
        assertEquals("_60q30@napkin.ie", node(event)?.extUid)
    }
}
