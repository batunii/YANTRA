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
private val BOLD = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
private val ITALIC = Regex("""(?<!\*)\*(?=\S)([^*]+?)(?<=\S)\*(?!\*)""")
private val CODE = Regex("""`(?=\S)([^`]+?)(?<=\S)`""")

/** Emphasis spans for [text], with the markers themselves painted in [markerColor]. */
fun markdownSpans(text: String, markerColor: Color): List<AnnotatedString.Range<SpanStyle>> {
    if (text.length < 3) return emptyList()
    val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
    // Claimed characters, so `*italic*` cannot match the inner asterisks of `**bold**` and code
    // spans win over anything that looks like emphasis inside them.
    val claimed = BooleanArray(text.length)

    fun scan(pattern: Regex, markerLength: Int, style: SpanStyle) {
        for (match in pattern.findAll(text)) {
            val range = match.range
            if (range.any { claimed[it] }) continue
            val innerStart = range.first + markerLength
            val innerEnd = range.last + 1 - markerLength
            if (innerEnd <= innerStart) continue
            range.forEach { claimed[it] = true }
            spans += AnnotatedString.Range(style, innerStart, innerEnd)
            spans += AnnotatedString.Range(SpanStyle(color = markerColor), range.first, innerStart)
            spans += AnnotatedString.Range(SpanStyle(color = markerColor), innerEnd, range.last + 1)
        }
    }

    // Longest marker first: ** before *, or the italic pattern would eat half a bold pair.
    scan(CODE, 1, SpanStyle(fontFamily = FontFamily.Monospace))
    scan(BOLD, 2, SpanStyle(fontWeight = FontWeight.Bold))
    scan(ITALIC, 1, SpanStyle(fontStyle = FontStyle.Italic))
    return spans
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
