package ie.shoonya.yantra

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.format.PageDoc
import ie.shoonya.yantra.data.format.TaskRef
import ie.shoonya.yantra.data.sync.Change
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * **A write asked for on the main thread does its work somewhere else.**
 *
 * The screens call the writer from their own scope, which is the main thread, and nothing in the
 * writer ever left it: ticking a task, pressing Enter or deleting a line read and re-parsed the
 * workspace, rebuilt the index and wrote the page with the UI waiting for all of it. On the tablet a
 * single tick held the main thread for over 100 ms under the tracer.
 *
 * Measured the way a user feels it: a heartbeat posted to the main looper every 2 ms while ten
 * structural edits run from `Dispatchers.Main`. Every gap longer than a beat is time the main thread
 * could not answer. Asserted loosely — a quarter of the elapsed time — because the point is "no
 * longer blocked for the whole of it", not a device-specific number; the figures are logged.
 */
@RunWith(AndroidJUnit4::class)
class WritesLeaveTheMainThreadTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: AppDatabase

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun build(pages: Int, tasksPerPage: Int): WorkspaceStore {
        root = File(ctx.cacheDir, "main-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val store = WorkspaceStore(root).also { it.scaffold("main", 1_787_000_000_000L) }
        val stamp = Instant.ofEpochMilli(1_787_000_000_000L)
        repeat(pages) { p ->
            store.writePage(
                PageDoc(
                    id = "page-$p", type = NodeType.LIST, parent = null, title = "Page $p",
                    modifiedAt = stamp, device = null,
                    blocks = List(tasksPerPage) { t ->
                        TaskRef(
                            id = "t-$p-$t", title = "Task $t on page $p",
                            priority = if (t % 3 == 0) "High" else null,
                            labels = if (t % 4 == 0) listOf("sync") else emptyList(),
                        )
                    },
                )
            )
        }
        return store
    }

    @Test
    fun structuralEditsLeaveTheMainThreadFree() = runBlocking {
        val store = build(pages = 80, tasksPerPage = 20)
        val indexer = Indexer(db)
        // No scope: every write rebuilds the index inline, which is what a structural change does.
        val writer = WorkspaceWriter(store, db, indexer, device = "test")
        indexer.rebuild(store)
        repeat(2) { i -> writer.editTask("t-0-0", Change.STRUCTURAL) { it.copy(title = "warm $i") } }

        val main = Handler(Looper.getMainLooper())
        val beats = ArrayList<Long>()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val beat = object : Runnable {
            override fun run() {
                beats += SystemClock.uptimeMillis()
                if (running.get()) main.postDelayed(this, 2)
            }
        }
        main.post(beat)
        Thread.sleep(50)

        val started = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) {
            repeat(10) { i -> writer.editTask("t-0-${i % 20}", Change.STRUCTURAL) { it.copy(title = "edit $i") } }
        }
        val elapsed = SystemClock.uptimeMillis() - started
        running.set(false)
        Thread.sleep(50)

        val during = withContext(Dispatchers.Main) { beats.filter { it >= started - 2 }.toList() }
        val blocked = during.zipWithNext { a, b -> (b - a - BEAT_SLACK_MS).coerceAtLeast(0) }.sum()
        val longest = during.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0

        android.util.Log.w(
            "MainThread",
            "10 structural edits on 80×20: ${elapsed}ms elapsed, main thread blocked ${blocked}ms, " +
                "longest gap ${longest}ms",
        )
        assertTrue(
            "the main thread was blocked for ${blocked}ms of ${elapsed}ms — the writes are running on it",
            blocked < elapsed / 4,
        )
    }

    private companion object {
        /** A gap up to this long is scheduling, not blocking. */
        const val BEAT_SLACK_MS = 6L
    }
}
