package ie.napkin.supertasks.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import ie.napkin.supertasks.data.capture.CaptureParse
import ie.napkin.supertasks.data.capture.Captured

/**
 * Tints the parts of what you are typing that the app has understood.
 *
 * This is the safety half of inline capture, not decoration. "buy milk tomorrow" quietly losing its
 * last word would be alarming; the same word tinted as you type says *this became a date, and it
 * will not be in the title* — before you commit to it, while it can still be edited away.
 *
 * The text itself is never altered, only coloured, so [OffsetMapping.Identity] holds and the caret
 * lands exactly where it was put. A transformation that rewrote the string would have to map every
 * cursor position across the edit, and would move the caret under the user's finger.
 */
class CaptureHighlight(
    private val dateColor: Color,
    private val labelColor: Color,
    private val priorityColor: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val parsed = CaptureParse.parse(text.text)
        // Nothing recognised, or everything was — a title that is only a token stays a title, and
        // tinting the whole thing would promise a modifier that was not applied.
        if (!parsed.hasAnything || parsed.spans.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val styled = AnnotatedString(
            text.text,
            parsed.spans.mapNotNull { span ->
                val colour = when (span.kind) {
                    Captured.Kind.DATE, Captured.Kind.TIME -> dateColor
                    Captured.Kind.LABEL -> labelColor
                    Captured.Kind.PRIORITY -> priorityColor
                }
                // Guard the bounds: the field's text and the parse are the same string, but a
                // stale recomposition briefly is not worth crashing over.
                if (span.range.last >= text.text.length) null
                else AnnotatedString.Range(
                    SpanStyle(color = colour, fontWeight = FontWeight.W700),
                    span.range.first,
                    span.range.last + 1,
                )
            },
        )
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
