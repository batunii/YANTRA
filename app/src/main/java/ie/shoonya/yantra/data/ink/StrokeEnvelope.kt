package ie.shoonya.yantra.data.ink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * The on-disk shape of one stroke — Yantra's own, so that a second platform can read it.
 *
 * The first cut of the sidecar stored androidx.ink's serialised `StrokeInputBatch` behind a brush
 * header. That was the right instinct (ink stays ink, never a picture of ink) with the wrong owner:
 * the bytes were Google's, undocumented, and readable by exactly one library on exactly one
 * platform. A repository the user is promised they own should not carry a payload only Android can
 * open. This envelope stores what that batch stores — every input point with its pressure, tilt,
 * orientation and time — in a layout small enough to write down here and implement in an afternoon:
 *
 * ```
 * "YNK1"                        4 bytes, magic + version
 * int32  headerLength           big-endian
 * bytes  header                 UTF-8 JSON: {family, color, size, epsilon}
 * uint8  tool                   0 unknown · 1 mouse · 2 touch · 3 stylus
 * int32  pointCount
 * per point, big-endian:
 *   float32 x, float32 y        stroke units (document space)
 *   int32   elapsedMillis       since the stroke began
 *   float32 pressure            0..1, negative when the device did not report one
 *   float32 tiltRadians         negative when not reported
 *   float32 orientationRadians  negative when not reported
 *   float32 strokeUnitLengthCm  0 when unknown
 * ```
 *
 * Every field the brush needs is in the header, so the stroke is rebuilt as `Stroke(brush, inputs)`
 * exactly as before — only the bytes between header and end changed hands.
 *
 * This file deliberately imports nothing from androidx.ink. It is the part of the codec a unit test
 * can run on the JVM, and the part another platform copies.
 */
object StrokeEnvelope {

    /** The four bytes that open a Yantra stroke. A later revision changes the digit, never the letters. */
    val MAGIC: ByteArray = "YNK1".encodeToByteArray()

    /** Whether the bytes are ours, the first format's, or something newer than this build knows. */
    enum class Kind { YANTRA, LEGACY, UNKNOWN }

    @Serializable
    data class Header(
        val family: String,
        /** ARGB as an unsigned 32-bit value. */
        val color: Long,
        val size: Float,
        val epsilon: Float,
    )

    /** One sampled input. Field semantics follow the layout comment above. */
    data class Point(
        val x: Float,
        val y: Float,
        val elapsedMillis: Int,
        val pressure: Float,
        val tiltRadians: Float,
        val orientationRadians: Float,
        val strokeUnitLengthCm: Float,
    )

    data class Envelope(val header: Header, val tool: Int, val points: List<Point>) {
        fun translated(dx: Float, dy: Float) =
            copy(points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })
    }

    const val TOOL_UNKNOWN = 0
    const val TOOL_MOUSE = 1
    const val TOOL_TOUCH = 2
    const val TOOL_STYLUS = 3

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sniffs the bytes without decoding them.
     *
     * The first format opened with a big-endian header length — a small positive int, so its first
     * byte was always zero and its fifth was `{`. Ours opens with `YNK`. Anything else, including a
     * `YNK` followed by a digit this build does not know, is [Kind.UNKNOWN] and must be left alone:
     * skipped on read, preserved on write, never "repaired".
     */
    fun kind(data: ByteArray): Kind {
        if (data.size >= 4 && data[0] == MAGIC[0] && data[1] == MAGIC[1] && data[2] == MAGIC[2]) {
            return if (data[3] == MAGIC[3]) Kind.YANTRA else Kind.UNKNOWN
        }
        if (data.size >= 5 && data[0] == 0.toByte() && data[4] == '{'.code.toByte()) return Kind.LEGACY
        return Kind.UNKNOWN
    }

    fun encode(e: Envelope): ByteArray {
        val headerBytes = json.encodeToString(Header.serializer(), e.header).encodeToByteArray()
        val out = ByteArrayOutputStream(64 + headerBytes.size + e.points.size * 28)
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeInt(headerBytes.size)
            d.write(headerBytes)
            d.writeByte(e.tool)
            d.writeInt(e.points.size)
            e.points.forEach { p ->
                d.writeFloat(p.x)
                d.writeFloat(p.y)
                d.writeInt(p.elapsedMillis)
                d.writeFloat(p.pressure)
                d.writeFloat(p.tiltRadians)
                d.writeFloat(p.orientationRadians)
                d.writeFloat(p.strokeUnitLengthCm)
            }
        }
        return out.toByteArray()
    }

    /** Decodes a [Kind.YANTRA] stroke. Throws on anything else; callers sniff with [kind] first. */
    fun decode(data: ByteArray): Envelope {
        require(kind(data) == Kind.YANTRA) { "not a YNK1 stroke" }
        DataInputStream(ByteArrayInputStream(data)).use { d ->
            d.skipBytes(4)
            val headerLen = d.readInt()
            require(headerLen in 1..65536) { "implausible header length $headerLen" }
            val headerBytes = ByteArray(headerLen).also { d.readFully(it) }
            val header = json.decodeFromString(Header.serializer(), headerBytes.decodeToString())
            val tool = d.readUnsignedByte()
            val count = d.readInt()
            require(count >= 0) { "negative point count" }
            val points = ArrayList<Point>(count)
            repeat(count) {
                points += Point(
                    x = d.readFloat(),
                    y = d.readFloat(),
                    elapsedMillis = d.readInt(),
                    pressure = d.readFloat(),
                    tiltRadians = d.readFloat(),
                    orientationRadians = d.readFloat(),
                    strokeUnitLengthCm = d.readFloat(),
                )
            }
            if (d.read() != -1) throw EOFException("trailing bytes after the last point")
            return Envelope(header, tool, points)
        }
    }

    /** The brush header of a legacy stroke, without touching the batch bytes that follow it. */
    fun legacyHeader(data: ByteArray): Header {
        require(kind(data) == Kind.LEGACY) { "not a legacy stroke" }
        DataInputStream(ByteArrayInputStream(data)).use { d ->
            val headerBytes = ByteArray(d.readInt()).also { d.readFully(it) }
            return json.decodeFromString(Header.serializer(), headerBytes.decodeToString())
        }
    }
}
