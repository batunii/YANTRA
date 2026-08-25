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
enum class EditorTool { DRAW, SHAPE, ERASE }

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
                activePointerId = null
                activeStrokeId = null
                shapeActive = false
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
                        val pointerId = activePointerId ?: return false
                        val strokeId = activeStrokeId ?: return false
                        if (event.findPointerIndex(pointerId) >= 0) {
                            wetLayer.addToStroke(event, pointerId, strokeId, null)
                        }
                    }
                    EditorTool.SHAPE -> if (shapeActive) updateShapePreview(event.x, event.y)
                    EditorTool.ERASE -> if (erasing) eraseAt(event.x, event.y)
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
                    }
                }
                activePointerId = null
                activeStrokeId = null
                shapeActive = false
                dryLayer.clearPreview()
                erasing = false
                panning = false
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
            val commit = if (recognizeShapes) {
                val r = ShapeRecognizer.recognize(doc.inputs)
                if (r != null) Stroke(doc.brush, StrokeCodec.shapeInputs(r.kind, r.x0, r.y0, r.x1, r.y1))
                else doc
            } else doc
            dryLayer.extraStrokes = dryLayer.extraStrokes + commit
            onStrokeFinished(commit)
        }
        wetLayer.removeFinishedStrokes(strokes.keys)
        clampScrollAndNotify() // drawing near the bottom may grow the document
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

    // live shape preview (view space)
    private var previewKind: ShapeKind? = null
    private var pvx0 = 0f; private var pvy0 = 0f; private var pvx1 = 0f; private var pvy1 = 0f
    var previewColor: Int = 0xFFE06A43.toInt()

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
        for (item in items) renderer.draw(canvas, item.stroke, transform)
        for (s in extraStrokes) renderer.draw(canvas, s, transform)
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
