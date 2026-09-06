package ie.napkin.supertasks

import ie.napkin.supertasks.data.format.Markdown
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reduction the surfaces outside the app rely on.
 *
 * `inlinePlain` was documented as "the same reduction with no styling at all" and performed only
 * the link half of it, so a notification, a widget and the running player each printed the
 * asterisks the renderer would have consumed. These pin the half that was missing — and pin it to
 * the *renderer's* rules, because the failure worth preventing is not "markers survive", it is the
 * two halves of the app disagreeing about what a task is called.
 */
class MarkdownPlainTest {

    @Test
    fun `text with no emphasis is returned untouched`() {
        assertEquals("Buy milk", Markdown.plain("Buy milk"))
        assertEquals("", Markdown.plain(""))
        assertEquals("2 * 3 = 6", Markdown.plain("2 * 3 = 6"))
    }

    @Test
    fun `each kind loses its markers and keeps its text`() {
        assertEquals("urgent", Markdown.plain("*urgent*"))
        assertEquals("urgent", Markdown.plain("**urgent**"))
        assertEquals("urgent", Markdown.plain("***urgent***"))
        assertEquals("urgent", Markdown.plain("`urgent`"))
    }

    @Test
    fun `markers go and the surrounding words stay put`() {
        assertEquals("Ship the beta today", Markdown.plain("Ship the **beta** today"))
        assertEquals("a b c", Markdown.plain("a *b* c"))
    }

    /**
     * The renderer unwraps only the outermost run of a nesting, and this has to agree with it. A
     * stripper that went one level deeper would render `**a *b* c**` as `a b c` on the widget while
     * the page showed `a *b* c`, which is the disagreement this whole function exists to close.
     */
    @Test
    fun `nesting is unwrapped exactly as far as the renderer unwraps it`() {
        assertEquals("a *b* c", Markdown.plain("**a *b* c**"))
    }

    /** Inside backticks an asterisk is content. Code wins, as it does everywhere else. */
    @Test
    fun `code spans keep what is inside them`() {
        assertEquals("2 * 3", Markdown.plain("`2 * 3`"))
    }

    @Test
    fun `an unclosed marker is not a marker`() {
        assertEquals("does *it", Markdown.plain("does *it"))
        assertEquals("50% * ", Markdown.plain("50% * "))
    }

    @Test
    fun `several runs in one title all go`() {
        assertEquals("red and green", Markdown.plain("*red* and **green**"))
    }
}
