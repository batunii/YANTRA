package ie.shoonya.yantra

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import ie.shoonya.yantra.data.ink.PAGE_WIDTH_DU
import ie.shoonya.yantra.data.ink.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The codec against real strokes, on a device, because `androidx.ink` is native-backed.
 *
 * The parts of this that are arithmetic or JSON live in `InkCoordinatesTest` and run on the JVM.
 * What is left is what genuinely needs `libink.so`: building a `Stroke`, serialising its input
 * batch, and getting the same geometry back.
 */
@RunWith(AndroidJUnit4::class)
class InkCodecInstrumentedTest {

    private fun stroke(vararg xy: Float): Stroke {
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (i in xy.indices step 2) {
            batch.add(InputToolType.UNKNOWN, xy[i], xy[i + 1], t)
            t += 8L
        }
        return Stroke(
            StrokeCodec.brush(StrokeCodec.FAMILY_PRESSURE_PEN, 0xFF000000L, 3f),
            batch.toImmutable(),
        )
    }

    @Test
    fun aRoundTripKeepsTheGeometry() {
        val original = stroke(10f, 20f, 500f, 600f, 990f, 1400f)
        val back = StrokeCodec.decode(StrokeCodec.encode(original, StrokeCodec.FAMILY_PRESSURE_PEN))
        val a = StrokeCodec.bbox(original.inputs)!!
        val b = StrokeCodec.bbox(back.inputs)!!
        for (i in 0..3) assertEquals(a[i], b[i], 0.001f)
    }

    @Test
    fun anEncodedStrokeDeclaresDocumentUnits() {
        val data = StrokeCodec.encode(stroke(1f, 2f, 3f, 4f), StrokeCodec.FAMILY_MARKER)
        // The version has to be *written*, not merely defaulted. `encodeDefaults` is false, so a
        // header field equal to its declared default is omitted — which would leave a new stroke
        // looking exactly like the pixel-coordinate ones this field exists to tell apart.
        assertEquals(StrokeCodec.VERSION_DU, StrokeCodec.version(data))
        assertTrue(StrokeCodec.isPortable(data))
        assertTrue(data.decodeToString().contains(StrokeCodec.UNIT_DU))
        assertNotNull(StrokeCodec.decodeOrNull(data))
    }

    @Test
    fun aMovedStrokeStaysReadableAndKeepsItsVersion() {
        val data = StrokeCodec.encode(stroke(100f, 100f, 200f, 200f), StrokeCodec.FAMILY_PRESSURE_PEN)
        val moved = StrokeCodec.translate(data, 50f, -25f)
        assertEquals(StrokeCodec.VERSION_DU, StrokeCodec.version(moved))
        val before = StrokeCodec.bbox(StrokeCodec.decode(data).inputs)!!
        val after = StrokeCodec.bbox(StrokeCodec.decode(moved).inputs)!!
        assertEquals(before[0] + 50f, after[0], 0.001f)
        assertEquals(before[1] - 25f, after[1], 0.001f)
    }

    @Test
    fun nothingIsDrawnWiderThanThePage() {
        // Ink captured at the far right of any screen converts to at most a page width, so it can
        // always be reached on a narrower one. The value here is what the canvas edge produces for
        // a touch on the last pixel of a 2000px tablet.
        val edge = 1999f * PAGE_WIDTH_DU / 2000f
        val s = stroke(edge, 10f, edge, 200f)
        val b = StrokeCodec.bbox(s.inputs)!!
        assertTrue("${b[0] + b[2]} should be within $PAGE_WIDTH_DU", b[0] + b[2] <= PAGE_WIDTH_DU)
    }
}
