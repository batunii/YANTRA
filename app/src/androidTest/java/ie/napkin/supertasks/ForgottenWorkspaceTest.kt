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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What a forgotten workspace leaves behind.
 *
 * It used to leave everything. Removing one deleted its files, its credentials and its registry
 * entry, and never touched the index — so its lists went on appearing on Home and in Today,
 * belonging to a repository that was no longer on the device.
 *
 * Worse than untidy: they could not be removed by hand either. A write is routed by the node's
 * `workspace_id`, and with no writer registered under that id it fell through to the primary
 * workspace, which has no such page. Delete succeeded at doing nothing, every time, with no error
 * to explain why — which is exactly what it looks like when an app is broken.
 */
@RunWith(AndroidJUnit4::class)
class ForgottenWorkspaceTest {

    private lateinit var db: AppDatabase
    private lateinit var ws: Workspaces
    private lateinit var indexer: Indexer
    private lateinit var root: File

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "forgotten-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        indexer = Indexer(db)
        ws = Workspaces(db, indexer, "test-device")
        ws.open("", File(root, "local").apply { mkdirs() }, "Personal")
        WorkspaceSeeder.seed(ws.primaryStore())
        ws.reindexAll()
    }

    @After
    fun tearDown() = db.close()

    /** A second workspace with a list of its own, indexed and present. */
    private suspend fun joinedWorkspace(id: String): Int {
        val dir = File(root, id).apply { mkdirs() }
        ws.open(id, dir, "Joined")
        WorkspaceSeeder.seedLinked(ws.store(id)!!, "Joined")
        ws.reindexAll()
        return db.nodeDao().countNodes(id)
    }

    @Test
    fun forgettingAWorkspaceTakesItsRowsWithIt() = runBlocking {
        val id = "49e77524-0698-4bab-a5cd-7ecd1b5acc52"
        assertTrue("the joined workspace indexed nothing", joinedWorkspace(id) > 0)
        val personalBefore = db.nodeDao().countNodes("")

        // What forgetWorkspace does to the index, which is the half that was missing.
        ws.close(id)
        File(root, id).deleteRecursively()
        indexer.purge(id)
        ws.reindexAll()

        assertEquals("its lists are still on Home", 0, db.nodeDao().countNodes(id))
        assertEquals(
            "forgetting one workspace disturbed another",
            personalBefore,
            db.nodeDao().countNodes(""),
        )
    }

    @Test
    fun rowsAlreadyOrphanedAreSweptOut() = runBlocking {
        // A device that forgot a workspace before the purge existed is still carrying its rows, and
        // nothing else on the device knows to look for them — the files and the registry entry are
        // long gone, so the index is the only place the workspace still is.
        val id = "191e5325-36e9-480a-ab11-f4994a3df1de"
        joinedWorkspace(id)
        ws.close(id)
        File(root, id).deleteRecursively()
        assertTrue("nothing to sweep", db.nodeDao().countNodes(id) > 0)

        val known = ws.all.map { it.id }.toSet()
        db.nodeDao().indexedWorkspaces().filterNot { it in known }.forEach { indexer.purge(it) }

        assertEquals(0, db.nodeDao().countNodes(id))
        assertTrue("the sweep took the open workspace too", db.nodeDao().countNodes("") > 0)
    }

    @Test
    fun anOpenWorkspaceIsNeverSwept() = runBlocking {
        // The sweep keys on what is open. Getting this wrong empties a live workspace.
        val id = "kept-open"
        val before = joinedWorkspace(id)

        val known = ws.all.map { it.id }.toSet()
        db.nodeDao().indexedWorkspaces().filterNot { it in known }.forEach { indexer.purge(it) }

        assertEquals("an open workspace was swept", before, db.nodeDao().countNodes(id))
    }
}
