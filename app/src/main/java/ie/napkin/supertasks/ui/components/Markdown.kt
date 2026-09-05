package ie.napkin.supertasks.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import ie.napkin.supertasks.data.format.Markdown

/**
 * Where emphasis gets its colours.
 *
 * Nothing about the database changes: a block's title has always been an arbitrary string, so
 * `**bold**` simply lives in it. That is also the better store — the markers stay greppable, they
 * survive export and sync, and they are what you typed.
 *
 * Where the emphasis *is* comes from [Markdown], so a widget stripping markers and a screen dimming
 * them can never disagree about what counts as bold. Only the styling is decided here, and only for
 * the case where the markers stay put and are merely dimmed — see `InlineText` for when they do not.
 *
 * Emphasis spans for [text], with the markers themselves painted in [markerColor].
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

/*
 * `markdownAnnotated`, `markdownStripped` and `MarkdownEmphasis` used to live here — one renderer
 * for read-only rows, one for stripping the markers, one VisualTransformation for the editor. All
 * three are gone, folded into `InlineText`, and the reason is not tidiness.
 *
 * Links arrived and had to be rendered by the same passes: both are rewrites of the same string,
 * and a span computed before the other rewrite has moved the characters lands in the wrong place.
 * Keeping a second set of functions that knew about emphasis and not about links would have meant
 * two renderers disagreeing about what one block says, decided by which composable a row happened
 * to call — which is the same shape as the bug where two producers minted two ids for one label
 * name and took the app down on launch. One place decides how a block reads.
 */
