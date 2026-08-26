package ie.napkin.supertasks.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import ie.napkin.supertasks.data.format.Markdown

/**
 * Inline emphasis, rendered from the markers that are stored in the text itself.
 *
 * Nothing about the database changes: a block's title has always been an arbitrary string, so
 * `**bold**` simply lives in it. That is also the better store — the markers stay greppable, they
 * survive export and sync, and they are what you typed.
 *
 * The markers stay *visible*, dimmed rather than hidden, and that is the whole reason this is safe:
 * the styled string is exactly as long as the stored one, so [OffsetMapping.Identity] is correct by
 * construction. Hiding the markers would mean hand-writing an offset mapping between two strings of
 * different lengths, which is where caret drift, wrong-place selection and backspace-eats-the-wrong
 * character all come from.
 */
/**
 * Emphasis spans for [text], with the markers themselves painted in [markerColor].
 *
 * Where the emphasis *is* comes from [Markdown], so a widget stripping markers and a screen dimming
 * them can never disagree about what counts as bold. Only the styling is decided here.
 */
fun markdownSpans(text: String, markerColor: Color): List<AnnotatedString.Range<SpanStyle>> =
    Markdown.runs(text).flatMap { run ->
        val style = when (run.kind) {
            Markdown.Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
            Markdown.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            Markdown.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            Markdown.Kind.BOLD_ITALIC ->
                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        }
        listOf(
            AnnotatedString.Range(style, run.inner.first, run.inner.last + 1),
            AnnotatedString.Range(SpanStyle(color = markerColor), run.outer.first, run.inner.first),
            AnnotatedString.Range(SpanStyle(color = markerColor), run.inner.last + 1, run.outer.last + 1),
        )
    }

/** [text] with its emphasis applied — for read-only rows, which render a plain string. */
fun markdownAnnotated(text: String, markerColor: Color): AnnotatedString =
    AnnotatedString(text, markdownSpans(text, markerColor))

/** The same emphasis, applied inside an editable field. */
class MarkdownEmphasis(private val markerColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            AnnotatedString(text.text, markdownSpans(text.text, markerColor)),
            OffsetMapping.Identity,
        )

    override fun equals(other: Any?) = other is MarkdownEmphasis && other.markerColor == markerColor
    override fun hashCode() = markerColor.hashCode()
}
