package ie.shoonya.yantra.data.ink

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
enum class ShapeKind { LINE, RECTANGLE, ELLIPSE, ARROW, TRIANGLE }

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

    /**
     * The original format: coordinates are the drawing device's screen pixels.
     *
     * It carries no version field at all, and its absence is how a v1 blob is recognised. It is also
     * why v1 ink cannot be read: a pixel is only a position if you know how wide the screen was, and
     * that was never written down. See INK_CANVAS_PLAN.md §A.
     */
    const val VERSION_PX = 1

    /** Coordinates are [PAGE_WIDTH_DU] document units across the page, and mean the same everywhere. */
    const val VERSION_DU = 2

    const val UNIT_PX = "px"
    const val UNIT_DU = "du1000"

    @Serializable
    private data class Header(
        val family: String,
        val color: Long,   // ARGB as unsigned-int value
        val size: Float,
        val epsilon: Float,
        /**
         * Absent in v1, which is exactly how v1 is identified.
         *
         * The defaults here are **v1's** values, deliberately. `encodeDefaults` is false, so a field
         * equal to its default is not written at all — declaring these as v2's values would emit no
         * `v`, making every new stroke indistinguishable from the old ones this field exists to tell
         * apart.
         */
        val v: Int = VERSION_PX,
        val unit: String = UNIT_PX,
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
            v = VERSION_DU,
            unit = UNIT_DU,
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

    /**
     * The same stroke, shifted.
     *
     * Goes through the header rather than through a decoded [Stroke], so the brush comes out the
     * far side byte-for-byte what it was: family, colour, size and epsilon are copied across
     * untouched. A stroke that has been moved is the same ink in a different place, and nothing
     * about how it was drawn should change because it was picked up.
     */
    fun translate(data: ByteArray, dx: Float, dy: Float): ByteArray {
        DataInputStream(ByteArrayInputStream(data)).use { dis ->
            val headerBytes = ByteArray(dis.readInt())
            dis.readFully(headerBytes)
            val header = json.decodeFromString(Header.serializer(), headerBytes.decodeToString())
            // Same bar as [decode]. This re-encodes through [encode], which stamps the current
            // version unconditionally — so a v1 blob coming through here would come out *labelled*
            // as document units with its pixel coordinates untouched, which is worse than being
            // refused: it would look portable and be wrong everywhere but the screen that drew it.
            require(header.v >= VERSION_DU) {
                "ink stroke is v${header.v} (${header.unit}) — pixel coordinates cannot be moved"
            }
            val inputs = StrokeInputBatch.decode(dis)
            val moved = MutableStrokeInputBatch()
            for (i in 0 until inputs.size) {
                val p = inputs[i]
                moved.add(
                    p.toolType, p.x + dx, p.y + dy, p.elapsedTimeMillis,
                    p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians,
                )
            }
            val stroke = Stroke(
                brush = brush(header.family, header.color, header.size, header.epsilon),
                inputs = moved.toImmutable(),
            )
            return encode(stroke, header.family)
        }
    }

    /**
     * The version a blob claims, read without decoding its geometry.
     *
     * Returns 0 for anything unreadable, so a corrupt blob and a blob from the future are both
     * "not something to draw" without needing to be told apart.
     */
    fun version(data: ByteArray): Int = runCatching {
        DataInputStream(ByteArrayInputStream(data)).use { dis ->
            val headerBytes = ByteArray(dis.readInt())
            dis.readFully(headerBytes)
            json.decodeFromString(Header.serializer(), headerBytes.decodeToString()).v
        }
    }.getOrDefault(0)

    /** True for a blob this build can place on a page. */
    fun isPortable(data: ByteArray): Boolean = version(data) >= VERSION_DU

    /**
     * Decodes, or null for a blob that cannot be placed — malformed, or v1 pixel coordinates.
     *
     * The callers that read from the index want exactly this: a stroke they cannot position is not
     * an error to report, it is a stroke that is not there.
     */
    fun decodeOrNull(data: ByteArray): Stroke? = runCatching { decode(data) }.getOrNull()

    fun decode(data: ByteArray): Stroke {
        DataInputStream(ByteArrayInputStream(data)).use { dis ->
            val headerBytes = ByteArray(dis.readInt())
            dis.readFully(headerBytes)
            val header = json.decodeFromString(Header.serializer(), headerBytes.decodeToString())
            // A v1 stroke is not corrupt — it is unplaceable. Its numbers are pixels on a screen
            // whose width nobody recorded, so there is no scale that turns them back into a
            // position on the page. Drawing it anyway would put it roughly right on the device that
            // made it and twice too big somewhere else, which is the bug this format replaced.
            require(header.v >= VERSION_DU) {
                "ink stroke is v${header.v} (${header.unit}) — pixel coordinates cannot be placed"
            }
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

    /**
     * How much of [stroke], by length, lies inside the closed loop [px]/[py] — from 0 to 1.
     *
     * **Measured by arc length, not by sample count**, and that is the whole point. The old test
     * counted input points and asked for a majority of them, which was wrong twice over.
     *
     * Wrong once because sample density is a function of speed: MotionEvent arrives at a fixed rate,
     * so a stroke drawn slowly then quickly holds most of its *points* in its first half while
     * holding half its *length* there. Circling the slow half selected it and circling the fast half
     * did not, for the same loop over the same amount of ink.
     *
     * Wrong twice because the gaps between samples were invisible. A fast stroke's points can sit
     * tens of du apart, so a segment could pass clean through a small loop with no point inside it
     * and contribute nothing at all — the loop visibly crossed the ink and the geometry never saw
     * it. Resampling along the segments closes both holes with one change.
     */
    fun containedFraction(stroke: Stroke, px: FloatArray, py: FloatArray): Float =
        containedFraction(path(stroke), px, py)

    /**
     * The same, on a bare polyline.
     *
     * Split out for the same reason [ShapeRecognizer.recognize] has a FloatArray overload: this is
     * plane geometry, it has no business needing the ink library's native code loaded, and a test
     * that cannot run on the JVM is a test that does not get run.
     */
    fun containedFraction(path: StrokePath, px: FloatArray, py: FloatArray): Float {
        if (px.size < 3) return 0f
        val pts = resample(path, RESAMPLE_DU)
        if (pts.isEmpty()) return 0f
        var inside = 0
        for (i in pts.indices step 2) {
            if (pointInPolygon(pts[i], pts[i + 1], px, py)) inside++
        }
        return inside.toFloat() / (pts.size / 2).toFloat()
    }

    /**
     * What a lasso catches, most-contained first. Ids in, the chosen ids out.
     *
     * **Relative containment.** The rule is not "most of this stroke is inside the loop" — that made
     * the loop's required size a property of the stroke rather than of the region being pointed at,
     * and handwriting is long strokes. A whole word is often a single stroke, so winning a majority
     * of the word meant encircling the word, and a loop that big had already swallowed its
     * neighbours, which then won their own majorities. The loop grew, the catch grew with it, and
     * there was no way down. See INK_CANVAS_PLAN.md §C.
     *
     * Instead: score every stroke by contained length, then keep the ones that come within
     * [RELATIVE_FLOOR] of the best-scoring one. "Most of it, not any of it" survives — but *most of
     * it relative to what else this loop caught*, which is what makes a small loop legitimate rather
     * than a near-miss:
     *
     * - A loop around two whole strokes scores both at ~1. Both are kept.
     * - A third stroke merely passing through that loop scores ~0.08 against a best of 1, and is
     *   dropped as collateral.
     * - A small loop over part of one long stroke scores it 0.3 while its neighbour manages 0.04.
     *   The long stroke is kept — the loop meant the region, and the region was on that stroke.
     * - A loop the size of a fingertip catches whatever it is sitting on and nothing else. A tap
     *   needs no special case; it is the degenerate loop, and it comes out right for free.
     * - A loop on blank paper scores nothing above [ABSOLUTE_FLOOR] and catches nothing.
     *
     * Order is preserved for ties: [candidates] is expected in draw order, so the last of two
     * equally-contained overlapping strokes — the one on top, the one you can see — comes first.
     */
    fun lassoCatch(
        candidates: List<Pair<String, Stroke>>,
        px: FloatArray,
        py: FloatArray,
    ): List<String> = lassoCatchPaths(candidates.map { (id, s) -> path(s, id) }, px, py)

    fun lassoCatchPaths(
        candidates: List<StrokePath>,
        px: FloatArray,
        py: FloatArray,
    ): List<String> {
        if (px.size < 3) return emptyList()
        var lassoL = Float.MAX_VALUE; var lassoT = Float.MAX_VALUE
        var lassoR = -Float.MAX_VALUE; var lassoB = -Float.MAX_VALUE
        for (i in px.indices) {
            if (px[i] < lassoL) lassoL = px[i]
            if (px[i] > lassoR) lassoR = px[i]
            if (py[i] < lassoT) lassoT = py[i]
            if (py[i] > lassoB) lassoB = py[i]
        }

        val scored = ArrayList<Pair<Int, Float>>()
        for ((index, path) in candidates.withIndex()) {
            // Bounding-box cull before any arc-length work: this runs on every few pixels of finger
            // movement now that the catch is previewed live.
            val b = path.bbox() ?: continue
            if (b[0] > lassoR || b[0] + b[2] < lassoL || b[1] > lassoB || b[1] + b[3] < lassoT) continue
            val f = containedFraction(path, px, py)
            if (f > 0f) scored += index to f
        }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second }
        if (best < ABSOLUTE_FLOOR) return emptyList()
        val cutoff = max(ABSOLUTE_FLOOR, best * RELATIVE_FLOOR)
        return scored.filter { it.second >= cutoff }
            .sortedWith(
                compareByDescending<Pair<Int, Float>> { it.second }
                    // Later in draw order is nearer the top, so it wins a tie: you selected the
                    // stroke you could see.
                    .thenByDescending { it.first },
            )
            .map { candidates[it.first].id }
    }

    /** A stroke's path, extracted once so the geometry can be done without it. */
    fun path(stroke: Stroke, id: String = ""): StrokePath {
        val inputs = stroke.inputs
        val n = inputs.size
        return StrokePath(id, FloatArray(n) { inputs[it].x }, FloatArray(n) { inputs[it].y })
    }

    /**
     * A stroke as points spaced [stepDu] apart along its own length, as `[x0, y0, x1, y1, …]`.
     *
     * Walks the segments between samples rather than the samples themselves, so the result is an
     * unbiased description of where the ink actually goes — which the raw inputs are not.
     */
    private fun resample(path: StrokePath, stepDu: Float): FloatArray {
        val n = path.size
        if (n == 0) return FloatArray(0)
        val out = ArrayList<Float>(64)
        var px0 = path.xs[0]
        var py0 = path.ys[0]
        out.add(px0); out.add(py0)
        if (n == 1) return out.toFloatArray()
        var carry = 0f
        for (i in 1 until n) {
            val x1 = path.xs[i]
            val y1 = path.ys[i]
            val segLen = hypot((x1 - px0).toDouble(), (y1 - py0).toDouble()).toFloat()
            if (segLen > 0f) {
                var travelled = stepDu - carry
                while (travelled <= segLen) {
                    val t = travelled / segLen
                    out.add(px0 + (x1 - px0) * t)
                    out.add(py0 + (y1 - py0) * t)
                    travelled += stepDu
                }
                carry = (segLen - (travelled - stepDu)).coerceAtLeast(0f) % stepDu
            }
            px0 = x1; py0 = y1
        }
        return out.toFloatArray()
    }

    /** About a fifth of a millimetre on an A4 page — fine enough that a tick mark still has points. */
    private const val RESAMPLE_DU = 2f

    /** How far behind the best-contained stroke another may fall and still be taken with it. */
    private const val RELATIVE_FLOOR = 0.5f

    /** Below this, the loop caught nothing worth calling a catch — an empty loop on blank paper. */
    private const val ABSOLUTE_FLOOR = 0.02f

    /** Envelope of the raw input points — good enough for future canvas culling / hit-tests. */
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
