package ie.napkin.supertasks.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import ie.napkin.supertasks.data.capture.CaptureParse
import ie.napkin.supertasks.data.capture.Captured
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.label.LabelPalette
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * Tints the parts of what you are typing that the app has understood.
 *
 * This is the safety half of inline capture, not decoration. "buy milk tomorrow" quietly losing its
 * last word would be alarming; the same word tinted as you type says *this became a date, and it
 * will not be in the title* — before you commit to it, while it can still be edited away.
 *
 * **A label is tinted the colour it is about to become.** If the tag already exists it wears its own
 * colour; if it does not, it wears the one [LabelPalette.defaultFor] will give it — which is derived
 * from the name, so it is knowable before the label exists. Typing `#home` therefore shows you the
 * chip you are about to make, and typing a tag you already use shows you that you are adding to it
 * rather than starting something new. A single generic tint could not say either of those things.
 *
 * The text itself is never altered, only coloured, so [OffsetMapping.Identity] holds and the caret
 * lands exactly where it was put. A transformation that rewrote the string would have to map every
 * cursor position across the edit, and would move the caret under the user's finger.
 */
class CaptureHighlight(
    private val dateColor: Color,
    private val priorityColor: Color,
    private val listColor: Color,
    private val linkColor: Color,
    private val assigneeColor: Color,
    private val lists: List<String>,
    private val people: List<String>,
    private val links: Map<String, String>,
    private val labelColor: (String) -> Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        // Parsed with everything the parser will be given for real, or the tint would promise
        // readings the commit does not make — an `@name` glowing for somebody who cannot be
        // assigned is worse than one that never lit up.
        val parsed = CaptureParse.parse(text.text, lists = lists, people = people, links = links)
        // Nothing recognised, or everything was — a title that is only a token stays a title, and
        // tinting the whole thing would promise a modifier that was not applied.
        if (!parsed.hasAnything || parsed.spans.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val styled = AnnotatedString(
            text.text,
            parsed.spans.mapNotNull { span ->
                // Guard the bounds: the field's text and the parse are the same string, but a
                // stale recomposition briefly is not worth crashing over.
                if (span.range.last >= text.text.length) return@mapNotNull null
                val written = text.text.substring(span.range.first, span.range.last + 1)
                val colour = when (span.kind) {
                    Captured.Kind.DATE, Captured.Kind.TIME -> dateColor
                    Captured.Kind.PRIORITY -> priorityColor
                    // Structure's own ink: a list is where a task goes, not something about it.
                    Captured.Kind.LIST -> listColor
                    // The ink the link is about to be drawn in once it is committed — see
                    // InlineText. The field and the block it becomes agree on what a link looks
                    // like, which is the same promise the label tint makes.
                    Captured.Kind.LINK -> linkColor
                    // Neutral, because the chip it becomes is neutral, and because the colour law
                    // has no free hue: coral is your own effort and crimson/amber is priority.
                    // A person is structure — who a thing belongs to — and structure is neutral.
                    Captured.Kind.ASSIGNEE -> assigneeColor
                    Captured.Kind.LABEL -> labelColor(written.removePrefix("#"))
                }
                AnnotatedString.Range(
                    SpanStyle(color = colour, fontWeight = FontWeight.W700),
                    span.range.first,
                    span.range.last + 1,
                )
            },
        )
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/**
 * A highlighter that knows the labels this workspace already has.
 *
 * Resolution goes through [chipStyleFor], the same function the chips themselves use, so the tint in
 * the field and the chip it becomes cannot drift apart — including the light/dark twin, which is why
 * a stored colour is not simply used as-is.
 *
 * Matching is case-insensitive because people type tags casually, exactly as
 * `LabelRepository.getOrCreate` does when deciding whether `#Home` is a new tag or the old one.
 */
@Composable
fun rememberCaptureHighlight(
    labels: List<LabelEntity>,
    lists: List<String> = emptyList(),
    people: List<String> = emptyList(),
    links: Map<String, String> = emptyMap(),
): CaptureHighlight {
    val y = Yantra.colors
    val byName = remember(labels) { labels.associateBy { it.name.lowercase() } }

    // Every colour resolved up front: a VisualTransformation runs on every keystroke and is not a
    // composable, so it cannot read the theme or call chipStyleFor itself.
    val existing = byName.mapValues { (_, label) -> chipStyleFor(label.color?.let { Color(it) }).text }
    val neutral = chipStyleFor(null as Color?).text

    // A tag that does not exist yet still has a knowable colour, because the default is derived from
    // its name — and there are only as many answers as there are swatches, so all of them can be
    // resolved here rather than guessed at later. Resolving them the *same* way as an existing label
    // matters: chipStyleFor both swaps in the twin for this theme and adjusts the tone for chip size,
    // and doing only the first by hand is how the preview drifted from the chip.
    val bySwatch = LabelPalette.swatches.associate { it.light to chipStyleFor(Color(it.light)).text }

    return remember(
        existing, neutral, bySwatch, lists, people, links,
        y.due, y.overdue, y.accentText, y.textSecondary,
    ) {
        CaptureHighlight(
            dateColor = y.due,
            priorityColor = y.overdue,
            listColor = y.accentText,
            linkColor = y.accentText,
            assigneeColor = y.textSecondary,
            lists = lists,
            people = people,
            links = links,
            labelColor = { name ->
                // An existing tag keeps its own colour — including a deliberately neutral one, which
                // must not be overridden by the name-derived default it would have had.
                if (byName.containsKey(name.lowercase())) existing[name.lowercase()] ?: neutral
                else bySwatch[LabelPalette.defaultFor(name)] ?: neutral
            },
        )
    }
}
