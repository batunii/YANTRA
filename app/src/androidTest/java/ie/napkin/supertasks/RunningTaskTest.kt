package ie.napkin.supertasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.repo.NodeRepository
import ie.napkin.supertasks.data.seed.WorkspaceSeeder
import ie.napkin.supertasks.data.workspace.Indexer
import ie.napkin.supertasks.data.workspace.Workspaces
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
 * What you have on the go.
 *
 * Two arities, and keeping them straight is the whole point of these:
 *
 * - **Many tasks may be in progress.** Having several things started is the ordinary shape of a day,
 *   and an app that allows only one makes you lie about the rest. The bar shows them as a stack.
 * - **Only one may be timed.** A focus session measures attention, and there is one of that. That
 *   limit lives in [ie.napkin.supertasks.domain.FocusTimer], which ends the previous session when a
 *   new one starts, and is not this file's subject.
 *
 * These go through [NodeRepository] rather than the writer beneath it, because the flag lives on the
 * task's line in the workspace and the repository is the seam where the app writes it.
 */
@RunWith(AndroidJUnit4::class)
class RunningTaskTest {

    private lateinit var db: AppDatabase
    private lateinit var ws: Workspaces
    private lateinit var nodes: NodeRepository
    private lateinit var root: File

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "running-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        ws = Workspaces(db, Indexer(db), "test-device")
        ws.open("", root, "Test")
        WorkspaceSeeder.seed(ws.primaryStore(), 1_787_000_000_000L)
        ws.reindexAll()
        nodes = NodeRepository(db, ws)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun task(title: String): String {
        val list = nodes.create(null, NodeType.LIST, "Host $title")
        return nodes.create(list, NodeType.TASK, title)
    }

    private suspend fun inProgress(id: String) = db.nodeDao().byId(id)?.inProgress == true

    private suspend fun stack() = nodes.inProgress().first().map { it.id }

    @Test
    fun severalTasksCanBeOnTheGoAtOnce() = runBlocking {
        val a = task("Reading the spec")
        val b = task("Fixing the bar")
        val c = task("Waiting on review")

        nodes.setInProgress(a, true)
        nodes.setInProgress(b, true)
        nodes.setInProgress(c, true)

        assertTrue(inProgress(a))
        assertTrue(inProgress(b))
        assertTrue(inProgress(c))
        assertEquals("all three should be in the stack", 3, stack().size)
    }

    @Test
    fun puttingOneDownLeavesTheRest() = runBlocking {
        val a = task("Kept")
        val b = task("Dropped")
        nodes.setInProgress(a, true)
        nodes.setInProgress(b, true)

        nodes.setInProgress(b, false)
        assertTrue("putting one down took the other with it", inProgress(a))
        assertFalse(inProgress(b))
        assertEquals(listOf(a), stack())
    }

    @Test
    fun theMarkReachesTheFileNotOnlyTheIndex() = runBlocking {
        // The flag lives on the task's line in the workspace, and that is the copy that syncs. A
        // write that only updated the index would leave the other device seeing nothing.
        val a = task("Started here")
        val b = task("Also started")
        nodes.setInProgress(a, true)
        nodes.setInProgress(b, true)
        nodes.setInProgress(a, false)

        ws.reindexAll()
        assertFalse("the clear never reached the file", inProgress(a))
        assertTrue("the mark never reached the file", inProgress(b))
    }

    @Test
    fun startingTheSameTaskTwiceIsNotAToggle() = runBlocking {
        val only = task("The one task")
        nodes.setInProgress(only, true)
        nodes.setInProgress(only, true)
        assertTrue("starting a task twice put it out", inProgress(only))
    }

    @Test
    fun completingATaskTakesItOutOfTheStack() = runBlocking {
        // Enforced at the DAO — a finished task is not still being worked on — and worth pinning
        // here, because the stack derives from this flag and would otherwise keep dealing a card
        // for a task that has been ticked off.
        val done = task("Finish me")
        val other = task("Still going")
        nodes.setInProgress(done, true)
        nodes.setInProgress(other, true)

        nodes.setDone(done, true)
        assertFalse("a completed task kept its mark", inProgress(done))
        assertEquals(listOf(other), stack())
    }

    @Test
    fun theNewestPickUpIsDealtFirst() = runBlocking {
        // Newest first, so the thing you just started is the card on top — which is where you would
        // look for it, and it saves a swipe in the common case.
        val first = task("Picked up first")
        val second = task("Picked up second")
        nodes.setInProgress(first, true)
        nodes.setInProgress(second, true)

        assertEquals(listOf(second, first), stack())
    }
}
