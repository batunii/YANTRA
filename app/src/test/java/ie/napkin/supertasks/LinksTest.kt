package ie.napkin.supertasks

import ie.napkin.supertasks.data.format.Links
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `[[Call Bob|^9f1e]]` means, and what it must never mean.
 *
 * The offset tests are the ones that matter. Everything else here is a grammar that either matches
 * or does not; the tables are what a caret sits on.
 */
class LinksTest {

    private val id = "9f1e2c3d-4a5b-6c7d-8e9f-0a1b2c3d4e5f"

    @Test
    fun `a link is its label and its target`() {
        val found = Links.links("See [[Call Bob|^$id]] before Friday")
        assertEquals(1, found.size)
        assertEquals("Call Bob", found[0].label)
        assertEquals(id, found[0].targetId)
        assertEquals("[[Call Bob|^$id]]", "See [[Call Bob|^$id]] before Friday".substring(found[0].range))
    }

    @Test
    fun `two links on one line stay two links`() {
        val text = "[[A|^a]] and [[B|^b]]"
        assertEquals(listOf("a", "b"), Links.targets(text))
    }

    /**
     * The whole reason the id is mandatory. A bare name would have to be resolved by title, titles
     * are not unique, and a link that silently points at the wrong "Call Bob" is worse than text.
     */
    @Test
    fun `a bracketed name with no id is not a link`() {
        assertTrue(Links.links("[[Call Bob]]").isEmpty())
        assertTrue(Links.links("[[Call Bob|9f1e]]").isEmpty())   // no caret
        assertEquals("[[Call Bob]]", Links.plain("[[Call Bob]]"))
    }

    /** An unterminated `[[` must not eat the rest of the paragraph looking for a close. */
    @Test
    fun `an unclosed bracket claims nothing`() {
        assertTrue(Links.links("[[Call Bob|^$id and then some").isEmpty())
    }

    @Test
    fun `encoding replaces the characters the syntax spends`() {
        assertEquals("[[a b c|^x]]", Links.encode("a|b]c", "x"))
        assertEquals("[[Untitled|^x]]", Links.encode("   ", "x"))
        // And round-trips.
        val back = Links.links(Links.encode("Call Bob", id)).single()
        assertEquals("Call Bob", back.label)
        assertEquals(id, back.targetId)
    }

    @Test
    fun `plain text is the label when nothing resolves`() {
        assertEquals("See Call Bob now", Links.plain("See [[Call Bob|^$id]] now"))
    }

    /** A rename shows through without anything having rewritten the file. */
    @Test
    fun `a resolved title wins over the stored label`() {
        val out = Links.plain("See [[Call Bob|^$id]] now") { if (it == id) "Ring Bob" else null }
        assertEquals("See Ring Bob now", out)
    }

    @Test
    fun `collapse reports where each link landed`() {
        val text = "See [[Call Bob|^$id]] now"
        val c = Links.collapse(text)
        assertEquals("See Call Bob now", c.text)
        val shown = c.shown.single()
        assertEquals("Call Bob", c.text.substring(shown.range))
        assertEquals(id, shown.targetId)
    }

    // ---- the offset table ----

    @Test
    fun `the offset table never goes backwards and never leaves the string`() {
        val text = "a [[One|^x]] b [[Two|^yy]] c"
        val c = Links.collapse(text)
        for (i in 0 until c.map.size - 1) {
            assertTrue("map must not decrease at $i", c.map[i] <= c.map[i + 1])
        }
        assertEquals(0, c.map.first())
        assertEquals(c.text.length, c.map.last())
        c.map.forEach { assertTrue(it in 0..c.text.length) }
    }

    /**
     * The caret at the end of the line has to be at the end of the *link*, not the end of its
     * label — the two are the same pixel and forty characters apart in the file.
     */
    @Test
    fun `the position past a collapsed link is past the whole link`() {
        val text = "See [[Call Bob|^$id]]"
        val c = Links.collapse(text)
        assertEquals("See Call Bob", c.text)
        assertEquals(text.length, c.back[c.text.length])
    }

    @Test
    fun `the position before a collapsed link is before its brackets`() {
        val text = "See [[Call Bob|^$id]] now"
        val c = Links.collapse(text)
        val at = c.text.indexOf("Call Bob")
        assertEquals(text.indexOf("[["), c.back[at])
    }

    @Test
    fun `walking back never leaves the original string`() {
        val text = "a [[One|^x]] b [[Two|^yy]] c"
        val c = Links.collapse(text)
        assertEquals(c.text.length + 1, c.back.size)
        c.back.forEach { assertTrue(it in 0..text.length) }
        for (i in 0 until c.back.size - 1) {
            assertTrue("back must not decrease at $i", c.back[i] <= c.back[i + 1])
        }
    }

    @Test
    fun `every offset round-trips back inside its own link`() {
        val text = "See [[Call Bob|^$id]] now"
        val c = Links.collapse(text)
        val inverse = Links.inverse(c.map)
        assertEquals(c.text.length + 1, inverse.size)
        for (t in inverse.indices) {
            val original = inverse[t]
            assertTrue("inverse[$t] = $original out of range", original in 0..text.length)
            // Mapping forward again cannot overshoot: the inverse of an offset is the first place
            // that maps to it, so going back out must land on exactly that offset.
            assertEquals("round trip at $t", t, c.map[original])
        }
    }

    @Test
    fun `text outside a link keeps its own offsets`() {
        val text = "abc [[X|^q]] def"
        val c = Links.collapse(text)
        assertEquals("abc X def", c.text)
        // The prefix is untouched.
        for (i in 0..3) assertEquals(i, c.map[i])
        // And the suffix has shifted by exactly what the link removed.
        val removed = "[[X|^q]]".length - "X".length
        assertEquals(text.length - removed, c.map[text.length])
    }

    @Test
    fun `a resolved title of a different length still maps cleanly`() {
        val text = "x [[Short|^q]] y"
        val c = Links.collapse(text) { "A Much Longer Title" }
        assertEquals("x A Much Longer Title y", c.text)
        assertEquals(c.text.length, c.map.last())
        val shown = c.shown.single()
        assertEquals("A Much Longer Title", c.text.substring(shown.range))
        assertTrue(shown.resolved)
    }

    @Test
    fun `text with no link is left exactly alone`() {
        val text = "nothing to see here"
        val c = Links.collapse(text)
        assertEquals(text, c.text)
        for (i in 0..text.length) assertEquals(i, c.map[i])
    }

    // ---- the picker's draft ----

    @Test
    fun `a draft is the unclosed bracket the caret is inside`() {
        val text = "see [[cal"
        val (range, typed) = Links.draft(text, text.length)!!
        assertEquals("cal", typed)
        assertEquals("[[cal", text.substring(range))
    }

    @Test
    fun `a finished link is not a draft`() {
        val text = "see [[Call Bob|^$id]] now"
        assertNull(Links.draft(text, text.length))
    }

    @Test
    fun `the caret has to be past the bracket`() {
        assertNull(Links.draft("see [[", 5))
        assertEquals("", Links.draft("see [[", 6)!!.second)
    }

    @Test
    fun `a draft ends at a line break`() {
        assertNull(Links.draft("see [[cal\nmore", 14))
    }
}
