package ie.napkin.supertasks.data.ink

import androidx.ink.strokes.StrokeInputBatch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Lightweight, ML-free shape recognition. Given a freehand stroke's raw points it decides
 * whether the gesture reads as a straight line, an axis-aligned rectangle, or an ellipse, and
 * returns the clean geometry to snap to (or null if it looks like ordinary handwriting).
 *
 * Kept deliberately conservative: recognition is opt-in and reversible (plain undo), so we'd
 * rather miss a shape than mangle real handwriting.
 */
object ShapeRecognizer {

    /**
     * How far the average point may sit from the shape it would become, as a fraction of half the
     * diagonal, before the stroke is left as it was drawn.
     */
    private const val FIT_TOLERANCE = 0.16f

    /**
     * How far the stroke's own length may differ from the perimeter of the shape it would become.
     *
     * The gate that keeps writing out. A shape is drawn by going round it once; handwriting covers
     * the same box two or three times over, and says so in its length whatever its outline
     * resembles.
     */
    private const val PATH_TOLERANCE = 0.30f

    /**
     * How far the outline may be simplified, as a fraction of the diagonal, when counting corners.
     *
     * Wide enough that a shaky edge is one straight run rather than several, tight enough that a
     * real corner survives. The count is flat across a broad range of this — 4% and 8% give the same
     * answers for triangles, boxes and circles alike — so it is not a number that wants tuning.
     */
    private const val CORNER_EPSILON = 0.06f

    /** Recognized shape defined by the drag box (x0,y0)-(x1,y1); line uses the two endpoints. */
    data class Result(
        val kind: ShapeKind,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        /**
         * The actual corners, for a shape a bounding box cannot describe.
         *
         * Null for a line, a rectangle or an ellipse, each of which its box defines completely. A
         * triangle it does not: the same box holds an upright one, a left-leaning one and a
         * right-leaning one, so the corners have to travel with the answer or the snap throws away
         * which triangle was drawn.
         */
        val vx: FloatArray? = null,
        val vy: FloatArray? = null,
    )

    fun recognize(inputs: StrokeInputBatch): Result? {
        val n = inputs.size
        if (n < 8) return null
        return recognize(FloatArray(n) { inputs[it].x }, FloatArray(n) { inputs[it].y })
    }

    /**
     * The same decision over plain points.
     *
     * Split out from the [StrokeInputBatch] overload because a batch cannot be built off-device —
     * it is backed by the ink library's native code, so anything taking one can only be exercised
     * on hardware. The geometry is the part worth testing and it needs no ink at all.
     */
    fun recognize(xs: FloatArray, ys: FloatArray): Result? {
        val n = xs.size
        if (n < 8) return null

        var minX = xs[0]; var maxX = xs[0]; var minY = ys[0]; var maxY = ys[0]
        var pathLen = 0f
        for (i in 0 until n) {
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
            if (i > 0) pathLen += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        val w = maxX - minX; val h = maxY - minY
        val diag = hypot(w, h)
        if (diag < 40f || pathLen < 40f) return null   // too small to be an intentional shape

        val chord = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])
        val closed = chord < 0.28f * diag

        // --- straight line: open path that barely deviates from its chord ---
        if (!closed && chord > 0.55f * diag) {
            val dev = maxPerpDeviation(xs, ys, xs[0], ys[0], xs[n - 1], ys[n - 1])
            if (dev < 0.14f * chord) {
                return Result(ShapeKind.LINE, xs[0], ys[0], xs[n - 1], ys[n - 1])
            }
        }

        if (!closed) return null
        // Degenerate closed shape (very thin) → not a rect/ellipse.
        if (min(w, h) < 0.12f * diag) return null

        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        val rx = (w / 2f).coerceAtLeast(1f); val ry = (h / 2f).coerceAtLeast(1f)

        // Both candidates are scored the same way: the average distance from a drawn point to the
        // shape it would snap to, as a fraction of half the diagonal.
        //
        // They used to be scored differently — the ellipse by a dimensionless radial ratio, the
        // rectangle by a normalised distance — and then compared to each other, which compares
        // nothing. It also let the rectangle through on an absolute threshold that virtually every
        // closed stroke passes: "mean distance to the nearest edge of your own bounding box" is
        // small for any blob, because the box is drawn around the blob and four of its points are
        // touching it by construction. So anything that was not an almost perfect circle came back
        // a rectangle.
        var ellErr = 0f
        var rectErr = 0f
        for (i in 0 until n) {
            val dx = xs[i] - cx; val dy = ys[i] - cy
            val d = hypot(dx, dy)
            // Where the fitted ellipse sits along this point's own ray, so the error is a real
            // distance rather than a ratio.
            val ux = if (d < 1e-3f) 1f else dx / d
            val uy = if (d < 1e-3f) 0f else dy / d
            val onEllipse = 1f / sqrt((ux * ux) / (rx * rx) + (uy * uy) / (ry * ry))
            ellErr += abs(d - onEllipse)
            rectErr += minOf(xs[i] - minX, maxX - xs[i], ys[i] - minY, maxY - ys[i])
        }
        val half = (diag / 2f).coerceAtLeast(1f)
        ellErr = (ellErr / n) / half
        rectErr = (rectErr / n) / half

        // Corners first, because a triangle is invisible to everything below this.
        //
        // The ellipse and rectangle scores both work by fitting a *box*, and a triangle sits inside
        // its own box closely enough to pass the rectangle test — three quarters of its outline is
        // on or near an edge. So a drawn triangle came back a rectangle, and no amount of tuning the
        // box metrics could have told them apart: the difference is the number of corners, which is
        // a thing you count rather than a distance you measure.
        val corners = simplifyClosed(xs, ys, diag * CORNER_EPSILON)
        if (corners.size == 3) {
            val tvx = FloatArray(3) { corners[it].first }
            val tvy = FloatArray(3) { corners[it].second }
            var perim = 0f
            for (i in 0 until 3) {
                val j = (i + 1) % 3
                perim += hypot(tvx[j] - tvx[i], tvy[j] - tvy[i])
            }
            // The same length check the other shapes get: three corners are not enough on their own,
            // since a scribble can simplify to three points while having wandered five times as far.
            if (abs(pathLen / perim.coerceAtLeast(1f) - 1f) <= PATH_TOLERANCE) {
                return Result(ShapeKind.TRIANGLE, minX, minY, maxX, maxY, tvx, tvy)
            }
        }

        // How far the pen actually travelled, against how far each candidate would have you travel.
        //
        // This is what separates a shape from handwriting, and nothing above it does. Distance to
        // the shape is not enough on its own: a looping cursive stroke sits close to the edges of
        // its own bounding box for most of its length, which scored 0.12 on the old rectangle test
        // and sailed through a 0.14 threshold — so ordinary writing came back a rectangle. It gives
        // itself away by *length*: a box walks its perimeter once, and a word crosses the same
        // ground three times.
        val rectPerimeter = 2f * (w + h)
        val ellPerimeter = PI.toFloat() * (3f * (rx + ry) - sqrt((3f * rx + ry) * (rx + 3f * ry)))
        val rectWalk = abs(pathLen / rectPerimeter.coerceAtLeast(1f) - 1f)
        val ellWalk = abs(pathLen / ellPerimeter.coerceAtLeast(1f) - 1f)

        val ellipseFits = ellErr <= FIT_TOLERANCE && ellWalk <= PATH_TOLERANCE
        val rectFits = rectErr <= FIT_TOLERANCE && rectWalk <= PATH_TOLERANCE

        // Neither fits: leave the handwriting alone. Recognition is opt-in and undoable, but a
        // wrong snap still costs the stroke you actually drew.
        return when {
            ellipseFits && (!rectFits || ellErr <= rectErr) ->
                Result(ShapeKind.ELLIPSE, minX, minY, maxX, maxY)
            rectFits -> Result(ShapeKind.RECTANGLE, minX, minY, maxX, maxY)
            else -> null
        }
    }

    /**
     * The stroke reduced to its corners — Ramer–Douglas–Peucker, over the closed outline.
     *
     * Keeps a point only where dropping it would move the outline further than [eps] from what was
     * drawn, which is exactly what a corner is: the place the path changes direction enough that no
     * straight line covers both sides of it. A hand-drawn triangle reduces to three points, a box to
     * four, a circle to eight or more, and a scribble to however many times it changed its mind.
     *
     * The closing duplicate is dropped, so the count is corners and not corners-plus-one.
     */
    private fun simplifyClosed(xs: FloatArray, ys: FloatArray, eps: Float): List<Pair<Float, Float>> {
        val pts = ArrayList<Pair<Float, Float>>(xs.size)
        for (i in xs.indices) pts.add(xs[i] to ys[i])
        val out = rdp(pts, 0, pts.size - 1, eps)
        if (out.size > 1) {
            val a = out.first(); val b = out.last()
            if (hypot(b.first - a.first, b.second - a.second) < eps * 2f) return out.dropLast(1)
        }
        return out
    }

    private fun rdp(
        pts: List<Pair<Float, Float>>,
        from: Int,
        to: Int,
        eps: Float,
    ): List<Pair<Float, Float>> {
        if (to <= from + 1) return listOf(pts[from], pts[to])
        val a = pts[from]; val b = pts[to]
        var worst = 0f
        var at = from
        for (i in from + 1 until to) {
            val d = perpDistance(pts[i], a, b)
            if (d > worst) { worst = d; at = i }
        }
        if (worst <= eps) return listOf(a, b)
        val left = rdp(pts, from, at, eps)
        val right = rdp(pts, at, to, eps)
        return left.dropLast(1) + right
    }

    private fun perpDistance(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = b.first - a.first; val dy = b.second - a.second
        val len = hypot(dx, dy)
        if (len < 1e-3f) return hypot(p.first - a.first, p.second - a.second)
        return abs((p.first - a.first) * dy - (p.second - a.second) * dx) / len
    }

    private fun maxPerpDeviation(xs: FloatArray, ys: FloatArray, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len = hypot(dx, dy).coerceAtLeast(1e-3f)
        var maxDev = 0f
        for (i in xs.indices) {
            // perpendicular distance from point to the infinite line through a-b
            val dev = abs((xs[i] - ax) * dy - (ys[i] - ay) * dx) / len
            if (dev > maxDev) maxDev = dev
        }
        return maxDev
    }
}
