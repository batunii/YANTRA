package ie.shoonya.yantra

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.ui.components.InlineStyle
import ie.shoonya.yantra.ui.components.InlineTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A task line says which of its words stopped being the title.
 *
 * On a task line the trailing words are **fields**: `@sai` assigns, `#bug` tags, `!high`
 * prioritises, `due:` dates. `PageCodec` scans them right to left and takes them out of the title,
 * so typing one really does assign the task — and on a page it did all of that in the same white as
 * the title, with no sign anything had happened. The only way to learn that `@saieeshward` had left
 * the name was to commit the line and look at it afterwards.
 *
 * The quick-add bar has tinted what it understood for months, for exactly this reason: "buy milk
 * tomorrow" quietly losing its last word is alarming, and a word tinted as you type says what
 * became of it while it can still be edited away. Subtasks are where tasks are actually written,
 * and they were the surface that did not say.
 *
 * **The boundary is the parser's own.** [PageCodec.tokenStart] runs `parseTask` and measures the
 * title it kept, rather than re-implementing the right-to-left rule beside it. A highlight drawing
 * a different line from the format is worse than no highlight, because it is confidently wrong.
 */
class ASubtaskShowsWhatItUnderstoodTest {

    private val style = InlineStyle(
        marker = Color(0xFF888888),
        link = Color(0xFF7A5AF8),
        brokenLink = Color(0xFF999999),
        token = Color(0xFF6E6E6E),
    )

    private fun shown(text: String, isTask: Boolean = true) =
        InlineTransformation(style, emphasis = !isTask, resolve = { null }, taskTokens = isTask)
            .filter(AnnotatedString(text))

    /** The characters the token span covers, so a test reads as what a person would see. */
    private fun tinted(text: String, isTask: Boolean = true): String {
        val out = shown(text, isTask)
        val span = out.text.spanStyles.singleOrNull { it.item.color == style.token } ?: return ""
        return out.text.text.substring(span.start, span.end)
    }

    @Test fun `an assignee at the end of a subtask is marked`() {
        assertEquals("@saieeshward", tinted("review deck @saieeshward"))
    }

    @Test fun `a run of tokens is marked as one`() {
        assertEquals("@sai #bug !high", tinted("review deck @sai #bug !high"))
    }

    /**
     * The right-to-left rule, which is the whole reason this must not be re-implemented: the scan
     * stops at the first word that is not a token, so `#2` here is part of the title and nothing is
     * tinted at all.
     */
    @Test fun `a hash in the middle of a title is left alone`() {
        assertEquals("", tinted("Buy #2 pencils"))
    }

    @Test fun `a title with no tokens is untouched`() {
        assertEquals("", tinted("review the deck"))
    }

    /** Prose is prose: `@sai` in a note is the characters `@sai`. */
    @Test fun `a note is not a task line`() {
        assertEquals("", tinted("email @sai about it", isTask = false))
    }

    /**
     * With a link on the line the string the field draws is shorter than the string it holds, so
     * the span has to be mapped into the collapsed coordinates like every other span here.
     */
    @Test fun `the mark lands correctly when a link has collapsed`() {
        val out = InlineTransformation(
            style, emphasis = false, resolve = { "Call Bob" }, taskTokens = true,
        ).filter(AnnotatedString("prep for [[Call Bob|^abc]] @sai"))
        val span = out.text.spanStyles.single { it.item.color == style.token }
        assertEquals("prep for Call Bob @sai", out.text.text)
        assertEquals("@sai", out.text.text.substring(span.start, span.end))
    }

    /** And the boundary itself is whatever the parser kept, asked directly. */
    @Test fun `the boundary is the parser's own`() {
        val body = "review deck @saieeshward"
        assertEquals(body.indexOf("@saieeshward"), PageCodec.tokenStart(body))
        assertEquals("Buy #2 pencils".length, PageCodec.tokenStart("Buy #2 pencils"))
        assertTrue(PageCodec.tokenStart("") <= 0)
    }
}
