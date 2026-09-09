package ie.shoonya.yantra.data.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.storage.decode
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
enum class ShapeKind { LINE, RECTANGLE, ELLIPSE, ARROW, TRIANGLE }

/**
 * Ink stays ink: the payload is every input point of the stroke (never a picture of it), behind a
 * tiny JSON header describing the brush, so the stroke can be rebuilt deterministically as
 * Stroke(brush, inputs). The layout is [StrokeEnvelope]'s and is Yantra's own.
 *
 * Until 0.3.0 the payload after the header was androidx.ink's serialised StrokeInputBatch. Those
 * bytes are still read — [decode] and [upgrade] both understand them — but never written again.
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

    /** A stroke this build cannot read: newer than it, or not a stroke at all. Skip it, keep the bytes. */
    class UnsupportedStrokeFormat(kind: StrokeEnvelope.Kind) : IllegalArgumentException("stroke format $kind")

    private fun toolCode(t: InputToolType): Int = when (t) {
        InputToolType.MOUSE -> StrokeEnvelope.TOOL_MOUSE
        InputToolType.TOUCH -> StrokeEnvelope.TOOL_TOUCH
        InputToolType.STYLUS -> StrokeEnvelope.TOOL_STYLUS
        else -> StrokeEnvelope.TOOL_UNKNOWN
    }

    private fun toolType(code: Int): InputToolType = when (code) {
        StrokeEnvelope.TOOL_MOUSE -> InputToolType.MOUSE
        StrokeEnvelope.TOOL_TOUCH -> InputToolType.TOUCH
        StrokeEnvelope.TOOL_STYLUS -> InputToolType.STYLUS
        else -> InputToolType.UNKNOWN
    }

    /** The envelope for a live stroke: brush header plus every input point, in Yantra's own layout. */
    private fun envelope(stroke: Stroke, familyName: String): StrokeEnvelope.Envelope {
        val inputs = stroke.inputs
        val tool = if (inputs.size > 0) toolCode(inputs[0].toolType) else StrokeEnvelope.TOOL_UNKNOWN
        val points = ArrayList<StrokeEnvelope.Point>(inputs.size)
        for (i in 0 until inputs.size) {
            val p = inputs[i]
            points += StrokeEnvelope.Point(
                x = p.x, y = p.y,
                elapsedMillis = p.elapsedTimeMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                pressure = p.pressure,
                tiltRadians = p.tiltRadians,
                orientationRadians = p.orientationRadians,
                strokeUnitLengthCm = p.strokeUnitLengthCm,
            )
        }
        return StrokeEnvelope.Envelope(
            header = StrokeEnvelope.Header(
                family = familyName,
                color = stroke.brush.colorIntArgb.toLong() and 0xFFFFFFFFL,
                size = stroke.brush.size,
                epsilon = stroke.brush.epsilon,
            ),
            tool = tool,
            points = points,
        )
    }

    private fun stroke(e: StrokeEnvelope.Envelope): Stroke {
        val batch = MutableStrokeInputBatch()
        val tool = toolType(e.tool)
        e.points.forEach { p ->
            batch.add(
                tool, p.x, p.y, p.elapsedMillis.toLong(),
                p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians,
            )
        }
        val h = e.header
        return Stroke(brush = brush(h.family, h.color, h.size, h.epsilon), inputs = batch.toImmutable())
    }

    /**
     * Reads either generation into the envelope. Legacy bytes go through androidx.ink's decoder one
     * last time — that is the only place the old payload is still understood, and it exists so that
     * a sketch drawn before the format changed can be carried across rather than lost.
     */
    private fun read(data: ByteArray): StrokeEnvelope.Envelope = when (val k = StrokeEnvelope.kind(data)) {
        StrokeEnvelope.Kind.YANTRA -> StrokeEnvelope.decode(data)
        StrokeEnvelope.Kind.LEGACY -> DataInputStream(ByteArrayInputStream(data)).use { dis ->
            val headerBytes = ByteArray(dis.readInt())
            dis.readFully(headerBytes)
            val header = json.decodeFromString(Header.serializer(), headerBytes.decodeToString())
            val inputs = StrokeInputBatch.decode(dis)
            val legacy = Stroke(
                brush = brush(header.family, header.color, header.size, header.epsilon),
                inputs = inputs,
            )
            envelope(legacy, header.family)
        }
        StrokeEnvelope.Kind.UNKNOWN -> throw UnsupportedStrokeFormat(k)
    }

    /** Always writes the current format. */
    fun encode(stroke: Stroke, familyName: String): ByteArray =
        StrokeEnvelope.encode(envelope(stroke, familyName))

    /**
     * The same stroke, shifted.
     *
     * Goes through the envelope rather than through a re-tessellated [Stroke], so the brush comes out
     * the far side exactly what it was: family, colour, size and epsilon are copied across untouched.
     * A stroke that has been moved is the same ink in a different place, and nothing about how it was
     * drawn should change because it was picked up.
     */
    fun translate(data: ByteArray, dx: Float, dy: Float): ByteArray =
        StrokeEnvelope.encode(read(data).translated(dx, dy))

    /** Throws [UnsupportedStrokeFormat] for bytes this build cannot read; callers skip those strokes. */
    fun decode(data: ByteArray): Stroke = stroke(read(data))

    /** True when the bytes are the first format's and would be rewritten by [upgrade]. */
    fun isLegacy(data: ByteArray): Boolean = StrokeEnvelope.kind(data) == StrokeEnvelope.Kind.LEGACY

    /**
     * Legacy bytes become current bytes; anything else is returned as it came, including strokes
     * newer than this build, which must round-trip untouched through a sidecar rewrite.
     */
    fun upgrade(data: ByteArray): ByteArray =
        if (isLegacy(data)) StrokeEnvelope.encode(read(data)) else data

    /**
     * Builds a clean vector shape as a StrokeInputBatch spanning the drag box (x0,y0)-(x1,y1).
     * The result commits through the normal stroke path, so shapes stay ink: erasable,
     * theme-aware, persisted identically to freehand.
     */
    /**
     * A closed polygon through the given corners.
     *
     * Shapes whose form a bounding box cannot describe come through here instead of [shapeInputs].
     * A triangle is the plain case: the same box holds a left-leaning, right-leaning or upright one,
     * so snapping to a box would be snapping away the thing that was drawn.
     */
    fun polygonInputs(vx: FloatArray, vy: FloatArray): StrokeInputBatch {
        val pts = ArrayList<FloatArray>()
        for (i in vx.indices) {
            val j = (i + 1) % vx.size
            sampleLine(pts, vx[i], vy[i], vx[j], vy[j])
        }
        pts.add(floatArrayOf(vx[0], vy[0]))
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (p in pts) {
            batch.add(InputToolType.UNKNOWN, p[0], p[1], t)
            t += 6L
        }
        return batch.toImmutable()
    }

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
            ShapeKind.TRIANGLE -> {
                // Only reached if something asks for a triangle by box alone; the recogniser sends
                // real corners through polygonInputs. Upright and centred is the honest default.
                val cx = (minX + maxX) / 2f
                sampleLine(pts, cx, minY, maxX, maxY)
                sampleLine(pts, maxX, maxY, minX, maxY)
                sampleLine(pts, minX, maxY, cx, minY)
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
    /**
     * Whether [stroke] is inside the closed path [px]/[py], and so caught by a lasso.
     *
     * **Most of it, not any of it.** A stroke that merely crosses the loop is not being selected —
     * you drew the loop around what you wanted, and a word whose descender happens to poke into the
     * circle should not come with it. Requiring a majority of the stroke's own points inside makes
     * the loop mean what it looks like it means, and makes a near-miss a near-miss rather than a
     * surprise.
     *
     * Ray casting, per point. The polygon is whatever the finger drew, so it is not convex and
     * cannot be treated as a rectangle.
     */
    fun strokeInside(stroke: Stroke, px: FloatArray, py: FloatArray): Boolean {
        val inputs = stroke.inputs
        val n = inputs.size
        if (n == 0 || px.size < 3) return false
        var inside = 0
        for (i in 0 until n) {
            if (pointInPolygon(inputs[i].x, inputs[i].y, px, py)) inside++
        }
        return inside * 2 > n
    }

    private fun pointInPolygon(x: Float, y: Float, px: FloatArray, py: FloatArray): Boolean {
        var hit = false
        var j = px.size - 1
        for (i in px.indices) {
            val yi = py[i]; val yj = py[j]
            if ((yi > y) != (yj > y)) {
                val t = (x - px[i]) < (px[j] - px[i]) * (y - yi) / (yj - yi)
                if (t) hit = !hit
            }
            j = i
        }
        return hit
    }

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
