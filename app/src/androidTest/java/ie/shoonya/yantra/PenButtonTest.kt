package ie.shoonya.yantra

import android.view.InputDevice
import android.view.MotionEvent
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.ink.PAGE_WIDTH_DU
import ie.shoonya.yantra.data.ink.StrokeCodec
import ie.shoonya.yantra.ui.ink.EditorTool
import ie.shoonya.yantra.ui.ink.InkCanvas
import ie.shoonya.yantra.ui.ink.StrokeItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Holding the pen's button makes it another tool until the button is let go.**
 *
 * The eraser by default: draw, hold the barrel button and rub out, let go and draw on, without the
 * hand leaving the page for the kit. The pen's eraser end, on pens that have one, always erases.
 *
 * Real MotionEvents with a stylus tool type and the barrel button in their button state, dispatched
 * into an [InkCanvas] in a real window. The shell cannot inject into the S Pen's own input device on
 * this hardware (SELinux refuses `sendevent`), so these are the events the framework delivers for
 * that hardware, built by hand.
 *
 * The kit's tool stays [EditorTool.LASSO] in the cases that would otherwise draw, which keeps the ink
 * library's rendering thread out of a window with nothing to render — see [PinchGestureTest].
 */
@RunWith(AndroidJUnit4::class)
class PenButtonTest {

    private val width = 1600
    private val height = 2560
    private var scenario: ActivityScenario<ViewHostActivity>? = null

    private val held = ArrayList<EditorTool?>()
    private val erased = ArrayList<String>()

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** A canvas holding one stroke that crosses the whole page at 200 du down. */
    private fun canvas(tool: EditorTool, buttonTool: EditorTool? = EditorTool.ERASE): InkCanvas {
        lateinit var c: InkCanvas
        val s = ActivityScenario.launch(ViewHostActivity::class.java)
        scenario = s
        s.onActivity { activity ->
            c = InkCanvas(activity).apply {
                this.tool = tool
                this.buttonTool = buttonTool
                onHeldToolChanged = { held += it }
                onErase = { erased += it }
            }
            activity.root.addView(c, android.widget.FrameLayout.LayoutParams(width, height))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (x in listOf(0f, PAGE_WIDTH_DU / 2, PAGE_WIDTH_DU)) {
            batch.add(InputToolType.STYLUS, x, 200f, t); t += 8
        }
        val line = Stroke(StrokeCodec.brush(StrokeCodec.FAMILY_PRESSURE_PEN, 0xFF000000L, 3f), batch.toImmutable())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            c.setStrokeItems(listOf(StrokeItem("line", line)))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return c
    }

    private var downTime = 0L
    private var clock = 0L

    private fun event(action: Int, x: Float, y: Float, toolType: Int, buttons: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.6f; size = 1f })
        clock += 16
        return MotionEvent.obtain(
            downTime, clock, action, 1, props, coords,
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
        )
    }

    /** A pen stroke straight down the middle of the page, across the stored line. */
    private fun strokeDown(c: InkCanvas, toolType: Int, buttons: Int) {
        val i = InstrumentationRegistry.getInstrumentation()
        downTime = clock
        val x = width / 2f
        val ys = (0..12).map { 40f + it * 60f }
        fun send(e: MotionEvent) { i.runOnMainSync { c.dispatchTouchEvent(e) }; e.recycle() }
        send(event(MotionEvent.ACTION_DOWN, x, ys.first(), toolType, buttons))
        ys.drop(1).forEach { send(event(MotionEvent.ACTION_MOVE, x, it, toolType, buttons)) }
        send(event(MotionEvent.ACTION_UP, x, ys.last(), toolType, buttons))
        i.waitForIdleSync()
    }

    @Test
    fun holdingTheButtonErasesAndLettingGoGivesThePenBack() {
        val c = canvas(tool = EditorTool.LASSO)

        strokeDown(c, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY)

        assertEquals("the stroke under the pen was not erased", listOf("line"), erased)
        assertEquals("the eraser should light while held and go out on lift", listOf(EditorTool.ERASE, null), held)
        assertEquals("the kit's own tool is untouched", EditorTool.LASSO, c.tool)
    }

    @Test
    fun withoutTheButtonThePenIsTheKitsTool() {
        val c = canvas(tool = EditorTool.LASSO)

        strokeDown(c, MotionEvent.TOOL_TYPE_STYLUS, buttons = 0)

        assertTrue("a plain stroke must not erase: $erased", erased.isEmpty())
        assertTrue("nothing is held: $held", held.all { it == null })
    }

    @Test
    fun theEraserEndAlwaysErases() {
        val c = canvas(tool = EditorTool.LASSO, buttonTool = null)

        strokeDown(c, MotionEvent.TOOL_TYPE_ERASER, buttons = 0)

        assertEquals(listOf("line"), erased)
    }

    @Test
    fun aButtonSetToNothingIsIgnored() {
        val c = canvas(tool = EditorTool.LASSO, buttonTool = null)

        strokeDown(c, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY)

        assertTrue("the button was set to nothing: $erased", erased.isEmpty())
    }

    @Test
    fun theButtonCanHoldTheLassoInstead() {
        val c = canvas(tool = EditorTool.ERASE, buttonTool = EditorTool.LASSO)

        strokeDown(c, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY)

        assertTrue("held as the lasso, the pen must not erase: $erased", erased.isEmpty())
        assertEquals(listOf(EditorTool.LASSO, null), held)
    }

    @Test
    fun hoveringWithTheButtonDownShowsTheHeldToolBeforeThePenLands() {
        val c = canvas(tool = EditorTool.LASSO)
        val i = InstrumentationRegistry.getInstrumentation()
        fun hover(action: Int, buttons: Int) {
            val e = event(action, 800f, 600f, MotionEvent.TOOL_TYPE_STYLUS, buttons)
            e.source = InputDevice.SOURCE_STYLUS
            i.runOnMainSync { c.getChildAt(1).dispatchGenericMotionEvent(e) }
            e.recycle()
        }

        hover(MotionEvent.ACTION_HOVER_ENTER, 0)
        hover(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.BUTTON_STYLUS_PRIMARY)
        hover(MotionEvent.ACTION_HOVER_MOVE, 0)
        i.waitForIdleSync()

        assertEquals(listOf(EditorTool.ERASE, null), held)
    }
}
