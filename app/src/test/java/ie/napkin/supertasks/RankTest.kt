package ie.napkin.supertasks

import ie.napkin.supertasks.data.rank.Rank
import org.junit.Assert.assertTrue
import org.junit.Test

class RankTest {

    @Test
    fun `first rank is a single middle digit`() {
        val r = Rank.between(null, null)
        assertTrue(r.isNotEmpty())
        assertTrue(r == "i")
    }

    @Test
    fun `between orders strictly`() {
        val a = "i"
        val b = Rank.after(a)
        assertTrue(a < b)
        val mid = Rank.between(a, b)
        assertTrue(a < mid && mid < b)
    }

    @Test
    fun `append many stays ordered`() {
        var last: String? = null
        val ranks = mutableListOf<String>()
        repeat(200) {
            val r = Rank.after(last)
            if (last != null) assertTrue(last!! < r)
            ranks += r
            last = r
        }
        assertTrue(ranks == ranks.sorted())
    }

    @Test
    fun `prepend many stays ordered`() {
        var first: String? = null
        repeat(200) {
            val r = Rank.before(first)
            if (first != null) assertTrue(r < first!!)
            first = r
        }
    }

    @Test
    fun `repeated midpoint insertion converges without collision`() {
        var lo = Rank.between(null, null)
        var hi = Rank.after(lo)
        repeat(200) {
            val mid = Rank.between(lo, hi)
            assertTrue("$lo < $mid < $hi failed", lo < mid && mid < hi)
            // squeeze from alternating sides
            if (it % 2 == 0) lo = mid else hi = mid
        }
    }

    @Test
    fun `generated ranks never end in zero`() {
        var last: String? = null
        repeat(100) {
            val r = Rank.after(last)
            assertTrue(!r.endsWith("0"))
            last = r
        }
        var lo = "i"
        var hi = Rank.after("i")
        repeat(100) {
            val mid = Rank.between(lo, hi)
            assertTrue(!mid.endsWith("0"))
            lo = mid
        }
    }
}
