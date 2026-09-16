package ie.shoonya.yantra.data.ink

import kotlin.math.sqrt

/**
 * The unit ink is stored in: **document units (du)**, 1000 of them across the width of a page.
 *
 * Not pixels, not dp, and not a property of any screen. A stroke's `x` is a position on the page,
 * so the same number means the same place on a phone, on a tablet, and in a preview thumbnail — the
 * one thing pixel coordinates could never do, because a pixel is only a position if you also know
 * how wide the screen was, and that was never written down. See INK_CANVAS_PLAN.md §A.
 *
 * The page is A4-proportioned, so 1000 du spans 210 mm and one du is about 0.21 mm. That is worth
 * knowing when picking a pen width or an eraser: the numbers are physical, not arbitrary.
 *
 * Page **width** is what is fixed. Zooming out reveals more pages, never a wider page — a page that
 * could widen would be a page whose du meant something different per document, which is the defect
 * again in a new costume.
 */
const val PAGE_WIDTH_DU = 1000f

/** Page height : width, like an A4 sheet in portrait. */
val PAGE_RATIO = sqrt(2f)

val PAGE_HEIGHT_DU = PAGE_WIDTH_DU * PAGE_RATIO

/**
 * A stroke reduced to the points it passes through, in document units.
 *
 * The selection geometry works on these rather than on `Stroke` for a practical reason: a `Stroke`
 * carries the ink library's native-backed input batch, so any code that touches one cannot run in a
 * JVM unit test — and geometry that cannot be tested is geometry that gets its thresholds wrong
 * quietly. [ie.shoonya.yantra.data.ink.ShapeRecognizer] already splits itself the same way.
 */
class StrokePath(val id: String, val xs: FloatArray, val ys: FloatArray) {

    val size: Int get() = xs.size

    /** `[x, y, width, height]`, or null for an empty path. */
    fun bbox(): FloatArray? {
        if (size == 0) return null
        var minX = xs[0]; var maxX = xs[0]
        var minY = ys[0]; var maxY = ys[0]
        for (i in 1 until size) {
            if (xs[i] < minX) minX = xs[i]
            if (xs[i] > maxX) maxX = xs[i]
            if (ys[i] < minY) minY = ys[i]
            if (ys[i] > maxY) maxY = ys[i]
        }
        return floatArrayOf(minX, minY, maxX - minX, maxY - minY)
    }
}
