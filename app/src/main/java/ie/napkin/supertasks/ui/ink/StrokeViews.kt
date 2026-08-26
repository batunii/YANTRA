package ie.napkin.supertasks.ui.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import ie.napkin.supertasks.data.ink.StrokeCodec
import ie.napkin.supertasks.ui.theme.Yantra
import kotlin.math.min

/**
 * Draws committed (dry) strokes with the Ink renderer. Used at identity transform as the
 * editor's dry layer, and with a fit-to-bounds transform for read-only previews.
 */
class FinishedStrokesView(context: Context) : View(context) {

    private val renderer = CanvasStrokeRenderer.create()
    private val transform = Matrix()

    var fitToBounds: Boolean = false
        set(value) {
            field = value
            recomputeTransform()
            invalidate()
        }

    /**
     * When set, render a true-scale crop of page 1 instead of shrinking everything to fit:
     * the value is the width of the editor's document (its canvas width in px), so ink
     * appears at the same proportions it was drawn at.
     */
    var pageCropDocWidth: Float? = null
        set(value) {
            field = value
            recomputeTransform()
            invalidate()
        }

    var strokes: List<Stroke> = emptyList()
        set(value) {
            field = value
            recomputeTransform()
            invalidate()
        }

    /** Freshly finished strokes shown until the persisted flow catches up. */
    var extraStrokes: List<Stroke> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeTransform()
    }

    private fun recomputeTransform() {
        transform.reset()
        if (width == 0 || height == 0) return
        val docWidth = pageCropDocWidth
        if (docWidth != null && docWidth > 0f) {
            val scale = width / docWidth
            var contentTop = Float.MAX_VALUE
            for (s in strokes) {
                val b = StrokeCodec.bbox(s.inputs) ?: continue
                if (b[1] < contentTop) contentTop = b[1]
            }
            if (contentTop == Float.MAX_VALUE) contentTop = 0f
            val startY = (contentTop - INK_CONTENT_PAD).coerceAtLeast(0f)
            transform.postTranslate(0f, -startY)
            transform.postScale(scale, scale)
            return
        }
        if (!fitToBounds) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (s in strokes) {
            val b = StrokeCodec.bbox(s.inputs) ?: continue
            if (b[0] < minX) minX = b[0]
            if (b[1] < minY) minY = b[1]
            if (b[0] + b[2] > maxX) maxX = b[0] + b[2]
            if (b[1] + b[3] > maxY) maxY = b[1] + b[3]
        }
        if (minX > maxX) return
        val pad = 24f
        val contentW = (maxX - minX).coerceAtLeast(1f)
        val contentH = (maxY - minY).coerceAtLeast(1f)
        val scale = min((width - pad * 2) / contentW, (height - pad * 2) / contentH).coerceAtMost(1.5f)
        transform.postTranslate(-minX, -minY)
        transform.postScale(scale, scale)
        transform.postTranslate(
            (width - contentW * scale) / 2f,
            (height - contentH * scale) / 2f,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(transform)
        for (s in strokes) renderer.draw(canvas, s, transform)
        for (s in extraStrokes) renderer.draw(canvas, s, transform)
        canvas.restore()
    }
}

/** Doc-space padding kept above/below content in previews (px). */
const val INK_CONTENT_PAD = 24f

/**
 * Read-only, page-native rendering of an ink block: transparent background, true-scale
 * crop starting just above the topmost ink, with the theme-adaptive ink colors — so the
 * sketch sits directly on the page like handwriting, not inside a card.
 */
@Composable
fun InkPreview(strokes: List<Stroke>, modifier: Modifier = Modifier) {
    // The app's own resolved mode, never the system night mode: the two disagree whenever
    // the user has pinned Light or Dark, and a preview keyed off the phone would draw black
    // ink onto a dark page (the default install is DARK, so that is the common case).
    val dark = Yantra.colors.isDark
    val display = remember(strokes, dark) { InkTheme.displayStrokes(strokes, dark) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FinishedStrokesView(ctx).apply {
                pageCropDocWidth = ctx.resources.displayMetrics.widthPixels.toFloat()
            }
        },
        update = { view -> view.strokes = display },
    )
}

/** Content height of a stroke set in document px, matching InkPreview's crop window. */
fun inkContentHeight(strokes: List<Stroke>): Float {
    var minY = Float.MAX_VALUE
    var maxY = 0f
    for (s in strokes) {
        val b = StrokeCodec.bbox(s.inputs) ?: continue
        if (b[1] < minY) minY = b[1]
        if (b[1] + b[3] > maxY) maxY = b[1] + b[3]
    }
    if (minY == Float.MAX_VALUE) return 0f
    val startY = (minY - INK_CONTENT_PAD).coerceAtLeast(0f)
    return maxY + INK_CONTENT_PAD - startY
}
