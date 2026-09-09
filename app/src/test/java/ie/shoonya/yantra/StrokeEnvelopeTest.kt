package ie.shoonya.yantra

import ie.shoonya.yantra.data.ink.StrokeEnvelope
import ie.shoonya.yantra.data.ink.StrokeEnvelope.Kind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The portable half of the ink codec, on the JVM. Nothing here touches androidx.ink, which is the
 * point: another platform implements exactly this and no more.
 */
class StrokeEnvelopeTest {

    private val header = StrokeEnvelope.Header("pressure_pen", 0xFF23211CL, 2.6f, 0.1f)
    private val pts = listOf(
        StrokeEnvelope.Point(10f, 20f, 0, 0.5f, -1f, -1f, 0f),
        StrokeEnvelope.Point(11.5f, 21.25f, 16, 0.75f, 0.3f, 1.2f, 0.02f),
    )

    @Test
    fun `round trips every field`() {
        val e = StrokeEnvelope.Envelope(header, StrokeEnvelope.TOOL_STYLUS, pts)
        val bytes = StrokeEnvelope.encode(e)
        assertEquals(Kind.YANTRA, StrokeEnvelope.kind(bytes))
        assertEquals(e, StrokeEnvelope.decode(bytes))
    }

    @Test
    fun `encoding is deterministic so two devices agree on bytes`() {
        val e = StrokeEnvelope.Envelope(header, StrokeEnvelope.TOOL_TOUCH, pts)
        assertArrayEquals(StrokeEnvelope.encode(e), StrokeEnvelope.encode(e))
    }

    @Test
    fun `opens with the magic and a big-endian header length`() {
        val bytes = StrokeEnvelope.encode(StrokeEnvelope.Envelope(header, 0, emptyList()))
        assertEquals("YNK1", bytes.copyOfRange(0, 4).decodeToString())
        val len = (bytes[4].toInt() and 0xFF shl 24) or (bytes[5].toInt() and 0xFF shl 16) or
            (bytes[6].toInt() and 0xFF shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals('{', bytes[8].toInt().toChar())
        assertEquals('}', bytes[8 + len - 1].toInt().toChar())
    }

    @Test
    fun `translation moves points and nothing else`() {
        val e = StrokeEnvelope.Envelope(header, StrokeEnvelope.TOOL_STYLUS, pts)
        val moved = e.translated(5f, -5f)
        assertEquals(header, moved.header)
        assertEquals(15f, moved.points[0].x)
        assertEquals(15f, moved.points[0].y)
        assertEquals(pts[0].pressure, moved.points[0].pressure)
    }

    @Test
    fun `sniffs the first format by its header-length prefix`() {
        // [int32 = 43]["{...}"][batch bytes]: first byte zero, fifth byte an opening brace.
        val legacy = byteArrayOf(0, 0, 0, 43) + "{\"family\":\"marker\"}".encodeToByteArray() + byteArrayOf(9, 9)
        assertEquals(Kind.LEGACY, StrokeEnvelope.kind(legacy))
    }

    @Test
    fun `a newer revision is unknown, not legacy and not ours`() {
        assertEquals(Kind.UNKNOWN, StrokeEnvelope.kind("YNK2....".encodeToByteArray()))
        assertEquals(Kind.UNKNOWN, StrokeEnvelope.kind(byteArrayOf()))
        assertEquals(Kind.UNKNOWN, StrokeEnvelope.kind("hello".encodeToByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            StrokeEnvelope.decode("YNK2....".encodeToByteArray())
        }
    }
}
