package ie.shoonya.yantra.ui.ink

import android.graphics.Matrix
import ie.shoonya.yantra.data.ink.PAGE_HEIGHT_DU
import ie.shoonya.yantra.data.ink.PAGE_WIDTH_DU
import kotlin.math.max
import kotlin.math.min

/**
 * The camera: where the document is being looked at from, and how closely.
 *
 * There used to be no such thing. The whole transform was `postTranslate(0f, -scrollOffset)` and its
 * inverse was open-coded as "add the scroll offset to y" in five separate places — the lasso, the
 * eraser, the shape tool, the selection hit-test and the wet-to-dry handoff each knew the mapping
 * privately. A scale term would have had to be threaded through all five and through two callbacks
 * that handed view pixels to a caller expecting document units. That is why the canvas could not
 * zoom: not because zooming is hard, but because there was nowhere to put it.
 *
 * So it lives here, once, and everything asks. Document units in, view pixels out, and back.
 *
 * @see ie.shoonya.yantra.data.ink.PAGE_WIDTH_DU for what a document unit is.
 */
class Viewport {

    /** Furthest out and furthest in. Out reveals more pages; in is for going back over detail. */
    companion object {
        const val MIN_ZOOM = 0.4f
        const val MAX_ZOOM = 8f
    }

    var viewWidthPx = 0f
        private set
    var viewHeightPx = 0f
        private set

    /** 1 means one page spans the view's width — the size the page is meant to be read at. */
    var zoom = 1f
        private set

    /** The document point at the view's top-left corner. */
    var panXDu = 0f
        private set
    var panYDu = 0f
        private set

    /**
     * How many pages the document is treated as having. Set by the canvas from its content.
     *
     * Used only for clamping: it is what stops panning past the end into blank space that goes on
     * forever, which is the difference between a document and a void.
     */
    var pages = 1
        private set

    /** View pixels per document unit at the current zoom. */
    val scale: Float get() = if (viewWidthPx <= 0f) 1f else viewWidthPx / PAGE_WIDTH_DU * zoom

    private val docToViewMatrix = Matrix()
    private val viewToDocMatrix = Matrix()

    /** For `canvas.concat` and for the renderer, which wants the stroke-to-screen transform. */
    val docToView: Matrix get() = docToViewMatrix

    /** For `startStroke`, which wants to be told how to read a MotionEvent as document coordinates. */
    val viewToDoc: Matrix get() = viewToDocMatrix

    fun resize(widthPx: Float, heightPx: Float) {
        viewWidthPx = widthPx
        viewHeightPx = heightPx
        clamp()
    }

    fun setPages(count: Int) {
        pages = max(1, count)
        clamp()
    }

    fun toDocX(viewX: Float): Float = viewX / scale + panXDu

    fun toDocY(viewY: Float): Float = viewY / scale + panYDu

    fun toViewX(docX: Float): Float = (docX - panXDu) * scale

    fun toViewY(docY: Float): Float = (docY - panYDu) * scale

    /** A view-space distance as a document-space distance — a drag, an eraser radius, a slop. */
    fun toDocSpan(viewSpan: Float): Float = viewSpan / scale

    fun panBy(viewDx: Float, viewDy: Float) {
        panXDu -= viewDx / scale
        panYDu -= viewDy / scale
        clamp()
    }

    fun panToDocY(docY: Float) {
        panYDu = docY
        clamp()
    }

    /**
     * Pinches about a focal point, keeping the document under the fingers under the fingers.
     *
     * Zooming about the centre of the view instead would move whatever you were looking at out from
     * under the gesture, which reads as the page fighting back.
     */
    fun zoomBy(factor: Float, focusViewX: Float, focusViewY: Float) {
        val anchorX = toDocX(focusViewX)
        val anchorY = toDocY(focusViewY)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Put the anchor back where the fingers are.
        panXDu = anchorX - focusViewX / scale
        panYDu = anchorY - focusViewY / scale
        clamp()
    }

    /** Back to one page across, at the top of the page currently in view. Double-tap lands here. */
    fun fitWidth() {
        val wasAt = panYDu
        zoom = 1f
        panYDu = wasAt
        clamp()
    }

    val isFitWidth: Boolean get() = zoom == 1f

    /** Which page is being read, and how many there are, for the page indicator. */
    fun currentPage(): Int {
        if (PAGE_HEIGHT_DU <= 0f) return 1
        val middleish = panYDu + toDocSpan(viewHeightPx) * 0.4f
        return ((middleish / PAGE_HEIGHT_DU).toInt() + 1).coerceIn(1, pages)
    }

    private fun clamp() {
        if (viewWidthPx <= 0f || viewHeightPx <= 0f) {
            rebuildMatrices()
            return
        }
        val visibleW = toDocSpan(viewWidthPx)
        val visibleH = toDocSpan(viewHeightPx)

        // Narrower than the view: centre it, so zooming out does not leave the page pinned to one
        // edge with all the empty space on the other.
        panXDu =
            if (visibleW >= PAGE_WIDTH_DU) -(visibleW - PAGE_WIDTH_DU) / 2f
            else panXDu.coerceIn(0f, PAGE_WIDTH_DU - visibleW)

        val docH = pages * PAGE_HEIGHT_DU
        panYDu =
            if (visibleH >= docH) 0f
            else panYDu.coerceIn(0f, docH - visibleH)

        rebuildMatrices()
    }

    private fun rebuildMatrices() {
        val s = scale
        docToViewMatrix.reset()
        docToViewMatrix.postTranslate(-panXDu, -panYDu)
        docToViewMatrix.postScale(s, s)
        viewToDocMatrix.reset()
        viewToDocMatrix.postScale(1f / s, 1f / s)
        viewToDocMatrix.postTranslate(panXDu, panYDu)
    }

    /** The document rectangle currently on screen, for culling. */
    fun visibleLeft(): Float = panXDu
    fun visibleTop(): Float = panYDu
    fun visibleRight(): Float = panXDu + toDocSpan(viewWidthPx)
    fun visibleBottom(): Float = panYDu + toDocSpan(viewHeightPx)

    /** Zoom expressed for a human — "120%" — for a readout that has to mean something. */
    fun percent(): Int = (zoom * 100f).toInt().coerceAtLeast(1)

    /** Whether a further pinch would do anything, so a control can dim when it would not. */
    val canZoomIn: Boolean get() = zoom < MAX_ZOOM - 1e-4f
    val canZoomOut: Boolean get() = zoom > MIN_ZOOM + 1e-4f

    /** Smallest of the two, for gestures that want a uniform slop regardless of orientation. */
    fun shortEdgePx(): Float = min(viewWidthPx, viewHeightPx)
}
