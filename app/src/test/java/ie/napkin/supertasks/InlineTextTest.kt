package ie.napkin.supertasks

import androidx.compose.ui.graphics.Color
import ie.napkin.supertasks.ui.components.InlineStyle
import ie.napkin.supertasks.ui.components.inlineAnnotated
import ie.napkin.supertasks.ui.components.inlinePlain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Emphasis and links in one string.
 *
 * Both are rewrites of the same text, and both carry styling that has to land on the right
 * characters *after* the other one has moved them. That interaction is the only thing here worth
 * testing and the only thing neither `MarkdownTest` nor `LinksTest` can see.
 */
class InlineTextTest {

    private val style = InlineStyle(
        marker = Color(0xFF888888),
        link = Color(0xFF7A5AF8),
        brokenLink = Color(0xFF999999),
    )

    @Test
    fun `a link and emphasis on one line both collapse`() {
        val out = inlineAnnotated("**Ship** [[Call Bob|^abc]] today", style)
        assertEquals("Ship Call Bob today", out.text)
    }

    /** The link span has to follow the characters the emphasis strip moved. */
    @Test
    fun `the link is styled where it actually ended up`() {
        val text = "**Ship** [[Call Bob|^abc]] today"
        val out = inlineAnnotated(text, style, resolve = { "Call Bob" })
        val at = out.text.indexOf("Call Bob")
        val span = out.spanStyles.single { it.item.color == style.link }
        assertEquals(at, span.start)
        assertEquals(at + "Call Bob".length, span.end)
    }

    @Test
    fun `a resolved link shows the current title and takes the resolved colour`() {
        val out = inlineAnnotated("See [[Old name|^abc]]", style, resolve = { "New name" })
        assertEquals("See New name", out.text)
        val span = out.spanStyles.single { it.item.color == style.link }
        assertEquals("New name", out.text.substring(span.start, span.end))
    }

    @Test
    fun `an unresolvable link keeps its stored label and says so in colour`() {
        val out = inlineAnnotated("See [[Old name|^abc]]", style)
        assertEquals("See Old name", out.text)
        assertEquals(1, out.spanStyles.count { it.item.color == style.brokenLink })
    }

    /** A task title renders links but never emphasis — see the note in InlineText. */
    @Test
    fun `emphasis off leaves the asterisks alone and still collapses the link`() {
        val out = inlineAnnotated("**Ship** [[Call Bob|^abc]]", style, emphasis = false)
        assertEquals("**Ship** Call Bob", out.text)
    }

    @Test
    fun `two links on one line each land on their own words`() {
        val out = inlineAnnotated("[[A|^a]] then [[Bee|^b]]", style)
        assertEquals("A then Bee", out.text)
        val spans = out.spanStyles.filter { it.item.color == style.brokenLink }.sortedBy { it.start }
        assertEquals(listOf("A", "Bee"), spans.map { out.text.substring(it.start, it.end) })
    }

    @Test
    fun `plain text is what a notification would say`() {
        assertEquals("Ask Bob about it", inlinePlain("Ask [[Bob|^x]] about it"))
    }
}
