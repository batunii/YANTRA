package ie.napkin.supertasks

import ie.napkin.supertasks.data.ink.ShapeKind
import ie.napkin.supertasks.data.ink.ShapeRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * What shape snapping is allowed to conclude.
 *
 * Written after it concluded "rectangle" for everything. The two candidates were scored in
 * different units — the ellipse by a dimensionless radial ratio, the rectangle by a distance — and
 * then compared against each other, and the rectangle's own threshold was one that any closed blob
 * passes, since the box being measured against is drawn around the blob. A circle survived only
 * because a near-perfect circle scores near zero on everything.
 */
class ShapeRecognizerTest {

    private fun recognize(pts: List<Pair<Float, Float>>) = ShapeRecognizer.recognize(
        FloatArray(pts.size) { pts[it].first },
        FloatArray(pts.size) { pts[it].second },
    )

    /** A circle of [n] points, jittered by up to [wobble] px — a hand, not a compass. */
    private fun circle(cx: Float, cy: Float, r: Float, n: Int = 40, wobble: Float = 0f): List<Pair<Float, Float>> {
        val rnd = Random(7)
        return (0..n).map { i ->
            val t = 2 * PI * i / n
            val w = if (wobble == 0f) 0f else (rnd.nextFloat() - 0.5f) * 2f * wobble
            ((cx + (r + w) * cos(t)).toFloat() to (cy + (r + w) * sin(t)).toFloat())
        }
    }

    /** A closed axis-aligned box walked corner to corner. */
    private fun box(l: Float, t: Float, r: Float, b: Float, per: Int = 12): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
            for (i in 0 until per) {
                val f = i.toFloat() / per
                out += (x0 + (x1 - x0) * f) to (y0 + (y1 - y0) * f)
            }
        }
        edge(l, t, r, t); edge(r, t, r, b); edge(r, b, l, b); edge(l, b, l, t)
        out += l to t
        return out
    }

    @Test
    fun `a clean circle is an ellipse`() {
        val r = recognize((circle(500f, 500f, 200f)))
        assertEquals(ShapeKind.ELLIPSE, r?.kind)
    }

    @Test
    fun `a hand-drawn circle is still an ellipse, not a rectangle`() {
        // The reported bug: anything short of a perfect circle came back a box.
        val r = recognize((circle(500f, 500f, 200f, wobble = 16f)))
        assertEquals(ShapeKind.ELLIPSE, r?.kind)
    }

    @Test
    fun `a rough polygon drawn as a circle is an ellipse`() {
        // Eight points is what a quick round gesture actually produces.
        val r = recognize((circle(500f, 500f, 200f, n = 8)))
        assertEquals(ShapeKind.ELLIPSE, r?.kind)
    }

    @Test
    fun `an oval keeps its proportions and is not rounded to a circle`() {
        val r = recognize((circle(500f, 500f, 200f).map { (x, y) -> x to (500f + (y - 500f) * 0.45f) }))
        assertEquals(ShapeKind.ELLIPSE, r?.kind)
        val w = (r!!.x1 - r.x0); val h = (r.y1 - r.y0)
        assert(h < w * 0.7f) { "expected a flattened oval, got ${w}x$h" }
    }

    /** A closed polygon walked corner to corner. */
    private fun shape(vs: List<Pair<Float, Float>>, per: Int = 14): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (i in vs.indices) {
            val a = vs[i]; val b = vs[(i + 1) % vs.size]
            for (k in 0 until per) {
                val f = k.toFloat() / per
                out += (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
            }
        }
        out += vs[0]
        return out
    }

    @Test
    fun `a triangle is a triangle, not a rectangle`() {
        // The reported bug. Three quarters of a triangle's outline lies on or near its own bounding
        // box, so it passed the rectangle test — the difference is the corner count, not a distance.
        val r = recognize(shape(listOf(500f to 250f, 750f to 700f, 250f to 700f)))
        assertEquals(ShapeKind.TRIANGLE, r?.kind)
        assertEquals(3, r?.vx?.size)
    }

    @Test
    fun `a leaning triangle keeps the corners it was drawn with`() {
        // A box cannot describe which triangle this is, so the corners have to survive the snap.
        val apex = 300f to 250f
        val r = recognize(shape(listOf(apex, 780f to 690f, 260f to 700f)))
        assertEquals(ShapeKind.TRIANGLE, r?.kind)
        val topmost = r!!.vx!!.indices.minByOrNull { r.vy!![it] }!!
        assert(r.vx!![topmost] < 400f) { "apex should stay left, was ${r.vx!![topmost]}" }
    }

    @Test
    fun `a rough triangle is still a triangle`() {
        val rnd = Random(5)
        val pts = shape(listOf(500f to 250f, 750f to 700f, 250f to 700f)).map { (x, y) ->
            (x + (rnd.nextFloat() - 0.5f) * 20f) to (y + (rnd.nextFloat() - 0.5f) * 20f)
        }
        assertEquals(ShapeKind.TRIANGLE, recognize(pts)?.kind)
    }

    @Test
    fun `a box is a rectangle`() {
        val r = recognize((box(200f, 200f, 700f, 600f)))
        assertEquals(ShapeKind.RECTANGLE, r?.kind)
    }

    @Test
    fun `a wobbly box is still a rectangle`() {
        val rnd = Random(3)
        val pts = box(200f, 200f, 700f, 600f).map { (x, y) ->
            (x + (rnd.nextFloat() - 0.5f) * 18f) to (y + (rnd.nextFloat() - 0.5f) * 18f)
        }
        assertEquals(ShapeKind.RECTANGLE, recognize(pts)?.kind)
    }

    @Test
    fun `a straight drag is a line`() {
        val pts = (0..20).map { (100f + it * 30f) to (300f + it * 2f) }
        assertEquals(ShapeKind.LINE, recognize(pts)?.kind)
    }

    @Test
    fun `a looping cursive stroke is not a rectangle`() {
        // The reported bug, and the case that actually reproduces it: a hand stroke that loops
        // sits near the edges of its own bounding box for most of its length, which scored 0.12
        // against a 0.14 rectangle threshold. Ordinary writing came back a box.
        val pts = (0..60).map {
            val t = 2 * PI * it / 60
            ((300 + 220 * sin(3 * t)).toFloat() to (400 + 120 * sin(2 * t)).toFloat())
        }
        assertNull(recognize(pts))
    }

    @Test
    fun `handwriting is left alone`() {
        // A closed-ish scribble that is neither: it must come back null rather than be squared off.
        val rnd = Random(11)
        val pts = (0..60).map {
            val t = 2 * PI * it / 60
            val r = 120f + 90f * sin(4 * t).toFloat() + (rnd.nextFloat() - 0.5f) * 40f
            ((500 + r * cos(t)).toFloat() to (500 + r * sin(t)).toFloat())
        }
        assertNull(recognize((pts)))
    }

    @Test
    fun `a stroke too small to be deliberate is left alone`() {
        assertNull(recognize((circle(100f, 100f, 8f))))
    }
}
