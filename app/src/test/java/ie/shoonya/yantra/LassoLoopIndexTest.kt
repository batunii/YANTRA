package ie.shoonya.yantra

import ie.shoonya.yantra.data.ink.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * **The lasso's fast inside-test gives exactly the answers the plain one does.**
 *
 * Scoring a lasso tested every sample of every stroke inside the loop against every edge of the
 * loop, on every few pixels of finger movement — 110 ms a point on a 253-stroke sketch. The loop
 * index files each edge under the horizontal bands it spans, so a point only looks at the edges in
 * its own band. That is only a speed-up if it never changes an answer, so it is checked against the
 * edge-by-edge test on loops shaped like real ones: wobbly, self-crossing, and near-degenerate.
 */
class LassoLoopIndexTest {

    private fun loop(rnd: Random, n: Int, wobble: Float): Pair<FloatArray, FloatArray> {
        val cx = rnd.nextFloat() * 1000; val cy = rnd.nextFloat() * 1400
        val rx = 20 + rnd.nextFloat() * 500; val ry = 20 + rnd.nextFloat() * 700
        val px = FloatArray(n); val py = FloatArray(n)
        for (i in 0 until n) {
            val a = 2 * Math.PI * i / n
            val k = 1 + (rnd.nextFloat() - 0.5f) * wobble
            px[i] = (cx + rx * k * cos(a)).toFloat()
            py[i] = (cy + ry * k * sin(a)).toFloat()
        }
        return px to py
    }

    @Test
    fun agreesWithTheEdgeByEdgeTestEverywhere() {
        val rnd = Random(7)
        var checked = 0
        repeat(300) { t ->
            val n = listOf(3, 4, 12, 60, 240, 700)[t % 6]
            val (px, py) = loop(rnd, n, wobble = if (t % 3 == 0) 1.6f else 0.3f)
            val index = StrokeCodec.LoopIndex(px, py)
            repeat(400) {
                // Points around and inside the loop, plus some exactly on its vertices.
                val (x, y) = if (it % 25 == 0) {
                    val v = rnd.nextInt(n); px[v] to py[v]
                } else {
                    (px.min() - 50 + rnd.nextFloat() * (px.max() - px.min() + 100)) to
                        (py.min() - 50 + rnd.nextFloat() * (py.max() - py.min() + 100))
                }
                assertEquals("loop $t point ($x, $y)", StrokeCodec.pointInPolygon(x, y, px, py), index.contains(x, y))
                checked++
            }
        }
        assertEquals(120_000, checked)
    }
}
