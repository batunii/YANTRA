package ie.shoonya.yantra

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.db.startedByList
import ie.shoonya.yantra.data.repo.FocusRepository
import ie.shoonya.yantra.data.repo.NodeRepository
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.Workspaces
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Two people on one shared workspace, through the real repositories: who is on a task, and whose
 * time is whose.
 */
@RunWith(AndroidJUnit4::class)
class InProgressBelongsToWhoStartedItTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var ws: Workspaces
    private lateinit var nodes: NodeRepository
    private val prefsNames = ArrayList<String>()

    /** Who is signed in to the shared workspace, switched mid-test to play the other person. */
    private var who: String? = "sai"

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "onit-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        ws = Workspaces(db, Indexer(db), "test-device", authorOf = { who })
        ws.open("shared", root, "Shared")
        ws.reindexAll()
        nodes = NodeRepository(db, ws)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
        prefsNames.forEach { ctx.deleteSharedPreferences(it) }
    }

    private suspend fun aTask(): Pair<String, String> {
        val list = nodes.create(null, NodeType.LIST, "Shared list", workspaceId = "shared")
        return list to nodes.create(list, NodeType.TASK, "Draft the doc")
    }

    private fun pageText(id: String) = ws.store("shared")!!.pageFile(id).readText()

    private fun devicePrefs() = "focus-${System.nanoTime()}".also { prefsNames += it }
        .let { ctx.getSharedPreferences(it, Context.MODE_PRIVATE) }

    @Test
    fun startingATaskNamesWhoStartedIt() = runBlocking {
        val (list, task) = aTask()
        nodes.setInProgress(task, true)

        assertTrue(pageText(list).contains("- [~] Draft the doc ^$task on:sai"))
        assertEquals(listOf("sai"), db.nodeDao().byId(task)!!.startedByList)
    }

    @Test
    fun twoPeopleCanBeOnOneTaskAndEachPutsOnlyThemselvesDown() = runBlocking {
        val (list, task) = aTask()
        nodes.setInProgress(task, true)
        who = "bob"
        nodes.setInProgress(task, true)
        assertTrue(pageText(list).contains("on:sai on:bob"))

        // Bob stops; Sai is still on it, so it stays in progress.
        nodes.setInProgress(task, false)
        val afterBob = db.nodeDao().byId(task)!!
        assertTrue(afterBob.inProgress)
        assertEquals(listOf("sai"), afterBob.startedByList)

        who = "sai"
        nodes.setInProgress(task, false)
        val afterSai = db.nodeDao().byId(task)!!
        assertFalse(afterSai.inProgress)
        assertFalse(pageText(list).contains("on:"))
    }

    @Test
    fun finishingATaskTakesEverybodyOffIt() = runBlocking {
        val (list, task) = aTask()
        nodes.setInProgress(task, true)
        who = "bob"
        nodes.setInProgress(task, true)
        nodes.setDone(task, true)

        assertFalse(pageText(list).contains("on:"))
        assertTrue(db.nodeDao().byId(task)!!.startedByList.isEmpty())
    }

    @Test
    fun aTimerComesBackOnlyOnTheDeviceThatStartedIt() = runBlocking {
        val (_, task) = aTask()
        val phone = FocusRepository(db, ws, devicePrefs()) { setOf("sai") }
        val mine = phone.startSession(task, 0)

        // A teammate starts one on their own device, and it syncs into the same log — newer than
        // ours, which is exactly the one the old restore would have picked.
        who = "bob"
        Thread.sleep(5)
        FocusRepository(db, ws, devicePrefs()) { setOf("bob") }.startSession(task, 0)
        ws.reindexAll()

        assertEquals(mine, phone.openSession()?.id)
        phone.endSession(mine, 120, "stopped")
        // Ours ended; theirs is still open, and is still not ours to resume.
        assertNull(phone.openSession())
    }

    @Test
    fun statsCountOnlyYourTimeButATaskKeepsEverybodys() = runBlocking {
        val (_, task) = aTask()
        val sai = FocusRepository(db, ws, devicePrefs()) { setOf("sai") }
        val s1 = sai.startSession(task, 0)
        sai.endSession(s1, 120, "stopped")
        who = "bob"
        val bob = FocusRepository(db, ws, devicePrefs()) { setOf("bob") }
        val s2 = bob.startSession(task, 0)
        bob.endSession(s2, 300, "stopped")
        ws.reindexAll()

        assertEquals(listOf(s1), sai.mine().first().map { it.id })
        assertEquals(setOf(s1, s2), sai.forNode(task).first().map { it.id }.toSet())
        assertEquals(s1, sai.lastSession()?.id)
    }
}
