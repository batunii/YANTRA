package ie.shoonya.yantra.ui.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import ie.shoonya.yantra.data.ink.PAGE_WIDTH_DU
import ie.shoonya.yantra.data.ink.StrokeCodec
import ie.shoonya.yantra.ui.theme.Yantra
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
     * the value is the width of the document in document units, so ink appears at the same
     * proportions it was drawn at.
     *
     * This used to be handed `displayMetrics.widthPixels` — the preview *assumed* the document was
     * as wide as whatever screen happened to be showing it, which was true only on the device that
     * drew it. It is [PAGE_WIDTH_DU] now, and true everywhere.
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
                val b = StrokeCodec.bbox(s) ?: continue
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
            val b = StrokeCodec.bbox(s) ?: continue
            if (b[0] < minX) minX = b[0]
            if (b[1] < minY) minY = b[1]
            if (b[0] + b[2] > maxX) maxX = b[0] + b[2]
            if (b[1] + b[3] > maxY) maxY = b[1] + b[3]
        }
        if (minX > maxX) return
        val pad = 24f
        val contentW = (maxX - minX).coerceAtLeast(1f)
        val contentH = (maxY - minY).coerceAtLeast(1f)
        // Capped relative to page-native scale rather than at a bare 1.5 px/du, which would have
        // been a different amount of magnification on every screen.
        val cap = width / PAGE_WIDTH_DU * 1.5f
        val scale = min((width - pad * 2) / contentW, (height - pad * 2) / contentH).coerceAtMost(cap)
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

/** Doc-space padding kept above/below content in previews, in document units. */
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
                pageCropDocWidth = PAGE_WIDTH_DU
            }
        },
        update = { view -> view.strokes = display },
    )
}

/** Content height of a stroke set in document units, matching InkPreview's crop window. */
fun inkContentHeight(strokes: List<Stroke>): Float {
    var minY = Float.MAX_VALUE
    var maxY = 0f
    for (s in strokes) {
        val b = StrokeCodec.bbox(s) ?: continue
        if (b[1] < minY) minY = b[1]
        if (b[1] + b[3] > maxY) maxY = b[1] + b[3]
    }
    if (minY == Float.MAX_VALUE) return 0f
    val startY = (minY - INK_CONTENT_PAD).coerceAtLeast(0f)
    return maxY + INK_CONTENT_PAD - startY
}

/** One slice of a sketch as its page draws it: where it sits, and the strokes that pass through it. */
private class InkBand(val top: Float, val bottom: Float, val strokes: List<Stroke>)

/** A sketch cut into [InkBand]s, in document units, from the top of its content to the bottom. */
private class InkBandLayout(val top: Float, val height: Float, val bands: List<InkBand>) {
    companion object {
        fun of(strokes: List<Stroke>): InkBandLayout {
            var minY = Float.MAX_VALUE
            var maxY = 0f
            val boxes = strokes.map { s ->
                StrokeCodec.bbox(s)?.also { b ->
                    if (b[1] < minY) minY = b[1]
                    if (b[1] + b[3] > maxY) maxY = b[1] + b[3]
                }
            }
            if (minY == Float.MAX_VALUE) return InkBandLayout(0f, 0f, emptyList())
            val top = (minY - INK_CONTENT_PAD).coerceAtLeast(0f)
            val bottom = maxY + INK_CONTENT_PAD
            val bands = ArrayList<InkBand>()
            var at = top
            while (at < bottom) {
                val end = minOf(at + BAND_DU, bottom)
                val lo = at
                // A stroke goes in every band it crosses. Each band clips to itself, so the part of
                // a stroke outside a band is never drawn by it — and so never drawn twice.
                val inside = strokes.filterIndexed { i, _ ->
                    val b = boxes[i] ?: return@filterIndexed false
                    b[1] - STROKE_REACH_DU < end && b[1] + b[3] + STROKE_REACH_DU > lo
                }
                bands += InkBand(lo, end, inside)
                at = end
            }
            return InkBandLayout(top, bottom - top, bands)
        }
    }
}

/**
 * A sketch on its page, **in full**, as a stack of bands.
 *
 * A sketch is one of the ways a page is written, not an attachment behind a thumbnail, so it shows
 * all of itself where it sits among the blocks — ten pages of notes read as ten pages. It used to be
 * one view the height of the whole drawing, capped at 2400 dp and silently cut off below that: the
 * cap was there because a single enormous view is expensive to draw and can exceed what the GPU
 * will draw at once.
 *
 * The way drawing apps with endless canvases stay fast is to make the cost of a frame depend on
 * what is on screen and never on how big the document is — tldraw culls every shape outside the
 * viewport against a spatial index; PencilKit and Figma draw in tiles. Here that is bands: each is a
 * page-slice of the sketch with its own layer, holding only the strokes that pass through it (found
 * from the per-stroke boxes StrokeCodec already caches). A band records its strokes once; scrolling
 * moves the layer and draws nothing new; and the renderer skips every band that is off screen. No
 * view is ever taller than a band, so there is no size to cap.
 *
 * [collapsed] shows only the top of it, faded out, for a sketch someone has folded away.
 */
@Composable
fun InkBands(
    strokes: List<Stroke>,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    /** Drawn over the sketch; told whether it is tall enough to fold at all. */
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.(foldable: Boolean) -> Unit = {},
) {
    val dark = Yantra.colors.isDark
    val page = Yantra.colors.page
    val display = remember(strokes, dark) { InkTheme.displayStrokes(strokes, dark) }
    val layout = remember(display) { InkBandLayout.of(display) }
    val renderer = remember { CanvasStrokeRenderer.create() }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scale = constraints.maxWidth / PAGE_WIDTH_DU
        val full = with(density) { (layout.height * scale).toDp() }.coerceAtLeast(64.dp)
        val shown = if (collapsed) minOf(full, COLLAPSED_INK_HEIGHT) else full
        val clipped = shown < full
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().height(shown).clipToBounds(),
        ) {
            androidx.compose.foundation.layout.Column {
                var above = 0.dp
                for (band in layout.bands) {
                    // Folded, the bands below the fold are not built at all.
                    if (above >= shown) break
                    val h = with(density) { ((band.bottom - band.top) * scale).toDp() }
                    above += h
                    androidx.compose.foundation.Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(h)
                            // Its own layer, clipped to itself: recorded once, moved not redrawn
                            // while the page scrolls, and skipped entirely while off screen.
                            .graphicsLayer { clip = true },
                    ) {
                        val m = Matrix().apply {
                            postTranslate(0f, -band.top)
                            postScale(scale, scale)
                        }
                        drawIntoCanvas { c ->
                            val canvas = c.nativeCanvas
                            canvas.save()
                            canvas.concat(m)
                            for (s in band.strokes) renderer.draw(canvas, s, m)
                            canvas.restore()
                        }
                    }
                }
            }
            if (clipped) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .align(androidx.compose.ui.Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, page))),
                )
            }
            overlay(full > COLLAPSED_INK_HEIGHT)
        }
    }
}

/** How tall a band is, in document units — about a third of a page. */
private const val BAND_DU = 480f

/** How far past its centreline a stroke's ink can reach: the widest marker, and then some. */
private const val STROKE_REACH_DU = 40f

/** What a folded sketch still shows. */
private val COLLAPSED_INK_HEIGHT = 180.dp
