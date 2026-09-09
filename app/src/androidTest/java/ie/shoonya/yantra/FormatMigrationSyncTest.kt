package ie.shoonya.yantra

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.storage.encode
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.format.InkRef
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.data.format.PageDoc
import ie.shoonya.yantra.data.format.TaskRef
import ie.shoonya.yantra.data.ink.StrokeCodec
import ie.shoonya.yantra.data.ink.StrokeEnvelope
import ie.shoonya.yantra.data.sync.GitRepo
import ie.shoonya.yantra.data.sync.SyncEngine
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant

/**
 * The format change, played through the real sync engine on two devices.
 *
 * This is the scenario the shell simulation showed losing data under the old rules: one device
 * migrates the ink format and raises the manifest, the other — still busy in the old format — sets
 * an archive threshold and adds a task in the same window. Whichever rebased second used to throw
 * the other's manifest away. Every case asserts convergence *and* that nothing was lost.
 */
@RunWith(AndroidJUnit4::class)
class FormatMigrationSyncTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var origin: File

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val branch = "yantra-tasks"

    private inner class Device(val name: String) {
        val dir = File(root, name)
        val store = WorkspaceStore(dir, name)
        val repo = GitRepo(dir, branch)
        val engine = SyncEngine(store, Indexer(db), repo, name)
        val writer = WorkspaceWriter(store, db, Indexer(db), device = name)

        fun clone() {
            Git.cloneRepository().setURI(origin.toURI().toString())
                .setDirectory(dir).setBranch(branch).call().close()
        }

        fun page(id: String): PageDoc? =
            store.pageFile(id).takeIf { it.exists() }?.let { PageCodec.decode(it.readText()) }

        fun manifestText() = File(dir, WorkspaceStore.MANIFEST_PATH).readText()
    }

    private fun sampleStroke(): Stroke {
        val batch = MutableStrokeInputBatch()
        for (i in 0 until 8) batch.add(InputToolType.STYLUS, 5f + i, 7f + i, i * 10L, 0f, 0.5f, 0f, 0f)
        return Stroke(StrokeCodec.brush(StrokeCodec.FAMILY_PRESSURE_PEN, 0xFF23211CL, 2.6f), batch.toImmutable())
    }

    /** The 0.3.0 sidecar stroke: [int32 header length][header JSON][androidx.ink bytes]. */
    private fun legacyStroke(): ByteArray {
        val header = """{"family":"pressure_pen","color":${0xFF23211CL},"size":2.6,"epsilon":0.1}""".encodeToByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d -> d.writeInt(header.size); d.write(header); sampleStroke().inputs.encode(d) }
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "fmt-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        origin = File(root, "origin.git")
        Git.init().setDirectory(origin).setBare(true).setInitialBranch(branch).call().close()

        // A repository as 0.3.0 left it: manifest at format 1, a page with an ink block, and a
        // sidecar in androidx.ink's bytes.
        val seed = File(root, "seed")
        val store = WorkspaceStore(seed, "seed").also { it.scaffold("Shared", 1_000L) }
        store.writeManifest(store.readManifest()!!.copy(formatVersion = 1))
        store.writePage(
            PageDoc(
                id = "list", type = NodeType.LIST, parent = null, title = "Shared list",
                modifiedAt = Instant.ofEpochMilli(1_000L), device = "seed",
                blocks = listOf(TaskRef(id = "t1", title = "Original"), InkRef(id = "k1")),
            )
        )
        store.writeInk("k1", listOf(legacyStroke()))
        val g = GitRepo(seed, branch)
        g.init().use {
            g.commitAll(it, "seed", "Yantra", "y@shoonya.ie")
            g.addRemote(it, origin.toURI().toString())
            g.push(it, null)
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aMigrationAndAnUnrelatedManifestEditBothSurviveTheRebase() = runBlocking {
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }
        assertEquals(1, a.store.readManifest()!!.formatVersion)
        assertTrue(StrokeCodec.isLegacy(a.store.readInk("k1").single()))

        // Device A updates first: its launch migration rewrites the ink and raises the format.
        assertEquals(1, a.writer.migrateLegacyInk())
        assertEquals(2, a.store.readManifest()!!.formatVersion)
        assertTrue(a.engine.sync().ok)

        // Device B, not yet pulled, does ordinary work in the old format: sets the archive
        // threshold and adds a task.
        b.store.writeManifest(b.store.readManifest()!!.copy(archiveAfterDays = 30))
        b.store.writePage(
            b.page("list")!!.let { p -> p.copy(blocks = p.blocks + TaskRef("b1", "From B")) }
                .copy(modifiedAt = Instant.ofEpochMilli(2_000L), device = "b")
        )
        val res = b.engine.sync()
        assertTrue(res.error ?: "ok", res.ok)
        assertTrue(
            "the manifest was not merged: ${res.conflicts.map { "${it.path}: ${it.reason}" }}",
            res.conflicts.any { it.path == WorkspaceStore.MANIFEST_PATH && it.reason.contains("field by field") },
        )

        // Both edits stand on B...
        val mb = b.store.readManifest()!!
        assertEquals(2, mb.formatVersion)
        assertEquals(30, mb.archiveAfterDays)
        assertEquals(StrokeEnvelope.Kind.YANTRA, StrokeEnvelope.kind(b.store.readInk("k1").single()))
        assertTrue(b.page("list")!!.blocks.any { it is TaskRef && it.id == "b1" })

        // ...and on A after it pulls, byte for byte.
        assertTrue(a.engine.sync().ok)
        assertEquals(b.manifestText(), a.manifestText())
        assertArrayEquals(b.store.readInk("k1").single(), a.store.readInk("k1").single())
        assertTrue(a.page("list")!!.blocks.any { it is TaskRef && it.id == "b1" })
    }

    @Test
    fun aDeviceOnAnOlderBuildStillPullsButNoLongerWrites() = runBlocking {
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        // A is running a build from the future: its repository moves to a format B cannot know.
        a.store.writeManifest(a.store.readManifest()!!.copy(formatVersion = WorkspaceStore.FORMAT_VERSION + 1))
        a.store.writePage(
            a.page("list")!!.let { p -> p.copy(blocks = p.blocks + TaskRef("a1", "From the future")) }
                .copy(modifiedAt = Instant.ofEpochMilli(2_000L), device = "a")
        )
        assertTrue(a.engine.sync().ok)

        // B syncs: the pull works, the newer device's task arrives, and from this moment B is
        // read-only here.
        val res = b.engine.sync()
        assertTrue(res.error ?: "ok", res.ok)
        assertTrue(res.pulled)
        assertTrue(b.page("list")!!.blocks.any { it is TaskRef && it.id == "a1" })
        assertTrue(b.store.isReadOnly)
        assertThrows(WorkspaceWriter.WorkspaceReadOnly::class.java) {
            runBlocking { b.writer.addBlock("list", NodeType.TASK, "must not land") }
        }
        assertFalse(b.page("list")!!.blocks.any { it is TaskRef && it.title == "must not land" })

        // And nothing B does by accident can lower the version: a sync with nothing to commit
        // leaves the manifest exactly as it arrived.
        val again = b.engine.sync()
        assertTrue(again.ok)
        assertFalse(again.committed)
        assertEquals(WorkspaceStore.FORMAT_VERSION + 1, b.store.readManifest()!!.formatVersion)
    }

    @Test
    fun theManifestMergeIsSymmetric_whicheverDeviceRebasesSecond() = runBlocking {
        val a = Device("a").also { it.clone() }
        val b = Device("b").also { it.clone() }

        // The mirror image of the first case: B migrates, A edits the threshold, and this time A
        // is the one that rebases onto the other's work.
        assertEquals(1, b.writer.migrateLegacyInk())
        assertTrue(b.engine.sync().ok)
        a.store.writeManifest(a.store.readManifest()!!.copy(archiveAfterDays = 90))
        assertTrue(a.engine.sync().ok)
        assertTrue(b.engine.sync().ok)

        assertEquals(a.manifestText(), b.manifestText())
        assertEquals(2, a.store.readManifest()!!.formatVersion)
        assertEquals(90, a.store.readManifest()!!.archiveAfterDays)
        assertEquals(StrokeEnvelope.Kind.YANTRA, StrokeEnvelope.kind(a.store.readInk("k1").single()))
    }
}
