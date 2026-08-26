package ie.napkin.supertasks

import ie.napkin.supertasks.data.format.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule for what emphasis is, read two ways.
 *
 * A screen that can style keeps the markers and dims them; a widget or a notification cannot style
 * at all and needs them gone, because `**ship it**` with visible asterisks is worse than either
 * choice. Both read [Markdown.runs], so the interesting property is not any single output but that
 * the two can never disagree about where the emphasis was.
 */
class MarkdownTest {

    @Test
    fun `markers are removed and the words kept`() {
        assertEquals("ship it", Markdown.strip("**ship it**"))
        assertEquals("ship it", Markdown.strip("*ship it*"))
        assertEquals("ship it", Markdown.strip("`ship it`"))
        assertEquals("really ship it now", Markdown.strip("really **ship it** now"))
    }

    @Test
    fun `several runs in one line`() {
        assertEquals(
            "bold and italic and code",
            Markdown.strip("**bold** and *italic* and `code`"),
        )
    }

    @Test
    fun `bold wins over the italic that lives inside it`() {
        // The claim logic: `*italic*` must not match the inner asterisks of `**bold**`, or stripping
        // would leave a stray marker behind.
        assertEquals("bold", Markdown.strip("**bold**"))
        assertTrue(!Markdown.strip("**bold**").contains("*"))
    }

    @Test
    fun `code wins over emphasis inside it`() {
        // Inside backticks the asterisks are content, not markers, and must survive — including a
        // pair that would otherwise read as bold, which is the whole reason someone writes it in a
        // code span in the first place.
        assertEquals("a *b* c", Markdown.strip("`a *b* c`"))
        assertEquals("**not bold**", Markdown.strip("`**not bold**`"))
    }

    @Test
    fun `nested emphasis is stripped all the way down`() {
        // The outer run alone is not enough: stopping there leaves exactly the punctuation this is
        // meant to remove.
        assertEquals("both", Markdown.strip("***both***"))
        assertEquals("a b c", Markdown.strip("**a *b* c**"))
    }

    @Test
    fun `text with no emphasis is returned untouched`() {
        val plain = "buy milk tomorrow"
        assertTrue(plain === Markdown.strip(plain))
        assertEquals(emptyList<Markdown.Run>(), Markdown.runs(plain))
    }

    @Test
    fun `lone or unmatched markers are left alone`() {
        // A task called "2 * 3" is arithmetic, not emphasis, and a half-typed "**bold" is a title
        // someone is still writing.
        listOf("2 * 3", "**bold", "an * asterisk", "a ` backtick").forEach {
            assertEquals("changed: $it", it, Markdown.strip(it))
        }
    }

    @Test
    fun `stripping never leaves a marker behind`() {
        listOf(
            "**a** *b* `c`",
            "***both***",
            "**a *b* c**",
        ).forEach { line ->
            val out = Markdown.strip(line)
            // Whatever the parse decides, what reaches a widget must never show punctuation the
            // writer intended as formatting.
            assertTrue("markers survived in '$line' -> '$out'", !out.contains("**"))
        }
    }

    @Test
    fun `runs report where the words are, so a styler and a stripper agree`() {
        val text = "really **ship it** now"
        val run = Markdown.runs(text).single()
        assertEquals(Markdown.Kind.BOLD, run.kind)
        assertEquals("**ship it**", text.substring(run.outer.first, run.outer.last + 1))
        assertEquals("ship it", text.substring(run.inner.first, run.inner.last + 1))
    }
}
