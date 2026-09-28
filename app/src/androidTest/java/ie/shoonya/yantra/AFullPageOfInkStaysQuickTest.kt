package ie.shoonya.yantra

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import ie.shoonya.yantra.data.ink.StrokeCodec
import ie.shoonya.yantra.ui.ink.InkTheme
import ie.shoonya.yantra.ui.ink.StrokeItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Adding a stroke measures one stroke, not the page.**
 *
 * On the tablet a sketch got slower to draw on as it filled up. Every stroke that landed handed the
 * canvas a new list, and the canvas re-read every point of every stroke on the page to rebuild each
 * one's box and lasso path — across the ink library's native boundary, allocating an object per
 * point. The 273-stroke, 75,208-point sketch from that morning cost 320–460 ms on the main thread
 * per stroke; measured the same way after this change, 5–11 ms.
 *
 * Asserted as identity rather than as a timing, which would be flaky: the second ask for a stroke's
 * geometry returns the very arrays the first one built, and the theme's recoloured copy of a stroke
 * is the same copy every time and carries the original's geometry with it.
 */
@RunWith(AndroidJUnit4::class)
class AFullPageOfInkStaysQuickTest {

    private fun stroke(color: Long, vararg xy: Float): Stroke {
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (i in xy.indices step 2) {
            batch.add(InputToolType.UNKNOWN, xy[i], xy[i + 1], t)
            t += 8L
        }
        return Stroke(StrokeCodec.brush(StrokeCodec.FAMILY_PRESSURE_PEN, color, 3f), batch.toImmutable())
    }

    @Test
    fun aStrokeIsMeasuredOnce() {
        val s = stroke(InkTheme.BLACK_INK, 10f, 20f, 500f, 600f, 990f, 140f)

        val first = StrokeCodec.path(s)
        assertSame("the second ask re-read the points", first, StrokeCodec.path(s))
        assertSame(StrokeCodec.bbox(s), StrokeCodec.bbox(s))
        // An id is a label on the same points, not a reason to read them again.
        assertSame(first.xs, StrokeCodec.path(s, "live-3").xs)
        assertEquals("live-3", StrokeCodec.path(s, "live-3").id)
    }

    @Test
    fun theRememberedGeometryIsTheStrokesOwn() {
        val s = stroke(InkTheme.BLACK_INK, 10f, 20f, 500f, 600f, 990f, 140f)

        assertArrayEquals(floatArrayOf(10f, 500f, 990f), StrokeCodec.path(s).xs, 0f)
        assertArrayEquals(floatArrayOf(20f, 600f, 140f), StrokeCodec.path(s).ys, 0f)
        assertArrayEquals(StrokeCodec.bbox(s.inputs), StrokeCodec.bbox(s), 0f)
        assertArrayEquals(floatArrayOf(10f, 20f, 980f, 580f), StrokeCodec.bbox(s), 0f)
    }

    @Test
    fun aRecolouredStrokeIsTheSameCopyWithTheSameGeometry() {
        // Chalk ink shown on light paper: the one case the theme copies a stroke for.
        val chalk = stroke(InkTheme.WHITE_INK, 10f, 20f, 500f, 600f)
        val original = StrokeCodec.bbox(chalk)
        val items = listOf(StrokeItem("a", chalk))

        val once = InkTheme.displayItems(items, dark = false).single().stroke
        val twice = InkTheme.displayItems(items, dark = false).single().stroke

        assertNotSame("light paper should draw chalk ink as graphite", chalk, once)
        assertSame("a fresh copy per ask is a stranger to the geometry cache", once, twice)
        assertSame(original, StrokeCodec.bbox(once))
    }
}
