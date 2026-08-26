package ie.napkin.supertasks

import ie.napkin.supertasks.data.format.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as emphasis in prose.
 *
 * [Markdown.runs] answers two questions at once — which words to style, and which characters are
 * markers rather than content — and the second is the one that bites. A marker the parse fails to
 * claim is drawn at full brightness next to the words it was meant to decorate, so most of these
 * tests are really about the punctuation rather than the styling.
 */
class MarkdownTest {

    /** Every run as `kind:innerText`, which is what a styler ends up drawing. */
    private fun emphasis(text: String): List<String> =
        Markdown.runs(text).map { "${it.kind}:${text.substring(it.inner.first, it.inner.last + 1)}" }

    /** The characters this parse calls markers — everything in a run that is not its inner text. */
    private fun markers(text: String): Set<Int> = Markdown.runs(text).flatMapTo(mutableSetOf()) {
        (it.outer.first until it.inner.first) + (it.inner.last + 1..it.outer.last)
    }

    @Test
    fun `the three kinds, and the words inside them`() {
        assertEquals(listOf("BOLD:ship it"), emphasis("**ship it**"))
        assertEquals(listOf("ITALIC:ship it"), emphasis("*ship it*"))
        assertEquals(listOf("CODE:ship it"), emphasis("`ship it`"))
        assertEquals(listOf("BOLD:ship it"), emphasis("really **ship it** now"))
    }

    @Test
    fun `several runs in one line`() {
        assertEquals(
            listOf("BOLD:bold", "ITALIC:italic", "CODE:code"),
            emphasis("**bold** and *italic* and `code`"),
        )
    }

    @Test
    fun `bold wins over the italic that lives inside it`() {
        // The claim logic: `*italic*` must not match the inner asterisks of `**bold**`, or the pair
        // is split across two runs and one asterisk of each end is left over.
        assertEquals(listOf("BOLD:bold"), emphasis("**bold**"))
    }

    @Test
    fun `code wins over emphasis inside it`() {
        // Inside backticks the asterisks are content, not markers, and must be drawn — including a
        // pair that would otherwise read as bold, which is the whole reason someone writes it in a
        // code span in the first place.
        assertEquals(listOf("CODE:a *b* c"), emphasis("`a *b* c`"))
        assertEquals(listOf("CODE:**not bold**"), emphasis("`**not bold**`"))
    }

    @Test
    fun `nested emphasis is found all the way down`() {
        // The outer run alone is not enough: stopping there leaves the inner words unstyled and
        // their markers undimmed, in the middle of text that is visibly formatted around them.
        assertEquals(listOf("BOLD_ITALIC:both"), emphasis("***both***"))
        assertEquals(listOf("BOLD:a *b* c", "ITALIC:b"), emphasis("**a *b* c**"))
    }

    @Test
    fun `text with no emphasis has no runs`() {
        assertEquals(emptyList<Markdown.Run>(), Markdown.runs("buy milk tomorrow"))
    }

    @Test
    fun `lone or unmatched markers are content`() {
        // "2 * 3" is arithmetic and "**bold" is a line someone is still typing. Claiming either
        // would dim a character the writer meant literally.
        listOf("2 * 3", "**bold", "an * asterisk", "a ` backtick").forEach {
            assertEquals("claimed something in: $it", emptyList<Markdown.Run>(), Markdown.runs(it))
        }
    }

    @Test
    fun `no marker of a claimed pair is left unclaimed`() {
        // The property that matters: if the parse decides a line has emphasis, every asterisk and
        // backtick belonging to it must sit inside some run's markers. One left over is drawn at
        // full strength beside dimmed twins, which reads as a typo in the user's own text.
        listOf("**a** *b* `c`", "***both***", "**a *b* c**", "***a* b**").forEach { line ->
            val claimed = markers(line)
            val inCode = Markdown.runs(line)
                .filter { it.kind == Markdown.Kind.CODE }
                .flatMapTo(mutableSetOf()) { it.inner }
            line.forEachIndexed { i, ch ->
                if (ch == '*' || ch == '`') {
                    assertTrue("unclaimed $ch at $i in '$line'", i in claimed || i in inCode)
                }
            }
        }
    }

    @Test
    fun `a run reports both the markers and the words`() {
        val text = "really **ship it** now"
        val run = Markdown.runs(text).single()
        assertEquals(Markdown.Kind.BOLD, run.kind)
        assertEquals("**ship it**", text.substring(run.outer.first, run.outer.last + 1))
        assertEquals("ship it", text.substring(run.inner.first, run.inner.last + 1))
    }
}
