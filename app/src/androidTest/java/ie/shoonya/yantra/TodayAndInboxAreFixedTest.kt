package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.db.SystemKey
import ie.shoonya.yantra.data.repo.NodeRepository
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.seed.WorkspaceSeeder
import ie.shoonya.yantra.data.workspace.Workspaces
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Today and Inbox cannot be deleted or renamed — by anything.**
 *
 * Capture, the widgets and the app's opening screen all lean on these two. Deleting either used to
 * be allowed, and the app then quietly made a fresh one the next time it needed it: an Inbox with
 * none of the old one's tasks, a Today with none of its rules, which reads as the app losing work.
 * Home and the list screens no longer offer either action for them; this holds the line under the
 * screens, at the repository every path goes through.
 */
@RunWith(AndroidJUnit4::class)
class TodayAndInboxAreFixedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var ws: Workspaces
    private lateinit var nodes: NodeRepository

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "fixed-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        ws = Workspaces(db, Indexer(db), "test-device")
        ws.open("", root, "Test")
        WorkspaceSeeder.seed(ws.primaryStore(), 1_787_000_000_000L)
        ws.reindexAll()
        nodes = NodeRepository(db, ws)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun todayAndInboxSurviveADelete() = runBlocking {
        val today = nodes.todaySmartList()!!
        val inbox = nodes.inboxList()

        nodes.delete(today.id)
        nodes.delete(inbox)

        assertEquals(today.id, db.nodeDao().bySystemKey(SystemKey.TODAY)?.id)
        assertEquals(inbox, db.nodeDao().bySystemKey(SystemKey.INBOX)?.id)
    }

    @Test
    fun todayAndInboxKeepTheirNames() = runBlocking {
        val today = nodes.todaySmartList()!!
        val inbox = nodes.inboxList()
        val inboxTitle = db.nodeDao().byId(inbox)?.title

        nodes.rename(today.id, "Whenever")
        nodes.rename(inbox, "Dump")

        assertEquals(today.title, db.nodeDao().byId(today.id)?.title)
        assertEquals(inboxTitle, db.nodeDao().byId(inbox)?.title)
    }

    @Test
    fun anOrdinaryListCanStillBeRenamedAndDeleted() = runBlocking {
        val list = ws.primary().createTopLevel(NodeType.LIST, "Errands")

        nodes.rename(list, "Chores")
        assertEquals("Chores", db.nodeDao().byId(list)?.title)

        nodes.delete(list)
        assertNull(db.nodeDao().byId(list))
    }
}
