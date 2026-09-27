package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.db.TaskOriginEntity
import ie.shoonya.yantra.data.sync.TaskOrigins
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Who added a task, end to end: stamped on the line by the writer, carried into the index, kept when
 * the task earns a page or changes kind, and read out of git for a line that has no stamp.
 */
@RunWith(AndroidJUnit4::class)
class ATaskSaysWhoAddedItTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "author-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = WorkspaceStore(root, "ws").also { it.scaffold("test", 1_787_000_000_000L) }
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun writerAs(login: String?) =
        WorkspaceWriter(store, db, Indexer(db), device = "test-device", author = { login })

    private fun pageText(id: String) = store.pageFile(id).readText()

    @Test
    fun aNewTaskIsStampedWithWhoeverIsSignedIn() = runBlocking {
        val writer = writerAs("sai").also { it.reindex() }
        val list = writer.createTopLevel(NodeType.LIST, "Shared")
        val task = writer.addBlock(list, NodeType.TASK, "Draft the API doc")

        assertTrue(pageText(list).contains("- [ ] Draft the API doc ^$task by:sai"))
        assertEquals("sai", db.nodeDao().byId(task)?.author)
        assertEquals("sai", db.originDao().authorsUnder(list).first().single().login)
    }

    @Test
    fun nobodySignedInWritesNoStamp() = runBlocking {
        val writer = writerAs(null).also { it.reindex() }
        val list = writer.createTopLevel(NodeType.LIST, "Mine")
        writer.addBlock(list, NodeType.TASK, "Buy milk")

        assertFalse(pageText(list).contains("by:"))
        assertTrue(db.originDao().authorsUnder(list).first().isEmpty())
    }

    @Test
    fun aTaskKeepsItsAuthorWhenItEarnsAPage() = runBlocking {
        // The reconciler copies a line's fields onto the page's own row. A field it forgets is lost
        // the moment the task gets its first note — which is how ext_uid went missing once.
        val writer = writerAs("sai").also { it.reindex() }
        val list = writer.createTopLevel(NodeType.LIST, "Shared")
        val task = writer.addBlock(list, NodeType.TASK, "Draft the API doc")
        writer.addBlock(task, NodeType.PARAGRAPH, "Notes on the endpoints")

        assertTrue(store.pageFile(task).exists())
        assertEquals("sai", db.nodeDao().byId(task)?.author)
    }

    @Test
    fun convertingATaskDoesNotChangeWhoAddedIt() = runBlocking {
        val list = writerAs("sai").also { it.reindex() }.createTopLevel(NodeType.LIST, "Shared")
        val task = writerAs("sai").addBlock(list, NodeType.TASK, "Draft the API doc")

        // Somebody else converts it task-to-task; the line is rebuilt, and must keep its author.
        writerAs("bob").convertBlock(task, NodeType.TASK)
        assertTrue(pageText(list).contains("by:sai"))
        assertFalse(pageText(list).contains("by:bob"))
    }

    @Test
    fun aLineBecomingATaskIsAddedByWhoeverMadeItOne() = runBlocking {
        val sai = writerAs("sai").also { it.reindex() }
        val list = sai.createTopLevel(NodeType.LIST, "Shared")
        val note = sai.addBlock(list, NodeType.PARAGRAPH, "Call the venue")
        val task = writerAs("bob").convertBlock(note, NodeType.TASK)

        assertEquals("bob", db.nodeDao().byId(task)?.author)
    }

    @Test
    fun theLineWinsOverTheHistory() = runBlocking {
        val writer = writerAs(null).also { it.reindex() }
        val list = writer.createTopLevel(NodeType.LIST, "Shared")
        val plain = writer.addBlock(list, NodeType.TASK, "Unstamped")
        val stamped = writerAs("sai").addBlock(list, NodeType.TASK, "Stamped")
        db.originDao().insertAll(
            listOf(TaskOriginEntity("ws", plain, "bob"), TaskOriginEntity("ws", stamped, "bob"))
        )

        val byId = db.originDao().authorsUnder(list).first().associate { it.nodeId to it.login }
        assertEquals("bob", byId[plain])
        assertEquals("sai", byId[stamped])
    }

    @Test
    fun theHistoryCreditsTheFirstCommitAndNotTheLaterEdit() = runBlocking {
        val repoDir = File(root, "repo").apply { mkdirs() }
        val page = File(repoDir, "p1.md")
        Git.init().setDirectory(repoDir).setInitialBranch("main").call().use { git ->
            fun commit(text: String, name: String, email: String) {
                page.writeText(text)
                git.add().addFilepattern(".").call()
                git.commit().setMessage("sync").setAuthor(name, email).setCommitter(name, email).call()
            }
            commit("- [ ] Old task ^t0\n", "Yantra", "yantra@napkin.ie")
            commit("- [ ] Old task ^t0\n- [ ] Draft ^t1\n", "sai", "sai@users.noreply.github.com")
            commit("- [x] Old task ^t0\n- [ ] Draft, renamed ^t1\n", "bob", "bob@users.noreply.github.com")

            val origins = TaskOrigins(db, kotlinx.coroutines.GlobalScope)
            origins.scan("ws", git.repository)

            val rows = db.originDao().authorsOf(listOf("t0", "t1")).first()
            // t0 has no row with a login: the app's old signature names nobody. The node table is
            // empty in this test, so the join has nothing to return for either — read the cache.
            assertTrue(rows.isEmpty())
        }
        val direct = db.query("SELECT node_id, login FROM task_origin ORDER BY node_id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        assertEquals(listOf("t0" to null, "t1" to "sai"), direct)
    }

    @Test
    fun readingAgainFindsNothingNewAndChangesNothing() = runBlocking {
        val repoDir = File(root, "repo2").apply { mkdirs() }
        Git.init().setDirectory(repoDir).setInitialBranch("main").call().use { git ->
            File(repoDir, "p1.md").writeText("- [ ] Draft ^t1\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("sync").setAuthor("sai", "sai@users.noreply.github.com")
                .setCommitter("sai", "sai@users.noreply.github.com").call()
            val origins = TaskOrigins(db, kotlinx.coroutines.GlobalScope)
            origins.scan("ws", git.repository)
            origins.scan("ws", git.repository)
        }
        val count = db.query("SELECT COUNT(*) FROM task_origin", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count)
    }
}
