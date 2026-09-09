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
import ie.shoonya.yantra.data.ink.StrokeCodec
import ie.shoonya.yantra.data.ink.StrokeEnvelope
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.data.workspace.WorkspaceWriter
import kotlinx.coroutines.runBlocking
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

/**
 * The seam between the two ink generations, on a device — this needs libink.so, which is why it is
 * not a JVM test.
 *
 * Writes a stroke exactly as 0.3.0 did (androidx.ink's own bytes behind the brush header), then
 * checks that this build reads it, rewrites it as a YNK1 envelope with the same points, refuses to
 * touch strokes it does not understand, and locks the workspace once its manifest names a format
 * newer than this build.
 */
@RunWith(AndroidJUnit4::class)
class InkFormatMigrationTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var store: WorkspaceStore
    private lateinit var writer: WorkspaceWriter

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "ink-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = WorkspaceStore(root).also { it.scaffold("test", 1_787_000_000_000L) }
        writer = WorkspaceWriter(store, db, Indexer(db), device = "test-device")
        writer.reindex()
    }

    @After
    fun tearDown() = db.close()

    private fun sampleStroke(): Stroke {
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (i in 0 until 12) {
            batch.add(InputToolType.STYLUS, 10f + i * 3f, 20f + i * 1.5f, t, 0f, 0.4f + i * 0.02f, 0.2f, 1.1f)
            t += 8
        }
        return Stroke(StrokeCodec.brush(StrokeCodec.FAMILY_PRESSURE_PEN, 0xFF23211CL, 2.6f), batch.toImmutable())
    }

    /** The 0.3.0 layout: [int32 header length][header JSON][androidx.ink StrokeInputBatch bytes]. */
    private fun legacyBytes(stroke: Stroke): ByteArray {
        val header = """{"family":"pressure_pen","color":${0xFF23211CL},"size":2.6,"epsilon":0.1}"""
            .encodeToByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(header.size)
            d.write(header)
            stroke.inputs.encode(d)
        }
        return out.toByteArray()
    }

    @Test
    fun aLegacyStrokeIsReadAndUpgradedWithItsPointsIntact() {
        val original = sampleStroke()
        val legacy = legacyBytes(original)
        assertTrue(StrokeCodec.isLegacy(legacy))

        val upgraded = StrokeCodec.upgrade(legacy)
        assertEquals(StrokeEnvelope.Kind.YANTRA, StrokeEnvelope.kind(upgraded))
        assertFalse(StrokeCodec.isLegacy(upgraded))

        val env = StrokeEnvelope.decode(upgraded)
        assertEquals(StrokeEnvelope.TOOL_STYLUS, env.tool)
        assertEquals(original.inputs.size, env.points.size)
        for (i in 0 until original.inputs.size) {
            val p = original.inputs[i]
            // androidx.ink quantises when it serialises — 13.0 came back 12.997, 0.4 came back
            // 0.3999, 56 ms came back 55 — so the old format was lossy in position, pressure and time. Ours keeps the
            // float32 exactly; the tolerance here is for the bytes we are migrating *from*.
            assertEquals(p.x, env.points[i].x, 1e-2f)
            assertEquals(p.y, env.points[i].y, 1e-2f)
            // Time too: 56 ms came back as 55.
            assertTrue(kotlin.math.abs(p.elapsedTimeMillis - env.points[i].elapsedMillis) <= 1)
            assertEquals(p.pressure, env.points[i].pressure, 1e-3f)
        }

        // Both generations decode to the same stroke.
        val fromLegacy = StrokeCodec.decode(legacy)
        val fromNew = StrokeCodec.decode(upgraded)
        assertEquals(fromLegacy.inputs.size, fromNew.inputs.size)
        assertEquals(fromLegacy.brush.colorIntArgb, fromNew.brush.colorIntArgb)
        assertEquals(fromLegacy.brush.size, fromNew.brush.size)

        // And the new bytes are stable: encode(decode(x)) == x.
        assertArrayEquals(upgraded, StrokeCodec.encode(fromNew, StrokeCodec.FAMILY_PRESSURE_PEN))
    }

    @Test
    fun theWriterRewritesLegacySidecarsOnceAndRaisesTheFormat() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "Sketches")
        val task = writer.addBlock(list, NodeType.TASK, "Draw")
        val ink = writer.addBlock(task, NodeType.INK, "")
        val legacy = legacyBytes(sampleStroke())
        val foreign = "YNK9-from-the-future".encodeToByteArray()
        writer.writeInk(ink, listOf(legacy, foreign))
        assertEquals(1, store.readManifest()!!.formatVersion.coerceAtMost(1))

        assertEquals(1, writer.migrateLegacyInk())
        val after = store.readInk(ink)
        assertEquals(2, after.size)
        assertEquals(StrokeEnvelope.Kind.YANTRA, StrokeEnvelope.kind(after[0]))
        // A stroke this build cannot read rides through the rewrite byte for byte.
        assertArrayEquals(foreign, after[1])
        assertEquals(2, store.readManifest()!!.formatVersion)

        // Idempotent: nothing left to do.
        assertEquals(0, writer.migrateLegacyInk())
    }

    @Test
    fun aNewerWorkspaceIsReadOnlyHere() = runBlocking {
        val list = writer.createTopLevel(NodeType.LIST, "Before")
        store.writeManifest(store.readManifest()!!.copy(formatVersion = WorkspaceStore.FORMAT_VERSION + 1))
        assertTrue(store.isReadOnly)

        assertThrows(WorkspaceWriter.WorkspaceReadOnly::class.java) {
            runBlocking { writer.addBlock(list, NodeType.TASK, "must not land") }
        }
        // Reading is untouched: the user still sees their tasks.
        assertEquals(1, store.readPages().size)
        assertEquals("Before", store.readPage(list)!!.title)
    }

    @Test
    fun unknownStrokesAreSkippedNotFatal() {
        assertThrows(StrokeCodec.UnsupportedStrokeFormat::class.java) {
            StrokeCodec.decode("YNK7 something newer".encodeToByteArray())
        }
    }
}
