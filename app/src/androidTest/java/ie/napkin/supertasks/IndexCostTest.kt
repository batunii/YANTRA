package ie.napkin.supertasks

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.format.InkRef
import ie.napkin.supertasks.data.format.PageDoc
import ie.napkin.supertasks.data.format.TaskRef
import ie.napkin.supertasks.data.workspace.Indexer
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import ie.napkin.supertasks.data.workspace.WorkspaceWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * What one keystroke actually costs.
 *
 * Not an assertion, a measurement — it prints and never fails, because the number depends on the
 * device and a threshold here would only ever be flaky. It exists to answer "is the index rebuild a
 * real problem or a theoretical one" with a figure rather than an opinion.
 */
@RunWith(AndroidJUnit4::class)
class IndexCostTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: AppDatabase

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun build(pages: Int, tasksPerPage: Int, inkBlocks: Int, strokesEach: Int): WorkspaceStore {
        root = File(ctx.cacheDir, "cost-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val store = WorkspaceStore(root).also { it.scaffold("cost", 1_787_000_000_000L) }
        val stamp = Instant.ofEpochMilli(1_787_000_000_000L)

        repeat(pages) { p ->
            val blocks = buildList {
                repeat(tasksPerPage) { t ->
                    // Real tasks carry properties and labels; a benchmark of bare titles would
                    // flatter the property_value and label tables by leaving them empty.
                    add(
                        TaskRef(
                            id = "t-$p-$t",
                            title = "Task $t on page $p",
                            priority = if (t % 3 == 0) "High" else null,
                            labels = if (t % 4 == 0) listOf("sync") else emptyList(),
                        )
                    )
                }
                if (p < inkBlocks) add(InkRef("ink-$p", 0))
            }
            store.writePage(
                PageDoc(
                    id = "page-$p", type = NodeType.LIST, parent = null, title = "Page $p",
                    modifiedAt = stamp, device = null, blocks = blocks,
                )
            )
        }
        // A stroke blob is a few hundred bytes in practice; this is deliberately conservative.
        repeat(inkBlocks) { i ->
            store.writeInk("ink-$i", List(strokesEach) { ByteArray(400) { b -> (b + i).toByte() } })
        }
        return store
    }

    private fun report(label: String, pages: Int, tasks: Int, inkBlocks: Int, strokes: Int): Unit = runBlocking {
        val store = build(pages, tasks, inkBlocks, strokes)
        val indexer = Indexer(db)
        val writer = WorkspaceWriter(store, db, indexer, device = "bench")

        repeat(3) { indexer.rebuild(store) }        // warm the page cache and JIT

        val runs = 10
        val started = System.nanoTime()
        repeat(runs) { indexer.rebuild(store) }
        val perRebuild = (System.nanoTime() - started) / runs / 1_000_000.0

        // What a keystroke costs end to end, rebuilding the index inline every time — the old
        // behaviour, and still what a caller with no scope gets.
        val typed = System.nanoTime()
        repeat(runs) { i ->
            writer.editTask("t-0-0") { it.copy(title = "Task 0 on page 0 $i") }
        }
        val perKeystroke = (System.nanoTime() - typed) / runs / 1_000_000.0

        // And what the same burst costs when the rebuild is allowed to wait for the typing to stop,
        // which is what the app does. Measured to the point where the index is actually current, so
        // the deferred work is counted rather than hidden.
        val buffered = WorkspaceWriter(store, db, indexer, device = "bench", scope = this)
        val burstStart = System.nanoTime()
        repeat(runs) { i -> buffered.editTask("t-0-0") { it.copy(title = "Burst $i") } }
        buffered.flushIndex()
        val perBurstKeystroke = (System.nanoTime() - burstStart) / runs / 1_000_000.0

        // Where the remaining time actually goes, so the next optimisation is aimed rather than
        // guessed: reading and mapping every file, versus rewriting every row in Room.
        val readStart = System.nanoTime()
        var idx: ie.napkin.supertasks.data.workspace.WorkspaceIndex? = null
        repeat(runs) {
            idx = ie.napkin.supertasks.data.workspace.WorkspaceReconciler.read(store, System.currentTimeMillis())
        }
        val perRead = (System.nanoTime() - readStart) / runs / 1_000_000.0

        val applyStart = System.nanoTime()
        repeat(runs) { indexer.apply(idx!!, store.id) }
        val perApply = (System.nanoTime() - applyStart) / runs / 1_000_000.0

        // Is the floor the work, or the transaction ceremony around it?
        val emptyStart = System.nanoTime()
        repeat(runs) { db.withTransaction { } }
        val perEmpty = (System.nanoTime() - emptyStart) / runs / 1_000_000.0

        android.util.Log.w("IndexCost", "$label — empty transaction ${"%.1f".format(perEmpty)}ms")

        android.util.Log.w(
            "IndexCost",
            "$label — read+map ${"%.1f".format(perRead)}ms, room ${"%.1f".format(perApply)}ms",
        )
        android.util.Log.w(
            "IndexCost",
            "$label — ${pages}p × ${tasks}t + ${inkBlocks} ink × $strokes strokes: " +
                "rebuild ${"%.1f".format(perRebuild)}ms, " +
                    "keystroke inline ${"%.1f".format(perKeystroke)}ms, " +
                    "buffered ${"%.1f".format(perBurstKeystroke)}ms",
        )
    }

    @Test fun tiny(): Unit = report("tiny", pages = 5, tasks = 4, inkBlocks = 1, strokes = 20)
    @Test fun realistic(): Unit = report("realistic", pages = 30, tasks = 12, inkBlocks = 6, strokes = 120)
    @Test fun heavy(): Unit = report("heavy", pages = 80, tasks = 20, inkBlocks = 15, strokes = 300)
}
