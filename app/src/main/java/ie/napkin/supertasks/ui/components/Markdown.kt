package ie.napkin.supertasks.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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

/**
 * [text] with its emphasis applied and the markers taken out.
 *
 * For rows that cannot be typed into. Keeping the markers visible is a property of the *editor*,
 * where hiding them would mean mapping offsets between two strings of different lengths and where
 * caret drift is the price of getting it wrong. A row you can only read has no caret to protect, so
 * the markers have nothing left to justify them and simply read as the app failing to render.
 *
 * Only the outermost run of each nesting is unwrapped: emphasis inside emphasis keeps its inner
 * markers rather than risking an offset this function cannot verify.
 */
fun markdownStripped(text: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    Markdown.runs(text).forEach { run ->
        if (run.outer.first < at) return@forEach   // nested inside one already unwrapped
        append(text.substring(at, run.outer.first))
        val style = when (run.kind) {
            Markdown.Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
            Markdown.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            Markdown.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            Markdown.Kind.BOLD_ITALIC ->
                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        }
        withStyle(style) { append(text.substring(run.inner.first, run.inner.last + 1)) }
        at = run.outer.last + 1
    }
    append(text.substring(at))
}

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
