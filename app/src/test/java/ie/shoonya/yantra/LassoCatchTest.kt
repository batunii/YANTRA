package ie.shoonya.yantra

import ie.shoonya.yantra.data.ink.StrokePath
import ie.shoonya.yantra.data.ink.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a loop catches, and why a small one is allowed to mean something.
 *
 * The old rule was a majority of a stroke's *sample points* inside the loop, which made the loop's
 * required size a property of the stroke rather than of the region being pointed at. Handwriting is
 * long strokes — a word is often one — so selecting a word meant encircling the word, and a loop
 * that big had already caught its neighbours, which then won their own majorities too. The loop grew
 * and the catch grew with it. See INK_CANVAS_PLAN.md §C.
 *
 * Each test here is one row of the table in that plan.
 */
class LassoCatchTest {

    /** A stroke as the points it passes through — no ink library, so this runs on the JVM. */
    private fun stroke(id: String, vararg xy: Float): StrokePath = StrokePath(
        id,
        FloatArray(xy.size / 2) { xy[it * 2] },
        FloatArray(xy.size / 2) { xy[it * 2 + 1] },
    )

    /** A closed rectangle as a lasso polygon. */
    private fun loop(l: Float, t: Float, r: Float, b: Float): Pair<FloatArray, FloatArray> =
        floatArrayOf(l, r, r, l) to floatArrayOf(t, t, b, b)

    private fun catch(
        candidates: List<StrokePath>,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ): List<String> {
        val (px, py) = loop(l, t, r, b)
        return StrokeCodec.lassoCatchPaths(candidates, px, py)
    }

    @Test
    fun `a loop around two whole strokes takes both`() {
        val a = stroke("a", 100f, 100f, 140f, 100f)
        val b = stroke("b", 100f, 130f, 140f, 130f)
        val got = catch(listOf(a, b), 80f, 80f, 160f, 150f)
        assertEquals(setOf("a", "b"), got.toSet())
    }

    @Test
    fun `a stroke merely passing through is left behind`() {
        val a = stroke("a", 100f, 100f, 140f, 100f)
        val b = stroke("b", 100f, 130f, 140f, 130f)
        // A long stroke crossing the loop, most of which is far outside it.
        val passing = stroke("passing", 0f, 115f, 900f, 115f)
        val got = catch(listOf(a, b, passing), 80f, 80f, 160f, 150f)
        assertEquals(setOf("a", "b"), got.toSet())
    }

    @Test
    fun `a small loop on part of one long stroke takes that stroke`() {
        // The case the old rule could not do at any loop size: a word-length stroke, and a loop
        // over a piece of it. A majority of it is outside, and it should still be the catch.
        val word = stroke("word", 0f, 200f, 100f, 200f, 200f, 200f, 300f, 200f, 400f, 200f)
        val neighbour = stroke("neighbour", 0f, 260f, 400f, 260f)
        val got = catch(listOf(word, neighbour), 90f, 180f, 210f, 220f)
        assertEquals(listOf("word"), got)
    }

    @Test
    fun `a loop the size of a fingertip catches what it sits on`() {
        // A tap needs no special case: it is the degenerate loop, and relative scoring gets it right
        // because the thing under it is the best-contained thing there is.
        val a = stroke("a", 0f, 100f, 400f, 100f)
        val b = stroke("b", 0f, 300f, 400f, 300f)
        val got = catch(listOf(a, b), 195f, 92f, 215f, 108f)
        assertEquals(listOf("a"), got)
    }

    @Test
    fun `a loop on blank paper catches nothing`() {
        val a = stroke("a", 0f, 100f, 400f, 100f)
        assertTrue(catch(listOf(a), 600f, 600f, 700f, 700f).isEmpty())
    }

    @Test
    fun `the topmost of two equally caught strokes comes first`() {
        // Draw order is z-order, so the last one is the one you can see and the one you meant.
        val under = stroke("under", 100f, 100f, 200f, 100f)
        val over = stroke("over", 100f, 101f, 200f, 101f)
        val got = catch(listOf(under, over), 80f, 80f, 220f, 120f)
        assertEquals("over", got.first())
        assertEquals(setOf("under", "over"), got.toSet())
    }

    @Test
    fun `scoring does not depend on how fast the stroke was drawn`() {
        // The bias that made the old rule unpredictable. Both strokes are the same line; one has its
        // samples bunched into the left half, as a pen that slowed down there would leave them.
        // Counting points, the loop over the left half catches the dense one and misses the sparse
        // one. Measuring length, they score alike.
        val dense = stroke("dense", 
            0f, 500f, 10f, 500f, 20f, 500f, 30f, 500f, 40f, 500f, 50f, 500f,
            60f, 500f, 70f, 500f, 80f, 500f, 90f, 500f, 100f, 500f, 200f, 500f,
        )
        val sparse = stroke("sparse", 0f, 500f, 100f, 500f, 200f, 500f)
        val (px, py) = loop(-10f, 480f, 100f, 520f)
        val d = StrokeCodec.containedFraction(dense, px, py)
        val s = StrokeCodec.containedFraction(sparse, px, py)
        assertEquals("same ink, same answer", d, s, 0.05f)
        assertEquals("half the line is inside", 0.5f, d, 0.05f)
    }

    @Test
    fun `a segment crossing the loop between samples is still seen`() {
        // Two samples 400du apart with the loop entirely between them. Counting points, this stroke
        // does not touch the loop at all; the loop visibly crosses the ink.
        val fast = stroke("fast", 0f, 500f, 400f, 500f)
        val (px, py) = loop(190f, 480f, 210f, 520f)
        assertTrue(
            "the crossing should register",
            StrokeCodec.containedFraction(fast, px, py) > 0f,
        )
    }
}
