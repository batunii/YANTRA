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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **A lasso'd catch can be made bigger or smaller from its corner.**
 *
 * The catch could be moved and deleted and nothing else; resizing meant erasing and writing it again.
 * A handle at its bottom-right corner scales it about the top-left, previewed live, and the strokes
 * are rewritten at the new size — brush width included, so enlarged writing looks written large.
 */
@RunWith(AndroidJUnit4::class)
class SelectionResizeTest {

    private val width = 1600
    private val height = 2560
    private var scenario: ActivityScenario<ViewHostActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun stroke(vararg xy: Float): Stroke {
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (i in xy.indices step 2) { batch.add(InputToolType.STYLUS, xy[i], xy[i + 1], t); t += 8 }
        return Stroke(StrokeCodec.brush(StrokeCodec.FAMILY_PRESSURE_PEN, 0xFF000000L, 3f), batch.toImmutable())
    }

    @Test
    fun scalingAStoredStrokeGrowsItAboutTheCornerAndThickensTheLine() {
        val data = StrokeCodec.encode(stroke(100f, 100f, 200f, 150f, 300f, 300f), StrokeCodec.FAMILY_PRESSURE_PEN)

        val grown = StrokeCodec.decode(StrokeCodec.transform(data, 2f, 100f, 100f))

        // [x, y, w, h]: the corner stays put, the size doubles.
        assertArrayEquals(floatArrayOf(100f, 100f, 400f, 400f), StrokeCodec.bbox(grown.inputs), 0.01f)
        assertEquals(6f, grown.brush.size, 0.001f)
    }

    @Test
    fun draggingTheCornerHandleResizesTheCatch() {
        lateinit var c: InkCanvas
        val s = ActivityScenario.launch(ViewHostActivity::class.java)
        scenario = s
        val scaled = ArrayList<FloatArray>()
        s.onActivity { a ->
            c = InkCanvas(a).apply {
                tool = EditorTool.LASSO
                onScaleSelection = { _, k, ax, ay -> scaled += floatArrayOf(k, ax, ay) }
            }
            a.root.addView(c, android.widget.FrameLayout.LayoutParams(width, height))
        }
        val i = InstrumentationRegistry.getInstrumentation()
        i.waitForIdleSync()
        // A stroke from (200, 200) to (400, 400) in document units.
        i.runOnMainSync { c.setStrokeItems(listOf(StrokeItem("a", stroke(200f, 200f, 300f, 300f, 400f, 400f)))) }
        i.waitForIdleSync()

        val scale = width / PAGE_WIDTH_DU
        fun v(du: Float) = du * scale
        var down = 0L
        var clock = 0L
        fun send(action: Int, x: Float, y: Float) {
            clock += 16
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
            val e = MotionEvent.obtain(down, clock, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            i.runOnMainSync { c.dispatchTouchEvent(e) }
            e.recycle()
        }
        // Lasso round the stroke.
        down = clock
        val loop = listOf(150f to 150f, 450f to 150f, 450f to 450f, 150f to 450f, 150f to 160f)
        send(MotionEvent.ACTION_DOWN, v(loop[0].first), v(loop[0].second))
        loop.drop(1).forEach { (x, y) -> send(MotionEvent.ACTION_MOVE, v(x), v(y)) }
        send(MotionEvent.ACTION_UP, v(150f), v(160f))
        i.waitForIdleSync()

        // The catch spans (200, 200)-(400, 400), so the handle is at (400, 400). Drag it out to
        // twice the distance from the anchor corner.
        down = clock
        send(MotionEvent.ACTION_DOWN, v(400f), v(400f))
        for (k in 1..10) send(MotionEvent.ACTION_MOVE, v(400f + k * 20f), v(400f + k * 20f))
        send(MotionEvent.ACTION_UP, v(600f), v(600f))
        i.waitForIdleSync()

        assertEquals("one resize reported", 1, scaled.size)
        val (k, ax, ay) = scaled.single().let { Triple(it[0], it[1], it[2]) }
        assertEquals(2f, k, 0.02f)
        assertEquals(200f, ax, 0.5f)
        assertEquals(200f, ay, 0.5f)
    }
}
