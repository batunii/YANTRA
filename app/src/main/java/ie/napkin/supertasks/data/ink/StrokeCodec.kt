package ie.napkin.supertasks.data.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Clean shapes the shape-tool can draw and the recognizer can snap freehand strokes to. */
enum class ShapeKind { LINE, RECTANGLE, ELLIPSE, ARROW }

/**
 * Ink stays ink: the payload is the Ink API's own serialized StrokeInputBatch (never text),
 * prefixed with a tiny JSON header describing the brush so the stroke can be rebuilt
 * deterministically as Stroke(brush, inputs).
 *
 * Layout: [int32 header length][header JSON utf-8][StrokeInputBatch bytes]
 */
object StrokeCodec {

    const val FAMILY_PRESSURE_PEN = "pressure_pen"
    const val FAMILY_MARKER = "marker"
    const val FAMILY_HIGHLIGHTER = "highlighter"

    /** Highlighters are translucent — they tint, never cover. (~35% opacity.) */
    const val HIGHLIGHTER_ALPHA = 0x59

    @Serializable
    private data class Header(
        val family: String,
        val color: Long,   // ARGB as unsigned-int value
        val size: Float,
        val epsilon: Float,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun familyFor(name: String): BrushFamily = when (name) {
        FAMILY_MARKER -> StockBrushes.marker()
        FAMILY_HIGHLIGHTER -> StockBrushes.highlighter()
        else -> StockBrushes.pressurePen()
    }

    fun brush(family: String, colorArgb: Long, size: Float, epsilon: Float = 0.1f): Brush {
        // Force the highlighter to its translucent alpha regardless of the picked swatch, so it
        // reads as a highlight rather than a solid stroke (also normalises older saved strokes).
        val effectiveColor =
            if (family == FAMILY_HIGHLIGHTER) (colorArgb and 0x00FFFFFFL) or (HIGHLIGHTER_ALPHA.toLong() shl 24)
            else colorArgb
        return Brush.createWithColorIntArgb(
            family = familyFor(family),
            colorIntArgb = effectiveColor.toInt(),
            size = size,
            epsilon = epsilon,
        )
    }

    /**
     * True for highlighter strokes. Detected by brush family, with a translucency fallback:
     * only highlighters are drawn non-opaque, so alpha < 255 is a reliable second signal even
     * if stock brush-family identity changes across Ink versions.
     */
    fun isHighlighter(stroke: Stroke): Boolean =
        stroke.brush.family == StockBrushes.highlighter() ||
            ((stroke.brush.colorIntArgb ushr 24) and 0xFF) < 0xFF

    fun encode(stroke: Stroke, familyName: String): ByteArray {
        val header = Header(
            family = familyName,
            color = stroke.brush.colorIntArgb.toLong() and 0xFFFFFFFFL,
            size = stroke.brush.size,
            epsilon = stroke.brush.epsilon,
        )
        val headerBytes = json.encodeToString(Header.serializer(), header).encodeToByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.writeInt(headerBytes.size)
            dos.write(headerBytes)
            stroke.inputs.encode(dos)
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Stroke {
        DataInputStream(ByteArrayInputStream(data)).use { dis ->
            val headerBytes = ByteArray(dis.readInt())
            dis.readFully(headerBytes)
            val header = json.decodeFromString(Header.serializer(), headerBytes.decodeToString())
            val inputs = StrokeInputBatch.decode(dis)
            return Stroke(
                brush = brush(header.family, header.color, header.size, header.epsilon),
                inputs = inputs,
            )
        }
    }

    /**
     * Builds a clean vector shape as a StrokeInputBatch spanning the drag box (x0,y0)-(x1,y1).
     * The result commits through the normal stroke path, so shapes stay ink: erasable,
     * theme-aware, persisted identically to freehand.
     */
    fun shapeInputs(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float): StrokeInputBatch {
        val pts = ArrayList<FloatArray>()
        val minX = min(x0, x1); val minY = min(y0, y1)
        val maxX = max(x0, x1); val maxY = max(y0, y1)
        when (kind) {
            ShapeKind.LINE -> sampleLine(pts, x0, y0, x1, y1)
            ShapeKind.RECTANGLE -> {
                sampleLine(pts, minX, minY, maxX, minY)
                sampleLine(pts, maxX, minY, maxX, maxY)
                sampleLine(pts, maxX, maxY, minX, maxY)
                sampleLine(pts, minX, maxY, minX, minY)
            }
            ShapeKind.ELLIPSE -> {
                val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
                val rx = (maxX - minX) / 2f; val ry = (maxY - minY) / 2f
                val steps = 64
                for (i in 0..steps) {
                    val a = (i.toFloat() / steps) * (2f * Math.PI.toFloat())
                    pts.add(floatArrayOf(cx + rx * cos(a), cy + ry * sin(a)))
                }
            }
            ShapeKind.ARROW -> {
                sampleLine(pts, x0, y0, x1, y1)
                val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()).toFloat()
                val head = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat() * 0.22f
                val spread = 0.45f
                val bx = x1 - head * cos(ang - spread); val by = y1 - head * sin(ang - spread)
                val cx = x1 - head * cos(ang + spread); val cy = y1 - head * sin(ang + spread)
                sampleLine(pts, x1, y1, bx, by)
                sampleLine(pts, bx, by, x1, y1)
                sampleLine(pts, x1, y1, cx, cy)
            }
        }
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (p in pts) {
            batch.add(InputToolType.UNKNOWN, p[0], p[1], t)
            t += 6L
        }
        return batch.toImmutable()
    }

    private fun sampleLine(out: ArrayList<FloatArray>, ax: Float, ay: Float, bx: Float, by: Float) {
        val steps = 24
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            out.add(floatArrayOf(ax + (bx - ax) * f, ay + (by - ay) * f))
        }
    }

    /** True if (px,py) lies within [radius] of any segment of the stroke — the eraser hit-test. */
    fun strokeHit(stroke: Stroke, px: Float, py: Float, radius: Float): Boolean {
        val inputs = stroke.inputs
        if (inputs.size == 0) return false
        val r2 = radius * radius
        var prevX = inputs[0].x
        var prevY = inputs[0].y
        if (distSq(px, py, prevX, prevY, prevX, prevY) <= r2) return true
        for (i in 1 until inputs.size) {
            val x = inputs[i].x
            val y = inputs[i].y
            if (distSq(px, py, prevX, prevY, x, y) <= r2) return true
            prevX = x; prevY = y
        }
        return false
    }

    private fun distSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
        val cx = ax + t * dx; val cy = ay + t * dy
        val ex = px - cx; val ey = py - cy
        return ex * ex + ey * ey
    }

    /** Envelope of the raw input points — good enough for future canvas culling / hit-tests. */
    fun bbox(inputs: StrokeInputBatch): FloatArray? {
        if (inputs.size == 0) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until inputs.size) {
            val p = inputs[i]
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return floatArrayOf(minX, minY, maxX - minX, maxY - minY)
    }
}
