package ie.shoonya.yantra

import ie.shoonya.yantra.data.ink.PAGE_HEIGHT_DU
import ie.shoonya.yantra.data.ink.PAGE_WIDTH_DU
import ie.shoonya.yantra.data.ink.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a stroke means the same place on every screen, and that the old ones say so.
 *
 * This is the regression test for INK_CANVAS_PLAN.md §A. Ink used to be stored in the drawing
 * device's pixels with no record of how wide that device was, so a mark made on a tablet landed off
 * the right edge of a phone — somewhere no gesture could reach, because there was no horizontal pan
 * either. The fix is a unit: a thousand document units across the page, whatever the page is being
 * shown on.
 *
 * Nothing here touches a `Stroke`. The ink library is native-backed and unavailable on the JVM, so
 * the parts that need a real one live in `InkCodecInstrumentedTest` — but a header is JSON and a
 * version check is a comparison, and those belong where they will actually be run.
 */
class InkCoordinatesTest {

    /** What a canvas of [widthPx] does to a touch at [viewX]: the conversion the edges perform. */
    private fun toDu(viewX: Float, widthPx: Float) = viewX * PAGE_WIDTH_DU / widthPx

    @Test
    fun `the same gesture on two screens lands in the same place on the page`() {
        // A finger three-quarters of the way across, on a phone and on a tablet.
        val phone = toDu(0.75f * 1080f, 1080f)
        val tablet = toDu(0.75f * 2000f, 2000f)
        assertEquals(phone, tablet, 0.01f)
        assertEquals(750f, phone, 0.01f)
    }

    @Test
    fun `ink from a wider screen stays on the page`() {
        // The original bug in one line: under the old scheme a mark near the right edge of a 2000px
        // tablet was stored as x=1900, which is off the right of a 1080px phone and unreachable.
        assertTrue(toDu(1900f, 2000f) <= PAGE_WIDTH_DU)
        assertEquals(950f, toDu(1900f, 2000f), 0.01f)
    }

    @Test
    fun `the page is A4-proportioned in both directions`() {
        // Page height has to be a document property too, or page boundaries land differently per
        // device and "page 3 of 7" is a different 3 and a different 7 on each one.
        assertEquals(PAGE_WIDTH_DU * 1.41421f, PAGE_HEIGHT_DU, 0.01f)
    }

    @Test
    fun `a header with no version field is the old format`() {
        assertEquals(StrokeCodec.VERSION_PX, StrokeCodec.version(blob("""{"family":"pressure_pen","color":4278190080,"size":3.0,"epsilon":0.1}""")))
        assertFalse(StrokeCodec.isPortable(blob("""{"family":"pressure_pen","color":4278190080,"size":3.0,"epsilon":0.1}""")))
    }

    @Test
    fun `a header naming document units is readable`() {
        val h = """{"family":"pressure_pen","color":4278190080,"size":3.0,"epsilon":0.1,"v":2,"unit":"du1000"}"""
        assertEquals(StrokeCodec.VERSION_DU, StrokeCodec.version(blob(h)))
        assertTrue(StrokeCodec.isPortable(blob(h)))
    }

    @Test
    fun `an old stroke is refused before its geometry is even looked at`() {
        // The payload here is nonsense on purpose. A v1 blob has to be turned away on the strength
        // of its header alone: if the refusal came after the input batch was parsed it would need
        // the native library, and the drop would fail on exactly the malformed old data it exists
        // to handle.
        assertNull(StrokeCodec.decodeOrNull(blob("""{"family":"marker","color":4278190080,"size":3.0,"epsilon":0.1}""")))
    }

    @Test
    fun `garbage is refused without throwing`() {
        assertNull(StrokeCodec.decodeOrNull(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(0, StrokeCodec.version(byteArrayOf(1, 2, 3, 4, 5)))
    }

    /** The codec's framing — `[int32 header length][header][payload]` — around a header of our own. */
    private fun blob(header: String, payload: ByteArray = byteArrayOf(9, 9, 9)): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use { dos ->
            val bytes = header.encodeToByteArray()
            dos.writeInt(bytes.size)
            dos.write(bytes)
            dos.write(payload)
        }
        return out.toByteArray()
    }
}
