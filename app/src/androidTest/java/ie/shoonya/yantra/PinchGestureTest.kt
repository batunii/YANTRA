package ie.shoonya.yantra

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.ui.ink.EditorTool
import ie.shoonya.yantra.ui.ink.InkCanvas
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pinch, as actual MotionEvents through the actual view.
 *
 * `adb input` is single-pointer and `sendevent` is refused by SELinux, so a two-finger gesture
 * cannot be driven from a shell on this hardware — which left the whole zoom path verified only as
 * arithmetic. This closes that: real events, two pointers, dispatched into a laid-out [InkCanvas],
 * so what is under test is the part `ViewportTest` cannot reach — reading a focal point and a spread
 * out of a MotionEvent, and the ACTION_POINTER_DOWN handover from drawing to panning.
 *
 * The tool is [EditorTool.LASSO] throughout. Not incidental: it keeps the ink library out of the
 * test, since a DRAW gesture would start a real stroke and drag `InProgressStrokesView`'s rendering
 * thread into a test that has no window to render into.
 */
@RunWith(AndroidJUnit4::class)
class PinchGestureTest {

    private val width = 1600
    private val height = 2560

    private var scenario: ActivityScenario<ViewHostActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    /**
     * An [InkCanvas] inside a real window.
     *
     * The window is the point. A canvas that has only been measured and laid out by hand looks
     * entirely correct and then never dispatches a touch to its children — the first version of
     * this test did exactly that and every assertion passed vacuously against a zoom that had never
     * been asked to change. The diagnostic below (`eventsReachTheCanvasAtAll`) is what caught it and
     * is why it stays.
     */
    private fun canvas(): InkCanvas {
        lateinit var c: InkCanvas
        val s = ActivityScenario.launch(ViewHostActivity::class.java)
        scenario = s
        s.onActivity { activity ->
            c = InkCanvas(activity).apply { tool = EditorTool.LASSO }
            activity.root.addView(
                c,
                android.widget.FrameLayout.LayoutParams(width, height),
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return c
    }

    private var downTime = 0L
    private var clock = 0L

    private fun send(view: View, action: Int, vararg pts: Pair<Float, Float>) {
        val props = Array(pts.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pts.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = pts[i].first; y = pts[i].second; pressure = 1f; size = 1f
            }
        }
        clock += 16
        val e = MotionEvent.obtain(
            downTime, clock, action, pts.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.dispatchTouchEvent(e) }
        e.recycle()
    }

    private fun pointerDown(index: Int) =
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** One finger down, a second joins, they spread by [factor], then both lift. */
    private fun pinch(
        c: InkCanvas,
        centreX: Float = 800f,
        centreY: Float = 1200f,
        fromHalfSpan: Float = 150f,
        factor: Float = 3f,
        steps: Int = 8,
    ) {
        downTime = 0; clock = 0
        send(c, MotionEvent.ACTION_DOWN, (centreX - fromHalfSpan) to centreY)
        send(
            c, pointerDown(1),
            (centreX - fromHalfSpan) to centreY, (centreX + fromHalfSpan) to centreY,
        )
        for (i in 1..steps) {
            val half = fromHalfSpan * (1f + (factor - 1f) * i / steps)
            send(
                c, MotionEvent.ACTION_MOVE,
                (centreX - half) to centreY, (centreX + half) to centreY,
            )
        }
        val end = fromHalfSpan * factor
        send(
            c, MotionEvent.ACTION_UP,
            (centreX - end) to centreY, (centreX + end) to centreY,
        )
    }

    @Test
    fun eventsReachTheCanvasAtAll() {
        // The first thing to know when a gesture test fails: is the gesture arriving? A stroke
        // going down flips onDrawingChanged, which is public, so this separates "the pinch is
        // wrong" from "nothing was dispatched".
        val c = canvas()
        var downs = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            c.onDrawingChanged = { if (it) downs++ }
        }
        downTime = 0; clock = 0
        send(c, MotionEvent.ACTION_DOWN, 800f to 1200f)
        send(c, MotionEvent.ACTION_UP, 800f to 1200f)
        assertEquals("ACTION_DOWN never reached the touch handler", 1, downs)
    }

    @Test
    fun twoFingersSpreadingZoomIn() {
        val c = canvas()
        assertEquals(1f, c.viewport.zoom, 1e-3f)
        pinch(c, factor = 3f)
        assertEquals("a 3x spread should be a 3x zoom", 3f, c.viewport.zoom, 0.1f)
    }

    @Test
    fun twoFingersClosingZoomOut() {
        val c = canvas()
        pinch(c, factor = 4f)
        val zoomedIn = c.viewport.zoom
        pinch(c, fromHalfSpan = 400f, factor = 0.5f)
        assertTrue("$zoomedIn should have come down", c.viewport.zoom < zoomedIn)
    }

    @Test
    fun thePageStaysUnderTheFingers() {
        val c = canvas()
        val focusX = 500f
        val focusY = 900f
        val anchorX = c.viewport.toDocX(focusX)
        val anchorY = c.viewport.toDocY(focusY)
        pinch(c, centreX = focusX, centreY = focusY, factor = 2.5f)
        // The whole feel of a pinch: what you grabbed does not slide away while you grab it.
        assertEquals(anchorX, c.viewport.toDocX(focusX), 2f)
        assertEquals(anchorY, c.viewport.toDocY(focusY), 2f)
    }

    @Test
    fun twoFingersMovingTogetherPanWithoutZooming() {
        val c = canvas()
        pinch(c, factor = 3f)     // zoom in first so there is somewhere to pan to
        val zoom = c.viewport.zoom
        val before = c.viewport.panYDu
        downTime = 0; clock = 0
        send(c, MotionEvent.ACTION_DOWN, 600f to 1600f)
        send(c, pointerDown(1), 600f to 1600f, 900f to 1600f)
        for (i in 1..8) {
            val dy = -40f * i
            send(c, MotionEvent.ACTION_MOVE, 600f to (1600f + dy), 900f to (1600f + dy))
        }
        send(c, MotionEvent.ACTION_UP, 600f to 1280f, 900f to 1280f)
        assertEquals("spread never changed, so zoom must not", zoom, c.viewport.zoom, 0.05f)
        assertTrue("dragging up should move down the document", c.viewport.panYDu > before)
    }

    @Test
    fun panningSidewaysIsPossibleOnceZoomedIn() {
        // There was no horizontal pan at all before: scrolling was y-only, so ink to the right of
        // the viewport was simply unreachable.
        val c = canvas()
        pinch(c, factor = 4f)
        val before = c.viewport.panXDu
        downTime = 0; clock = 0
        send(c, MotionEvent.ACTION_DOWN, 1200f to 1200f)
        send(c, pointerDown(1), 1200f to 1200f, 1400f to 1200f)
        for (i in 1..8) {
            val dx = -50f * i
            send(c, MotionEvent.ACTION_MOVE, (1200f + dx) to 1200f, (1400f + dx) to 1200f)
        }
        send(c, MotionEvent.ACTION_UP, 800f to 1200f, 1000f to 1200f)
        assertTrue("panX should have moved right from $before", c.viewport.panXDu > before)
    }

    @Test
    fun aCancelledSelectionDragDoesNotPoisonTheNextGesture() {
        // The bug: ACTION_CANCEL reset everything about a gesture except `movingSelection` and the
        // carried offset, so the next lasso took the carry branch and moved the selection by a delta
        // measured from a gesture that had already been abandoned — and persisted it.
        val c = canvas()
        var moves = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            c.onMoveSelection = { _, _, _ -> moves++ }
        }
        downTime = 0; clock = 0
        send(c, MotionEvent.ACTION_DOWN, 500f to 700f)
        send(c, MotionEvent.ACTION_MOVE, 560f to 760f)
        send(c, MotionEvent.ACTION_CANCEL, 560f to 760f)
        // A fresh loop somewhere else entirely.
        send(c, MotionEvent.ACTION_DOWN, 1100f to 1800f)
        send(c, MotionEvent.ACTION_MOVE, 1160f to 1860f)
        send(c, MotionEvent.ACTION_UP, 1160f to 1860f)
        assertEquals("a cancelled drag must not make the next gesture a move", 0, moves)
    }

    @Test
    fun zoomIsStillClampedWhenItComesFromFingers() {
        val c = canvas()
        repeat(4) { pinch(c, fromHalfSpan = 100f, factor = 6f) }
        assertTrue("zoom ${c.viewport.zoom}", c.viewport.zoom <= 8f + 1e-3f)
        repeat(6) { pinch(c, fromHalfSpan = 700f, factor = 0.2f) }
        assertTrue("zoom ${c.viewport.zoom}", c.viewport.zoom >= 0.4f - 1e-3f)
    }
}
