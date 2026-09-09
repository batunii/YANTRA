package ie.shoonya.yantra.ui.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import ie.shoonya.yantra.data.ink.PAGE_HEIGHT_DU
import ie.shoonya.yantra.data.ink.PAGE_WIDTH_DU
import ie.shoonya.yantra.data.ink.ShapeKind
import ie.shoonya.yantra.data.ink.ShapeRecognizer
import ie.shoonya.yantra.data.ink.StrokeCodec
import ie.shoonya.yantra.data.ink.StrokePath
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What a one-finger gesture does on the canvas. */
enum class EditorTool { DRAW, SHAPE, ERASE, LASSO }

/** How long the pen must sit still at the end of a stroke for shape snapping to take it. */
private const val HOLD_MS = 450L

/** And how still. Wide enough for a resting hand, tight enough that a slow finish is not a hold. */
private const val HOLD_RADIUS_PX = 26f

/** Chrome drawn in view pixels, so it stays the same size whatever the zoom. */
private const val PAGE_LABEL_TEXT_PX = 28f
private const val PAGE_LABEL_INSET_PX = 40f

/** The halo around a caught stroke, in document units — it belongs to the ink, so it scales with it. */
private const val SELECTION_PAD_DU = 10f

/**
 * Samsung-Notes-style paginated drawing surface: one continuous document scrolled
 * vertically, rendered as a stack of A4-proportioned pages. One finger (or stylus) draws;
 * two fingers pan and pinch. Strokes are persisted in DOCUMENT coordinates — document units,
 * a thousand across the page — so pagination and zoom are purely render/interaction concerns and
 * the stored data means the same thing on every screen.
 */
@SuppressLint("ClickableViewAccessibility")
class InkCanvas(context: Context) : FrameLayout(context), InProgressStrokesFinishedListener {

    var brushProvider: () -> Brush = { error("brushProvider not set") }

    /** Receives finished strokes, already in document coordinates. */
    var onStrokeFinished: (Stroke) -> Unit = {}

    /** Called with a stroke id when the eraser touches it. */
    var onErase: (String) -> Unit = {}

    /** (currentPage, pageCount) whenever scrolling, content, or size changes. */
    var onViewportChanged: (Int, Int) -> Unit = { _, _ -> }

    /** Fires with true once a stylus is detected — fingers become scroll from then on. */
    var onStylusModeChanged: (Boolean) -> Unit = {}

    /**
     * True while something is actually being drawn, false the moment it lifts.
     *
     * Distinct from [onStylusModeChanged], which says what kind of instrument is in use and stays
     * true afterwards. The screen dims its chrome against this one, so it has to be a down/up, not
     * a mode.
     */
    var onDrawingChanged: (Boolean) -> Unit = {}

    /** The zoom as a percentage, so a readout can offer the way back to 100%. */
    var onZoomChanged: (Int) -> Unit = {}

    /**
     * What a lasso caught, and where to put the bar that acts on it.
     *
     * The ids are the caller's to act on; the two floats are the centre and the bottom of the
     * selection **in view space**, so the bar can sit under the thing it belongs to rather than in
     * a corner where it would be a menu about nothing in particular. An empty list means the
     * selection was cleared.
     */
    var onLassoSelection: (List<String>, Float, Float) -> Unit = { _, _, _ -> }

    /**
     * A finished drag of the selection: which strokes moved, and by how far in document units.
     *
     * The ids travel with the callback rather than being read back from the screen's own state.
     * This is set once, when the view is created, so anything it closed over would be whatever the
     * selection was at that moment — which is empty, forever. The canvas already knows what it is
     * carrying; it should be the one to say.
     */
    var onMoveSelection: (List<String>, Float, Float) -> Unit = { _, _, _ -> }

    /**
     * What a one-finger gesture does, and the moment a selection stops being the subject.
     *
     * Reaching for the eraser is not an instruction about the strokes you lassoed a moment ago, so
     * the bar goes with the change. It guards on the old value because the screen assigns this on
     * every recomposition — an unguarded setter would clear the selection continuously and the bar
     * would never appear at all.
     */
    var tool: EditorTool = EditorTool.DRAW
        set(value) {
            if (field == value) return
            field = value
            dropSelection()
        }

    var shapeKind: ShapeKind = ShapeKind.LINE
    var recognizeShapes: Boolean = false

    /**
     * The eraser's radius **in view pixels**, converted to document units at the moment of use.
     *
     * A physical size, not a document one: the eraser is the size of the thing you are rubbing with,
     * so it stays put under the finger while the page grows and shrinks beneath it. Keeping it in du
     * instead would make a zoomed-out eraser swallow half a page.
     */
    var eraserRadiusPx: Float = 44f

    /** The camera. Everything that has to cross between screen and page goes through it. */
    val viewport = Viewport()

    private val dryLayer = DocumentStrokesView(context, viewport)
    private val wetLayer = InProgressStrokesView(context)

    /** Identity: the wet layer's own coordinates are already this view's. */
    private val identity = Matrix()

    private var activePointerId: Int? = null
    private var activeStrokeId: InProgressStrokeId? = null
    private var panning = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var stylusSeen = false

    // shape gesture
    private var shapeActive = false
    private var shapeStartX = 0f
    private var shapeStartY = 0f

    // erase gesture
    private var erasing = false
    private val erasedThisGesture = HashSet<String>()

    /**
     * Which way round the ink reads — genuinely a boolean, because a stroke drawn in near-black has
     * to be remapped to near-white on dark paper. Distinct from [surface], which is what colour the
     * paper actually is.
     */
    var darkTheme: Boolean = false
        set(value) {
            field = value
            dryLayer.darkTheme = value
        }

    /**
     * The paper, its page separators and its page numbers, taken from the app's theme.
     *
     * These used to be six constants split across this file and [InkTheme], chosen by a boolean —
     * which meant the drawing surface was the one part of the app the theme did not reach. It also
     * meant OLED got ordinary dark grey while `YantraColors.inkPaper` was already computing pure
     * black for it and going unread. The tokens existed; nothing consumed them.
     */
    fun lasso(accent: Int) {
        dryLayer.lassoColor = accent
    }

    fun surface(paper: Int, separator: Int, pageLabel: Int) {
        setBackgroundColor(paper)
        dryLayer.surface(separator, pageLabel)
    }

    init {
        setBackgroundColor(Color.WHITE)
        addView(dryLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wetLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wetLayer.addFinishedStrokesListener(this)
        wetLayer.setOnTouchListener { view, event -> handleTouch(view, event) }
    }

    fun setStrokeItems(items: List<StrokeItem>) {
        dryLayer.items = items
        dryLayer.extraStrokes = emptyList()
        clampAndNotify()
    }

    fun scrollByPages(deltaPages: Int) {
        val target = floor(viewport.panYDu / PAGE_HEIGHT_DU).toInt() + deltaPages
        viewport.panToDocY(target * PAGE_HEIGHT_DU)
        afterViewportMove()
    }

    /** Back to one page across. The way out of a zoom, offered by the screen's readout. */
    fun fitWidth() {
        viewport.fitWidth()
        afterViewportMove()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewport.resize(w.toFloat(), h.toFloat())
        clampAndNotify()
    }

    // ---- geometry ----

    private fun contentPages(): Int {
        var maxY = 0f
        for (s in dryLayer.items.map { it.stroke } + dryLayer.extraStrokes) {
            val b = StrokeCodec.bbox(s.inputs) ?: continue
            maxY = max(maxY, b[1] + b[3])
        }
        return max(1, ceil((maxY + 1f) / PAGE_HEIGHT_DU).toInt())
    }

    /** Content pages plus one blank page to grow into. */
    private fun totalPages(): Int = contentPages() + 1

    private fun clampAndNotify() {
        viewport.setPages(totalPages())
        afterViewportMove()
    }

    // What the screen was last told, so it is not told again for no reason. Every one of these
    // callbacks writes Compose state, and a recomposition re-runs the AndroidView update — so an
    // unchanged report during a pinch costs a frame's worth of work per frame to say nothing.
    private var lastPage = -1
    private var lastPages = -1
    private var lastZoomPercent = -1

    private fun afterViewportMove() {
        dryLayer.invalidate()
        val page = viewport.currentPage()
        if (page != lastPage || viewport.pages != lastPages) {
            lastPage = page; lastPages = viewport.pages
            onViewportChanged(page, viewport.pages)
        }
        val percent = viewport.percent()
        if (percent != lastZoomPercent) {
            lastZoomPercent = percent
            onZoomChanged(percent)
        }
        reportCurrentSelection()
    }

    // ---- input ----

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.requestUnbufferedDispatch(event)
                val isStylus = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS
                if (isStylus && !stylusSeen) {
                    stylusSeen = true
                    onStylusModeChanged(true)
                }
                if (!isStylus && stylusSeen) {
                    // a stylus owns drawing on this canvas: a finger pans directly
                    beginPanning(event)
                    return true
                }
                panning = false
                // Drawing over a catch is moving on. Panning and pinching are not — those are ways
                // of looking at what you caught, often on the way to dragging it — so they leave it
                // alone and the bar follows the ink instead (see [afterViewportMove]).
                if (tool != EditorTool.LASSO) dropSelection()
                onDrawingChanged(true)
                holdReset(event)
                endedOnHold = false
                when (tool) {
                    EditorTool.DRAW -> {
                        val pointerId = event.getPointerId(event.actionIndex)
                        activePointerId = pointerId
                        // The camera goes to the ink library rather than being applied to what comes
                        // back: told how to read a MotionEvent as a document position, it hands over
                        // a stroke already in document units. The alternative — capture in view
                        // space and shift afterwards — is what `toDocumentSpace` used to do, and it
                        // could only ever undo a translation, never a zoom.
                        activeStrokeId = wetLayer.startStroke(
                            event, pointerId, brushProvider(), viewport.viewToDoc, identity,
                        )
                    }
                    EditorTool.SHAPE -> {
                        shapeActive = true
                        shapeStartX = event.x; shapeStartY = event.y
                        dryLayer.previewColor = brushProvider().colorIntArgb
                        updateShapePreview(event.x, event.y)
                    }
                    EditorTool.LASSO -> {
                        // Touching what is already caught picks it up; touching anywhere else
                        // starts a new loop, because you are pointing at something else now.
                        if (insideSelection(viewport.toDocX(event.x), viewport.toDocY(event.y))) {
                            movingSelection = true
                            moveFromX = event.x; moveFromY = event.y
                            dryLayer.setMove(0f, 0f)
                        } else {
                            // What is already caught is kept, and the new loop toggles against it.
                            // A rough catch is then a starting point rather than a dead end: loop
                            // the two you want, then loop the one you did not to drop it. Clearing
                            // instead — which is what this did — meant every attempt started from
                            // nothing and the only way to a precise selection was one precise
                            // gesture.
                            heldSelection = dryLayer.selected
                            lassoX.clear(); lassoY.clear()
                            lassoPoint(event.x, event.y)
                        }
                    }
                    EditorTool.ERASE -> {
                        erasing = true
                        erasedThisGesture.clear()
                        eraseAt(event.x, event.y)
                    }
                }
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // second finger: this gesture becomes a pan and pinch, so cancel any active op
                activeStrokeId?.let { wetLayer.cancelStroke(it, event) }
                abandonLasso()
                activePointerId = null
                activeStrokeId = null
                shapeActive = false
                onDrawingChanged(false)
                dryLayer.clearPreview()
                erasing = false
                beginPanning(event)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (panning) {
                    trackPanAndPinch(event)
                    return true
                }
                when (tool) {
                    EditorTool.DRAW -> {
                        holdTrack(event)
                        val pointerId = activePointerId ?: return false
                        val strokeId = activeStrokeId ?: return false
                        if (event.findPointerIndex(pointerId) >= 0) {
                            wetLayer.addToStroke(event, pointerId, strokeId, null)
                        }
                    }
                    EditorTool.SHAPE -> if (shapeActive) updateShapePreview(event.x, event.y)
                    EditorTool.ERASE -> if (erasing) eraseAt(event.x, event.y)
                    EditorTool.LASSO ->
                        if (movingSelection) {
                            dryLayer.setMove(event.x - moveFromX, event.y - moveFromY)
                        } else {
                            lassoPoint(event.x, event.y)
                        }
                }
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (panning) {
                    // Re-seat on what is left, excluding the finger on its way up: its last
                    // position would otherwise register as a jump the moment it stops reporting.
                    lastFocusX = focusX(event, excludeIndex = event.actionIndex)
                    lastFocusY = focusY(event, excludeIndex = event.actionIndex)
                    lastSpan = span(event, excludeIndex = event.actionIndex)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!panning) {
                    when (tool) {
                        EditorTool.DRAW -> {
                            holdTrack(event)
                            endedOnHold = event.eventTime - holdSince >= HOLD_MS
                            val pointerId = activePointerId
                            val strokeId = activeStrokeId
                            if (pointerId != null && strokeId != null &&
                                event.getPointerId(event.actionIndex) == pointerId
                            ) {
                                wetLayer.finishStroke(event, pointerId, strokeId)
                                view.performClick()
                            }
                        }
                        EditorTool.SHAPE -> if (shapeActive) commitShape(event.x, event.y)
                        EditorTool.ERASE -> Unit
                        EditorTool.LASSO ->
                            if (movingSelection) {
                                val dx = viewport.toDocSpan(event.x - moveFromX)
                                val dy = viewport.toDocSpan(event.y - moveFromY)
                                movingSelection = false
                                dryLayer.setMove(0f, 0f)
                                // The bounds travel with the strokes, so the next grab still finds
                                // them without waiting for the write to come back round.
                                selL += dx; selR += dx; selT += dy; selB += dy
                                reportSelection(dryLayer.selected.toList())
                                onMoveSelection(dryLayer.selected.toList(), dx, dy)
                            } else {
                                commitLasso()
                            }
                    }
                }
                activePointerId = null
                activeStrokeId = null
                shapeActive = false
                movingSelection = false
                dryLayer.setMove(0f, 0f)
                dryLayer.clearPreview()
                erasing = false
                panning = false
                onDrawingChanged(false)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeStrokeId?.let { wetLayer.cancelStroke(it, event) }
                activePointerId = null
                activeStrokeId = null
                shapeActive = false
                dryLayer.clearPreview()
                abandonLasso()
                // A cancelled drag has to put the carried strokes back down. Leaving these set left
                // `movingSelection` true with a stale origin, so the *next* lasso gesture took the
                // carry branch and moved the selection by a delta measured from a gesture that had
                // already been abandoned — and persisted it.
                movingSelection = false
                dryLayer.setMove(0f, 0f)
                erasing = false
                panning = false
                onDrawingChanged(false)
                true
            }
            else -> false
        }
    }

    private fun beginPanning(event: MotionEvent) {
        panning = true
        lastFocusX = focusX(event)
        lastFocusY = focusY(event)
        lastSpan = span(event)
    }

    /**
     * One gesture, two things: the fingers' midpoint moves the page and their separation scales it.
     *
     * Zoom is applied before pan, and about the focal point, so the document under the fingers
     * stays under the fingers. Doing it the other way round makes the page slide out from under a
     * pinch, which reads as the canvas arguing with you.
     */
    private fun trackPanAndPinch(event: MotionEvent) {
        val fx = focusX(event)
        val fy = focusY(event)
        val sp = span(event)
        if (event.pointerCount >= 2 && lastSpan > 0f && sp > 0f) {
            viewport.zoomBy(sp / lastSpan, fx, fy)
        }
        viewport.panBy(fx - lastFocusX, fy - lastFocusY)
        lastFocusX = fx; lastFocusY = fy; lastSpan = sp
        afterViewportMove()
    }

    private fun updateShapePreview(x: Float, y: Float) {
        dryLayer.setPreview(shapeKind, shapeStartX, shapeStartY, x, y)
    }

    private fun commitShape(endX: Float, endY: Float) {
        // ignore accidental taps with no drag
        if (hypot(endX - shapeStartX, endY - shapeStartY) < 8f) return
        val inputs = StrokeCodec.shapeInputs(
            shapeKind,
            viewport.toDocX(shapeStartX), viewport.toDocY(shapeStartY),
            viewport.toDocX(endX), viewport.toDocY(endY),
        )
        val stroke = Stroke(brushProvider(), inputs)
        dryLayer.extraStrokes = dryLayer.extraStrokes + stroke
        onStrokeFinished(stroke)
        clampAndNotify()
    }

    // The loop being drawn, in document space, and what it caught.
    private val lassoX = ArrayList<Float>()
    private val lassoY = ArrayList<Float>()

    /** The catch's bounds in document space, so a touch can tell whether it landed on it. */
    private var selL = 0f
    private var selT = 0f
    private var selR = 0f
    private var selB = 0f

    /**
     * What was already selected when the current loop began, for the loop to toggle against.
     *
     * Empty for a first loop, which makes toggling and replacing the same thing — so there is no
     * mode here, only the accumulated state of what you have pointed at so far.
     */
    private var heldSelection: Set<String> = emptySet()

    /** True while the selection is being carried rather than a new loop drawn. */
    private var movingSelection = false
    private var moveFromX = 0f
    private var moveFromY = 0f

    /** How far into the selection's own space a touch may land and still count as grabbing it. */
    private val grabSlopPx = 24f

    private fun insideSelection(docX: Float, docY: Float): Boolean {
        if (dryLayer.selected.isEmpty()) return false
        val slop = viewport.toDocSpan(grabSlopPx)
        return docX >= selL - slop && docX <= selR + slop &&
            docY >= selT - slop && docY <= selB + slop
    }

    private fun lassoPoint(x: Float, y: Float) {
        val docX = viewport.toDocX(x)
        val docY = viewport.toDocY(y)
        // Skip points that add nothing: a polygon test is per-point per-stroke, and a finger held
        // still would otherwise pile up hundreds of identical vertices. The threshold is a screen
        // distance so it stays a "did the finger move" test rather than a document one.
        val n = lassoX.size
        if (n > 0 && hypot(x - viewport.toViewX(lassoX[n - 1]), y - viewport.toViewY(lassoY[n - 1])) < 3f) return
        lassoX.add(docX); lassoY.add(docY)
        dryLayer.setLasso(lassoX, lassoY)
        previewLasso()
    }

    /**
     * Highlights what the loop would take, while it is still being drawn.
     *
     * The catch used to be computed only on lift, so growing the loop was guesswork: you found out
     * what you had caught after it was too late to aim. Now the halo appears as you go and you stop
     * when the right thing is lit. Culled by bounding box first — the scoring is arc-length work and
     * this runs on every few pixels of finger movement.
     */
    private fun previewLasso() {
        if (lassoX.size < 3) {
            dryLayer.selected = heldSelection
            return
        }
        dryLayer.selected = toggled(scoreLasso())
    }

    /** The loop's catch, symmetric-differenced against what was already held. */
    private fun toggled(caught: List<String>): Set<String> {
        if (heldSelection.isEmpty()) return caught.toSet()
        val hit = caught.toSet()
        return (heldSelection - hit) + (hit - heldSelection)
    }

    /**
     * Which strokes the current loop catches, most-contained first.
     *
     * See [StrokeCodec.lassoCatch] for the rule. This runs on every few pixels of finger movement,
     * because the catch is previewed live, so it is careful about what it does per call: the
     * bounding-box cull uses boxes computed when the stroke set was last set, and the survivors are
     * scored against paths cached the same way. Extracting a path from a `Stroke` costs two array
     * allocations and a native read per input point, and doing that for every stroke on the page
     * before culling — which is what the first version did — made drawing a lasso stall.
     *
     * Only committed strokes are candidates. A stroke that has been drawn but not yet round-tripped
     * through the view model has no id anything else would recognise, so selecting one produced a
     * catch that could not be moved, deleted or drawn with a halo. The window is a few frames wide;
     * the honest answer is that it is not selectable until it has an id.
     */
    private fun scoreLasso(): List<String> {
        if (lassoX.size < 3) return emptyList()
        val px = lassoX.toFloatArray()
        val py = lassoY.toFloatArray()
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in px.indices) {
            if (px[i] < l) l = px[i]
            if (px[i] > r) r = px[i]
            if (py[i] < t) t = py[i]
            if (py[i] > b) b = py[i]
        }
        val near = ArrayList<StrokePath>()
        for (i in dryLayer.items.indices) {
            val box = dryLayer.boxAt(i) ?: continue
            if (box[0] > r || box[0] + box[2] < l || box[1] > b || box[1] + box[3] < t) continue
            near += dryLayer.pathAt(i) ?: continue
        }
        if (near.isEmpty()) return emptyList()
        return StrokeCodec.lassoCatchPaths(near, px, py)
    }

    private fun commitLasso() {
        val chosen = if (lassoX.size < 3) heldSelection else toggled(scoreLasso())
        clearLasso()
        heldSelection = emptySet()
        dryLayer.selected = chosen
        if (chosen.isEmpty()) {
            onLassoSelection(emptyList(), 0f, 0f)
            return
        }
        recomputeSelectionBounds()
        // Draw order, so the bar's count reads the same way the ink is stacked.
        reportSelection(dryLayer.items.map { it.id }.filter { it in chosen })
    }

    private fun recomputeSelectionBounds() {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        val chosen = dryLayer.selected
        for (item in dryLayer.items) {
            if (item.id !in chosen) continue
            dryLayer.boxOf(item.id)?.let { bb ->
                if (bb[0] < l) l = bb[0]
                if (bb[1] < t) t = bb[1]
                if (bb[0] + bb[2] > r) r = bb[0] + bb[2]
                if (bb[1] + bb[3] > b) b = bb[1] + bb[3]
            }
        }
        if (l > r) return
        selL = l; selT = t; selR = r; selB = b
    }

    /** The bar goes under the catch, so the canvas converts document bounds into view space. */
    private fun reportSelection(ids: List<String>) {
        onLassoSelection(
            ids,
            viewport.toViewX((selL + selR) / 2f),
            viewport.toViewY(selB),
        )
    }

    private fun clearLasso() {
        lassoX.clear(); lassoY.clear()
        dryLayer.setLasso(lassoX, lassoY)
    }

    /**
     * Drops a loop that was interrupted rather than finished, and un-does what it was previewing.
     *
     * The live preview writes the toggled catch straight into the highlight as the loop is drawn,
     * without telling the screen — the bar is only updated on lift. So a loop that never lifts left
     * the halo showing one set and the bar holding another, and the bar's Delete acted on the ids it
     * was holding: it removed the strokes that were *not* lit up. Putting the highlight back to what
     * it was before the loop started is what makes the two agree again.
     */
    private fun abandonLasso() {
        clearLasso()
        if (dryLayer.selected != heldSelection) dryLayer.selected = heldSelection
        heldSelection = emptySet()
    }

    /** Drops the highlight — the caller does this when the selection has been acted on or dismissed. */
    fun clearSelection() {
        dryLayer.selected = emptySet()
        heldSelection = emptySet()
    }

    /** Drops it *and* says so, so the bar goes with it. */
    private fun dropSelection() {
        if (dryLayer.selected.isEmpty() && heldSelection.isEmpty()) return
        clearSelection()
        onLassoSelection(emptyList(), 0f, 0f)
    }

    /**
     * Re-states where the current selection is, in view space.
     *
     * Called whenever the camera moves, because the bar is placed under the strokes it belongs to
     * and those strokes are in document space: pan without this and the ink slides away while the
     * bar sits where the ink used to be, which makes it a menu about a spot on the glass.
     *
     * Cheap to call redundantly — the ids and the offset compare equal when nothing has moved, so
     * an unchanged report does not recompose anything.
     */
    private fun reportCurrentSelection() {
        val chosen = dryLayer.selected
        if (chosen.isEmpty()) return
        recomputeSelectionBounds()
        reportSelection(dryLayer.items.map { it.id }.filter { it in chosen })
    }

    private fun eraseAt(x: Float, y: Float) {
        val docX = viewport.toDocX(x)
        val docY = viewport.toDocY(y)
        val radius = viewport.toDocSpan(eraserRadiusPx)
        val hits = dryLayer.items.filter {
            it.id !in erasedThisGesture && StrokeCodec.strokeHit(it.stroke, docX, docY, radius)
        }
        if (hits.isEmpty()) return
        hits.forEach { erasedThisGesture.add(it.id); onErase(it.id) }
        // immediate visual feedback; the persisted flow will confirm shortly
        dryLayer.items = dryLayer.items.filter { it.id !in erasedThisGesture }
    }

    private fun focusX(event: MotionEvent, excludeIndex: Int = -1): Float =
        average(event, excludeIndex) { i -> event.getX(i) }

    private fun focusY(event: MotionEvent, excludeIndex: Int = -1): Float =
        average(event, excludeIndex) { i -> event.getY(i) }

    private inline fun average(event: MotionEvent, excludeIndex: Int, of: (Int) -> Float): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            sum += of(i)
            n++
        }
        return if (n == 0) 0f else sum / n
    }

    /**
     * How far apart the fingers are — the pinch's raw material.
     *
     * The mean distance from the focal point rather than the distance between the first two
     * pointers, so a third finger landing does not make the scale lurch.
     */
    private fun span(event: MotionEvent, excludeIndex: Int = -1): Float {
        val cx = focusX(event, excludeIndex)
        val cy = focusY(event, excludeIndex)
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            sum += hypot(event.getX(i) - cx, event.getY(i) - cy)
            n++
        }
        return if (n < 2) 0f else sum / n
    }

    // ---- wet -> dry handoff ----

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        for (doc in strokes.values) {
            // Already in document units: the transform went in with startStroke, so there is
            // nothing to undo here.
            //
            // When shape-snapping is on, replace a freehand stroke that reads as a shape.
            // The toggle is the gate. A dwell before lifting is tracked (see holdTrack) and was
            // briefly required as well — "draw and hold", which is what most tablets do — but it
            // could not be shown to work here, and a gate that might never open is worse than one
            // that opens too easily: the feature would be on and do nothing, which is the exact
            // fault being fixed. Snapping applies on lift while it is switched on, and switching it
            // on is the opt-in.
            val commit = if (recognizeShapes) {
                val r = ShapeRecognizer.recognize(doc.inputs)
                if (r == null) doc
                // A shape that carries its corners is drawn through them; one defined by its box
                // is drawn from the box.
                else if (r.vx != null && r.vy != null) Stroke(doc.brush, StrokeCodec.polygonInputs(r.vx, r.vy))
                else Stroke(doc.brush, StrokeCodec.shapeInputs(r.kind, r.x0, r.y0, r.x1, r.y1))
            } else doc
            dryLayer.extraStrokes = dryLayer.extraStrokes + commit
            onStrokeFinished(commit)
        }
        wetLayer.removeFinishedStrokes(strokes.keys)
        clampAndNotify() // drawing near the bottom may grow the document
    }

    // Where the pen last actually moved, and when — the raw material of "and hold".
    private var holdX = 0f
    private var holdY = 0f
    private var holdSince = 0L

    /** Set at the moment of lift, read when the stroke comes back finished. */
    private var endedOnHold = false

    private fun holdReset(event: MotionEvent) {
        holdX = event.x; holdY = event.y; holdSince = event.eventTime
    }

    /**
     * Tracks the dwell, from the view's own event stream rather than the stroke's inputs.
     *
     * The inputs are the wrong source: the ink library drops samples that do not advance the
     * stroke, so a pen held perfectly still contributes nothing to them and the dwell is invisible
     * exactly when it happens. MotionEvent keeps arriving regardless — that is what "the pointer is
     * still down here" looks like — so the timing is read from there.
     */
    private fun holdTrack(event: MotionEvent) {
        if (hypot(event.x - holdX, event.y - holdY) > HOLD_RADIUS_PX) holdReset(event)
    }
}

/** Renders document-space strokes through a [Viewport], with page separators. */
private class DocumentStrokesView(context: Context, private val viewport: Viewport) : View(context) {

    private val renderer = CanvasStrokeRenderer.create()

    private val separatorPaint = Paint().apply { strokeWidth = 2f }

    /** The loop, dashed, so it reads as a gesture in progress rather than something drawn. */
    private val lassoPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    /** The halo around what was caught. Same ink as the loop, no dashes — this one is settled. */
    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val pageLabelPaint = Paint().apply {
        textSize = PAGE_LABEL_TEXT_PX
        isAntiAlias = true
    }
    private val previewPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val previewRect = RectF()

    var darkTheme: Boolean = false

    /** One stroke's cached envelope, by id. */
    fun boxOf(id: String): FloatArray? {
        val i = items.indexOfFirst { it.id == id }
        return if (i < 0) null else itemBoxes.getOrNull(i)
    }

    /** Page furniture, from the theme rather than from a pair of constants per mode. */
    fun surface(separator: Int, pageLabel: Int) {
        separatorPaint.color = separator
        pageLabelPaint.color = pageLabel
        invalidate()
    }

    var items: List<StrokeItem> = emptyList()
        set(value) {
            // The screen re-runs its AndroidView update on every recomposition and hands the same
            // list back; recomputing boxes and paths for it would be a full walk of every input
            // point on the page, per frame, during a pinch.
            if (field === value) return
            field = value
            itemBoxes = value.map { StrokeCodec.bbox(it.stroke.inputs) }
            itemPaths = value.map { StrokeCodec.path(it.stroke, it.id) }
            invalidate()
        }

    /** Freshly finished strokes shown until the persisted flow catches up. */
    var extraStrokes: List<Stroke> = emptyList()
        set(value) {
            field = value
            extraBoxes = value.map { StrokeCodec.bbox(it.inputs) }
            invalidate()
        }

    /**
     * Each stroke's envelope, computed when the set changes rather than when it is drawn.
     *
     * Culling needs a box per stroke per frame, and a box costs a walk of every input point. Doing
     * that inside `onDraw` would have made the cull more expensive than the drawing it saves on any
     * page whose ink is mostly on screen — which is most pages.
     */
    private var itemBoxes: List<FloatArray?> = emptyList()
    private var extraBoxes: List<FloatArray?> = emptyList()

    /**
     * Each stroke as a bare polyline, for the lasso to score against.
     *
     * Cached for the same reason as the boxes and a sharper one: pulling a path out of a `Stroke`
     * reads every input point across the ink library's native boundary, and the live lasso preview
     * would otherwise pay for that on every stroke on the page, several times a second.
     */
    private var itemPaths: List<StrokePath> = emptyList()

    fun boxAt(i: Int): FloatArray? = itemBoxes.getOrNull(i)

    fun pathAt(i: Int): StrokePath? = itemPaths.getOrNull(i)

    /** The loop being drawn, in document space. */
    private var lassoPath: android.graphics.Path? = null

    /** What the loop has caught, drawn with a halo so the selection is visible without a box. */
    var selected: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** How far the carried selection is from home, while a finger is still on it. */
    private var moveDx = 0f
    private var moveDy = 0f

    fun setMove(dx: Float, dy: Float) {
        if (moveDx == dx && moveDy == dy) return
        moveDx = dx; moveDy = dy
        invalidate()
    }

    /**
     * The loop, **closed**.
     *
     * It was drawn open while the geometry closed it implicitly, so the region being tested and the
     * region being shown were different shapes — and the one being shown looked unfinished, which
     * invited drawing a bigger loop than the test needed.
     */
    fun setLasso(xs: List<Float>, ys: List<Float>) {
        lassoPath = if (xs.size < 2) null else android.graphics.Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 1 until xs.size) lineTo(xs[i], ys[i])
            close()
        }
        invalidate()
    }

    // live shape preview (view space)
    private var previewKind: ShapeKind? = null
    private var pvx0 = 0f; private var pvy0 = 0f; private var pvx1 = 0f; private var pvy1 = 0f
    var previewColor: Int = 0xFFE06A43.toInt()

    /** The accent, for the loop and the halo. Set with the rest of the surface colours. */
    var lassoColor: Int = 0xFFE06A43.toInt()
        set(value) {
            field = value
            lassoPaint.color = value
            selectionPaint.color = value
            invalidate()
        }

    fun setPreview(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float) {
        previewKind = kind; pvx0 = x0; pvy0 = y0; pvx1 = x1; pvy1 = y1
        invalidate()
    }

    fun clearPreview() {
        if (previewKind != null) {
            previewKind = null
            invalidate()
        }
    }

    /** Anything whose box is off screen is not drawn. Cheap, and it is what makes zooming out sane. */
    private fun visible(b: FloatArray?): Boolean {
        if (b == null) return false
        return b[0] <= viewport.visibleRight() && b[0] + b[2] >= viewport.visibleLeft() &&
            b[1] <= viewport.visibleBottom() && b[1] + b[3] >= viewport.visibleTop()
    }

    /** Rebuilt only when the zoom changes, because it is allocation and onDraw runs constantly. */
    private var dashScale = Float.NaN

    private fun updateOverlayScale(scale: Float) {
        selectionPaint.strokeWidth = 2f / scale
        lassoPaint.strokeWidth = 2.5f / scale
        if (dashScale != scale) {
            dashScale = scale
            lassoPaint.pathEffect = android.graphics.DashPathEffect(
                floatArrayOf(10f / scale, 8f / scale), 0f,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = viewport.scale

        // ---- page furniture, in view pixels ----
        //
        // Outside the document transform on purpose. A separator is not a line drawn on the page,
        // it is the edge of one, and a page number is a label about the document rather than
        // content in it. Inside the transform they would thin to invisibility as you zoomed out and
        // swell into slabs as you zoomed in — chrome that changes size is chrome that is wrong.
        val pageEdgeRight = viewport.toViewX(PAGE_WIDTH_DU)
        val pageEdgeLeft = viewport.toViewX(0f)
        var boundary = ceil(viewport.visibleTop() / PAGE_HEIGHT_DU) * PAGE_HEIGHT_DU
        while (boundary <= viewport.visibleBottom()) {
            val y = viewport.toViewY(boundary)
            if (y > 0.5f) {
                canvas.drawLine(pageEdgeLeft, y, pageEdgeRight, y, separatorPaint)
                val page = (boundary / PAGE_HEIGHT_DU).toInt() + 1
                canvas.drawText(
                    "$page",
                    pageEdgeRight - PAGE_LABEL_INSET_PX,
                    y + PAGE_LABEL_TEXT_PX + 8f,
                    pageLabelPaint,
                )
            }
            boundary += PAGE_HEIGHT_DU
        }

        // ---- the ink, in document units ----
        val transform = viewport.docToView
        canvas.save()
        canvas.concat(transform)
        // Everything that is staying put.
        for ((i, item) in items.withIndex()) {
            if (item.id in selected) continue
            if (!visible(itemBoxes.getOrNull(i))) continue
            renderer.draw(canvas, item.stroke, transform)
        }
        for ((i, extra) in extraStrokes.withIndex()) {
            if (visible(extraBoxes.getOrNull(i))) renderer.draw(canvas, extra, transform)
        }
        // Then the carried ones, shifted by however far the finger has taken them so far. Drawn
        // last so a group being moved passes over what it is being moved across.
        if (selected.isNotEmpty()) {
            canvas.save()
            canvas.translate(moveDx / scale, moveDy / scale)
            for (item in items) if (item.id in selected) renderer.draw(canvas, item.stroke, transform)
            canvas.restore()
        }
        // The catch, ringed, and the loop being drawn. Both in document space inside the same
        // transform as the strokes, so they scroll with the ink rather than beside it — but with
        // their stroke widths divided by the scale, because a 2px outline should stay 2px whatever
        // the zoom.
        //
        // bbox reports [x, y, width, height] — not left/top/right/bottom, which is what this first
        // read it as, and the halos then hung above and left of the ink they belonged to.
        updateOverlayScale(scale)
        if (selected.isNotEmpty()) {
            canvas.save()
            canvas.translate(moveDx / scale, moveDy / scale)
            for ((i, item) in items.withIndex()) {
                if (item.id !in selected) continue
                itemBoxes.getOrNull(i)?.let { b ->
                    canvas.drawRoundRect(
                        b[0] - SELECTION_PAD_DU, b[1] - SELECTION_PAD_DU,
                        b[0] + b[2] + SELECTION_PAD_DU, b[1] + b[3] + SELECTION_PAD_DU,
                        12f, 12f, selectionPaint,
                    )
                }
            }
            canvas.restore()
        }
        lassoPath?.let { canvas.drawPath(it, lassoPaint) }
        canvas.restore()

        // preview shape (view space, no document transform — follows the finger)
        previewKind?.let { kind ->
            previewPaint.color = previewColor
            val l = min(pvx0, pvx1); val t = min(pvy0, pvy1)
            val r = max(pvx0, pvx1); val b = max(pvy0, pvy1)
            when (kind) {
                ShapeKind.LINE -> canvas.drawLine(pvx0, pvy0, pvx1, pvy1, previewPaint)
                ShapeKind.RECTANGLE -> canvas.drawRect(l, t, r, b, previewPaint)
                ShapeKind.ELLIPSE -> { previewRect.set(l, t, r, b); canvas.drawOval(previewRect, previewPaint) }
                ShapeKind.TRIANGLE -> {
                    val cx = (l + r) / 2f
                    canvas.drawLine(cx, t, r, b, previewPaint)
                    canvas.drawLine(r, b, l, b, previewPaint)
                    canvas.drawLine(l, b, cx, t, previewPaint)
                }
                ShapeKind.ARROW -> {
                    canvas.drawLine(pvx0, pvy0, pvx1, pvy1, previewPaint)
                    val ang = atan2((pvy1 - pvy0), (pvx1 - pvx0))
                    val head = hypot(pvx1 - pvx0, pvy1 - pvy0) * 0.22f
                    val spread = 0.45f
                    canvas.drawLine(pvx1, pvy1, pvx1 - head * cos(ang - spread), pvy1 - head * sin(ang - spread), previewPaint)
                    canvas.drawLine(pvx1, pvy1, pvx1 - head * cos(ang + spread), pvy1 - head * sin(ang + spread), previewPaint)
                }
            }
        }
    }
}
