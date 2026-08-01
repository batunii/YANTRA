package ie.napkin.supertasks.data.ink

import androidx.ink.strokes.StrokeInputBatch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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

    /** Recognized shape defined by the drag box (x0,y0)-(x1,y1); line uses the two endpoints. */
    data class Result(val kind: ShapeKind, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    fun recognize(inputs: StrokeInputBatch): Result? {
        val n = inputs.size
        if (n < 8) return null

        val xs = FloatArray(n) { inputs[it].x }
        val ys = FloatArray(n) { inputs[it].y }

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

        var ellErr = 0f
        var rectErr = 0f
        for (i in 0 until n) {
            val dx = xs[i] - cx; val dy = ys[i] - cy
            // radial error against the fitted ellipse (0 = exactly on the ellipse)
            ellErr += abs(sqrt((dx * dx) / (rx * rx) + (dy * dy) / (ry * ry)) - 1f)
            // distance to the nearest bounding-box edge, normalised
            val edge = minOf(xs[i] - minX, maxX - xs[i], ys[i] - minY, maxY - ys[i])
            rectErr += edge
        }
        ellErr /= n
        rectErr = (rectErr / n) / (diag / 2f)

        return when {
            ellErr < rectErr && ellErr < 0.22f -> Result(ShapeKind.ELLIPSE, minX, minY, maxX, maxY)
            rectErr < 0.14f -> Result(ShapeKind.RECTANGLE, minX, minY, maxX, maxY)
            else -> null
        }
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
