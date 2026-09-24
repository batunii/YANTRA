package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A write never happens on the thread that draws.
 *
 * **The crash this exists for.** Every write ends in a file — read the page, transform it, write it
 * back atomically: open, fsync, rename. The writer never named a dispatcher, so it ran on whoever
 * called it, and that is `viewModelScope`, which is `Dispatchers.Main.immediate`. Fifty-nine call
 * sites across the screens, every one of them writing to disk on the main thread.
 *
 * It surfaced as a freeze rather than a crash, which is why nothing caught it: tearing a view model
 * down during a package replace left the main thread inside `openat()` in an uninterruptible wait,
 * past the ten seconds the system allows for input, and the process was killed. The trace read
 * `ViewModelStore.clear → cancel → editPage → writePage → writeBytesAtomically`, `state=D`.
 *
 * So the assertion is about *where* the work runs, not whether it succeeds — the transform is
 * invoked inside the lock, so the thread it sees is the thread the file work is done on.
 */
@RunWith(AndroidJUnit4::class)
class WritesLeaveTheMainThreadTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore
    private lateinit var writer: WorkspaceWriter

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "mainthread-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = WorkspaceStore(root).also { it.scaffold("test", 1_787_000_000_000L) }
        writer = WorkspaceWriter(store, db, Indexer(db), device = "test-device")
        writer.reindex()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun anEditAskedForOnTheMainThreadIsNotDoneOnIt() = runBlocking {
        val page = writer.createTopLevel(NodeType.LIST, "Notes")
        var wroteOn: String? = null

        // Asked for exactly as a screen asks: from the main thread, which is where viewModelScope
        // runs. Before the fix the transform — and the file write after it — ran right here.
        withContext(Dispatchers.Main) {
            writer.editPage(page) { doc ->
                wroteOn = Thread.currentThread().name
                doc.copy(title = "Notes, renamed")
            }
        }

        assertNotNull("the edit ran at all", wroteOn)
        assertNotEquals("a file write must not happen on the thread that draws", "main", wroteOn)
    }

    @Test
    fun theEditStillTakesEffect() = runBlocking {
        // Moving work off a thread is only a fix if the work still happens.
        val page = writer.createTopLevel(NodeType.LIST, "Notes")
        withContext(Dispatchers.Main) {
            writer.editPage(page) { doc -> doc.copy(title = "Renamed elsewhere") }
        }
        assertEquals("Renamed elsewhere", db.nodeDao().byId(page)?.title)
    }

    @Test
    fun aWriteAskedForOffTheMainThreadIsUnaffected() = runBlocking {
        // The background callers — sync, workers — must not be made worse by this.
        val page = writer.createTopLevel(NodeType.LIST, "Notes")
        var wroteOn: String? = null
        withContext(Dispatchers.IO) {
            writer.editPage(page) { doc ->
                wroteOn = Thread.currentThread().name
                doc.copy(title = "From a worker")
            }
        }
        assertNotEquals("main", wroteOn)
        assertEquals("From a worker", db.nodeDao().byId(page)?.title)
    }
}
