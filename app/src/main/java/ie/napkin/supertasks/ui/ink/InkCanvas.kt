package ie.napkin.supertasks.ui.ink

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
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import ie.napkin.supertasks.data.ink.ShapeKind
import ie.napkin.supertasks.data.ink.ShapeRecognizer
import ie.napkin.supertasks.data.ink.StrokeCodec
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Page height : width, like an A4 sheet in portrait. */
private val PAGE_RATIO = sqrt(2f)

/** What a one-finger gesture does on the canvas. */
enum class EditorTool { DRAW, SHAPE, ERASE, LASSO }

/** How long the pen must sit still at the end of a stroke for shape snapping to take it. */
private const val HOLD_MS = 450L

/** And how still. Wide enough for a resting hand, tight enough that a slow finish is not a hold. */
private const val HOLD_RADIUS_PX = 26f

/**
 * Samsung-Notes-style paginated drawing surface: one continuous document scrolled
 * vertically, rendered as a stack of A4-proportioned pages. One finger (or stylus) draws;
 * a second finger switches to panning. Strokes are persisted in DOCUMENT coordinates,
 * so pagination is purely a render/interaction concern — the stored data doesn't change shape.
 */
@SuppressLint("ClickableViewAccessibility")
class InkCanvas(context: Context) : FrameLayout(context), InProgressStrokesFinishedListener {

    var brushProvider: () -> Brush = { error("brushProvider not set") }

    /** Receives finished strokes already translated into document coordinates. */
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

    var tool: EditorTool = EditorTool.DRAW
    var shapeKind: ShapeKind = ShapeKind.LINE
    var recognizeShapes: Boolean = false
    var eraserRadius: Float = 44f

    private val dryLayer = DocumentStrokesView(context)
    private val wetLayer = InProgressStrokesView(context)

    private var activePointerId: Int? = null
    private var activeStrokeId: InProgressStrokeId? = null
    private var panning = false
    private var lastFocusY = 0f
    private var stylusSeen = false

    // shape gesture
    private var shapeActive = false
    private var shapeStartX = 0f
    private var shapeStartY = 0f

    // erase gesture
    private var erasing = false
    private val erasedThisGesture = HashSet<String>()

    private val pageHeight: Float get() = if (width > 0) width * PAGE_RATIO else 0f

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
        clampScrollAndNotify()
    }

    fun scrollByPages(deltaPages: Int) {
        if (pageHeight <= 0f) return
        val targetPage = floor(dryLayer.scrollOffset / pageHeight).toInt() + deltaPages
        scrollDocTo(targetPage * pageHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dryLayer.pageHeightPx = pageHeight
        clampScrollAndNotify()
    }

    // ---- geometry ----

    private fun contentPages(): Int {
        val pageH = pageHeight
        if (pageH <= 0f) return 1
        var maxY = 0f
        for (s in dryLayer.items.map { it.stroke } + dryLayer.extraStrokes) {
            val b = StrokeCodec.bbox(s.inputs) ?: continue
            maxY = max(maxY, b[1] + b[3])
        }
        return max(1, ceil((maxY + 1f) / pageH).toInt())
    }

    /** Content pages plus one blank page to grow into. */
    private fun totalPages(): Int = contentPages() + 1

    private fun maxScroll(): Float = max(0f, totalPages() * pageHeight - height)

    private fun scrollDocTo(y: Float) {
        dryLayer.scrollOffset = y.coerceIn(0f, maxScroll())
        notifyViewport()
    }

    private fun clampScrollAndNotify() = scrollDocTo(dryLayer.scrollOffset)

    private fun notifyViewport() {
        val pageH = pageHeight
        if (pageH <= 0f) return
        val total = totalPages()
        val current = (floor((dryLayer.scrollOffset + height * 0.4f) / pageH).toInt() + 1)
            .coerceIn(1, total)
        onViewportChanged(current, total)
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
                    panning = true
                    lastFocusY = focusY(event)
                    return true
                }
                panning = false
                onDrawingChanged(true)
                holdReset(event)
                endedOnHold = false
                when (tool) {
                    EditorTool.DRAW -> {
                        val pointerId = event.getPointerId(event.actionIndex)
                        activePointerId = pointerId
                        activeStrokeId = wetLayer.startStroke(event, pointerId, brushProvider())
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
                        if (insideSelection(event.x, event.y + dryLayer.scrollOffset)) {
                            movingSelection = true
                            moveFromX = event.x; moveFromY = event.y
                            dryLayer.setMove(0f, 0f)
                        } else {
                            dryLayer.selected = emptySet()
                            onLassoSelection(emptyList(), 0f, 0f)
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
                // second finger: this gesture becomes a pan, so cancel any active op
                activeStrokeId?.let { wetLayer.cancelStroke(it, event) }
                clearLasso()
                activePointerId = null
                activeStrokeId = null
                shapeActive = false
                onDrawingChanged(false)
                dryLayer.clearPreview()
                erasing = false
                panning = true
                lastFocusY = focusY(event)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (panning) {
                    val focus = focusY(event)
                    scrollDocTo(dryLayer.scrollOffset - (focus - lastFocusY))
                    lastFocusY = focus
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
                if (panning) lastFocusY = focusY(event, excludeIndex = event.actionIndex)
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
                                val dx = event.x - moveFromX
                                val dy = event.y - moveFromY
                                movingSelection = false
                                dryLayer.setMove(0f, 0f)
                                // The bounds travel with the strokes, so the next grab still finds
                                // them without waiting for the write to come back round.
                                selL += dx; selR += dx; selT += dy; selB += dy
                                onLassoSelection(
                                    dryLayer.selected.toList(),
                                    (selL + selR) / 2f,
                                    selB - dryLayer.scrollOffset,
                                )
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
                erasing = false
                panning = false
                true
            }
            else -> false
        }
    }

    private fun updateShapePreview(x: Float, y: Float) {
        dryLayer.setPreview(shapeKind, shapeStartX, shapeStartY, x, y)
    }

    private fun commitShape(endX: Float, endY: Float) {
        // ignore accidental taps with no drag
        if (hypot(endX - shapeStartX, endY - shapeStartY) < 8f) return
        val dy = dryLayer.scrollOffset
        val inputs = StrokeCodec.shapeInputs(
            shapeKind, shapeStartX, shapeStartY + dy, endX, endY + dy,
        )
        val stroke = Stroke(brushProvider(), inputs)
        dryLayer.extraStrokes = dryLayer.extraStrokes + stroke
        onStrokeFinished(stroke)
        clampScrollAndNotify()
    }

    // The loop being drawn, in document space, and what it caught.
    private val lassoX = ArrayList<Float>()
    private val lassoY = ArrayList<Float>()

    /** The catch's bounds in document space, so a touch can tell whether it landed on it. */
    private var selL = 0f
    private var selT = 0f
    private var selR = 0f
    private var selB = 0f

    /** True while the selection is being carried rather than a new loop drawn. */
    private var movingSelection = false
    private var moveFromX = 0f
    private var moveFromY = 0f

    /** How far into the selection's own space a touch may land and still count as grabbing it. */
    private val grabSlop = 24f

    private fun insideSelection(x: Float, docY: Float): Boolean =
        dryLayer.selected.isNotEmpty() &&
            x >= selL - grabSlop && x <= selR + grabSlop &&
            docY >= selT - grabSlop && docY <= selB + grabSlop

    private fun lassoPoint(x: Float, y: Float) {
        val docY = y + dryLayer.scrollOffset
        // Skip points that add nothing: a polygon test is per-point per-stroke, and a finger held
        // still would otherwise pile up hundreds of identical vertices.
        val n = lassoX.size
        if (n > 0 && hypot(x - lassoX[n - 1], docY - lassoY[n - 1]) < 3f) return
        lassoX.add(x); lassoY.add(docY)
        dryLayer.setLasso(lassoX, lassoY)
    }

    private fun commitLasso() {
        if (lassoX.size < 3) {
            clearLasso()
            onLassoSelection(emptyList(), 0f, 0f)
            return
        }
        val px = lassoX.toFloatArray()
        val py = lassoY.toFloatArray()
        val caught = dryLayer.items.filter { StrokeCodec.strokeInside(it.stroke, px, py) }
        clearLasso()
        if (caught.isEmpty()) {
            dryLayer.selected = emptySet()
            onLassoSelection(emptyList(), 0f, 0f)
            return
        }
        dryLayer.selected = caught.map { it.id }.toSet()
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        caught.forEach { item ->
            StrokeCodec.bbox(item.stroke.inputs)?.let { bb ->
                if (bb[0] < l) l = bb[0]
                if (bb[1] < t) t = bb[1]
                if (bb[0] + bb[2] > r) r = bb[0] + bb[2]
                if (bb[1] + bb[3] > b) b = bb[1] + bb[3]
            }
        }
        selL = l; selT = t; selR = r; selB = b
        onLassoSelection(caught.map { it.id }, (l + r) / 2f, b - dryLayer.scrollOffset)
    }

    private fun clearLasso() {
        lassoX.clear(); lassoY.clear()
        dryLayer.setLasso(lassoX, lassoY)
    }

    /** Drops the highlight — the caller does this when the selection has been acted on or dismissed. */
    fun clearSelection() {
        dryLayer.selected = emptySet()
    }

    private fun eraseAt(x: Float, y: Float) {
        val docX = x
        val docY = y + dryLayer.scrollOffset
        val hits = dryLayer.items.filter {
            it.id !in erasedThisGesture && StrokeCodec.strokeHit(it.stroke, docX, docY, eraserRadius)
        }
        if (hits.isEmpty()) return
        hits.forEach { erasedThisGesture.add(it.id); onErase(it.id) }
        // immediate visual feedback; the persisted flow will confirm shortly
        dryLayer.items = dryLayer.items.filter { it.id !in erasedThisGesture }
    }

    private fun focusY(event: MotionEvent, excludeIndex: Int = -1): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            sum += event.getY(i)
            n++
        }
        return if (n == 0) 0f else sum / n
    }

    // ---- wet -> dry handoff ----

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        for (raw in strokes.values) {
            val doc = toDocumentSpace(raw)
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
        clampScrollAndNotify() // drawing near the bottom may grow the document
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

    /** The wet layer records in view space; shift by the scroll offset to anchor on the page. */
    private fun toDocumentSpace(stroke: Stroke): Stroke {
        val dy = dryLayer.scrollOffset
        if (dy == 0f) return stroke
        val batch = MutableStrokeInputBatch()
        for (i in 0 until stroke.inputs.size) {
            val p = stroke.inputs[i]
            batch.add(
                p.toolType, p.x, p.y + dy, p.elapsedTimeMillis,
                p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians,
            )
        }
        return Stroke(stroke.brush, batch.toImmutable())
    }
}

/** Renders document-space strokes at a vertical scroll offset, with page separators. */
private class DocumentStrokesView(context: Context) : View(context) {

    private val renderer = CanvasStrokeRenderer.create()
    private val transform = Matrix()

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
        textSize = 28f
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

    /** Page furniture, from the theme rather than from a pair of constants per mode. */
    fun surface(separator: Int, pageLabel: Int) {
        separatorPaint.color = separator
        pageLabelPaint.color = pageLabel
        invalidate()
    }

    var pageHeightPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var scrollOffset: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var items: List<StrokeItem> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Freshly finished strokes shown until the persisted flow catches up. */
    var extraStrokes: List<Stroke> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** The loop being drawn, in document space. */
    private var lassoPath: android.graphics.Path? = null

    /** What the last loop caught, drawn with a halo so the selection is visible without a box. */
    var selected: Set<String> = emptySet()
        set(value) {
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

    fun setLasso(xs: List<Float>, ys: List<Float>) {
        lassoPath = if (xs.size < 2) null else android.graphics.Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 1 until xs.size) lineTo(xs[i], ys[i])
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pageH = pageHeightPx
        if (pageH > 0f) {
            // page boundaries visible in the current viewport
            var boundary = ceil(scrollOffset / pageH) * pageH
            while (boundary <= scrollOffset + height) {
                val y = boundary - scrollOffset
                if (y > 0.5f) {
                    canvas.drawLine(0f, y, width.toFloat(), y, separatorPaint)
                    val page = (boundary / pageH).toInt() + 1
                    canvas.drawText("$page", width - 40f, y + 36f, pageLabelPaint)
                }
                boundary += pageH
            }
        }
        transform.reset()
        transform.postTranslate(0f, -scrollOffset)
        canvas.save()
        canvas.concat(transform)
        // Everything that is staying put.
        for (item in items) if (item.id !in selected) renderer.draw(canvas, item.stroke, transform)
        for (s in extraStrokes) renderer.draw(canvas, s, transform)
        // Then the carried ones, shifted by however far the finger has taken them so far. Drawn
        // last so a group being moved passes over what it is being moved across.
        if (selected.isNotEmpty()) {
            canvas.save()
            canvas.translate(moveDx, moveDy)
            for (item in items) if (item.id in selected) renderer.draw(canvas, item.stroke, transform)
            canvas.restore()
        }
        // The catch, ringed, and the loop being drawn. Both in document space inside the same
        // transform as the strokes, so they scroll with the ink rather than beside it.
        //
        // bbox reports [x, y, width, height] — not left/top/right/bottom, which is what this first
        // read it as, and the halos then hung above and left of the ink they belonged to.
        if (selected.isNotEmpty()) {
            canvas.save()
            canvas.translate(moveDx, moveDy)
            for (item in items) {
                if (item.id !in selected) continue
                StrokeCodec.bbox(item.stroke.inputs)?.let { b ->
                    canvas.drawRoundRect(
                        b[0] - 10f, b[1] - 10f, b[0] + b[2] + 10f, b[1] + b[3] + 10f,
                        12f, 12f, selectionPaint,
                    )
                }
            }
            canvas.restore()
        }
        lassoPath?.let { canvas.drawPath(it, lassoPaint) }
        canvas.restore()

        // preview shape (view space, no scroll transform — follows the finger)
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
