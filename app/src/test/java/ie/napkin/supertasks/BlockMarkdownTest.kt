package ie.napkin.supertasks

import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.node.BlockMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a marker typed at the start of a line means.
 *
 * Reported as: inline emphasis converted but `## `, `* ` and `1. ` did not — the characters simply
 * stayed in the text. Emphasis is a visual transformation and changes no data, so the two failing
 * halves of the feature share nothing; this pins the half that is decidable without a keyboard.
 */
class BlockMarkdownTest {

    private fun become(text: String, from: String = NodeType.PARAGRAPH) =
        BlockMarkdown.match(text, from)?.let { "${it.type}:${it.rest}" }

    @Test
    fun `every block marker names its kind and is eaten`() {
        assertEquals("${NodeType.HEADING}:", become("# "))
        assertEquals("${NodeType.HEADING}:", become("## "))
        assertEquals("${NodeType.HEADING}:", become("### "))
        assertEquals("${NodeType.BULLET}:", become("- "))
        assertEquals("${NodeType.BULLET}:", become("* "))
        assertEquals("${NodeType.BULLET}:", become("+ "))
        assertEquals("${NodeType.NUMBERED}:", become("1. "))
        assertEquals("${NodeType.NUMBERED}:", become("12) "))
        assertEquals("${NodeType.TASK}:", become("[] "))
        assertEquals("${NodeType.TASK}:", become("[x] "))
    }

    @Test
    fun `a deeper heading is not read as a shallower one plus a hash`() {
        // "## " matched by the "# " rule would leave the second hash sitting in the text.
        assertEquals("${NodeType.HEADING}:", become("## "))
        assertEquals("${NodeType.HEADING}:", become("#### "))
    }

    @Test
    fun `whatever follows the marker survives it`() {
        assertEquals("${NodeType.HEADING}:Today", become("## Today"))
        assertEquals("${NodeType.BULLET}:milk", become("- milk"))
    }

    @Test
    fun `a marker only counts at the start of the line`() {
        assertNull(become("see item 1. here"))
        assertNull(become("a - b"))
        assertNull(become("nothing here"))
    }

    @Test
    fun `typing the marker a block already wears is just typing`() {
        assertNull(become("- ", from = NodeType.BULLET))
        assertNull(become("# ", from = NodeType.HEADING))
        assertNull(become("## ", from = NodeType.HEADING))
        assertNull(become("1. ", from = NodeType.NUMBERED))
    }

    @Test
    fun `a bullet can still become something else`() {
        assertEquals("${NodeType.NUMBERED}:", become("1. ", from = NodeType.BULLET))
        assertEquals("${NodeType.HEADING}:", become("# ", from = NodeType.BULLET))
    }
}
