package ie.napkin.supertasks.ui.components

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import ie.napkin.supertasks.data.format.Links
import ie.napkin.supertasks.data.format.Markdown

/**
 * How a block's stored string becomes something to look at.
 *
 * Two constructs live in that string — emphasis (`**bold**`) and links (`[[Call Bob|^9f1e…]]`) —
 * and they are rendered by one file because they are answered by one question: *is there a caret in
 * this text right now?*
 *
 * ## The rule, and where it comes from
 *
 * [markdownSpans] settled this for emphasis: markers stay visible and are merely dimmed, so the
 * styled string is exactly as long as the stored one and [OffsetMapping.Identity] is correct by
 * construction. Hiding them would mean hand-writing a mapping between two strings of different
 * lengths, which is where caret drift and backspace-eats-the-wrong-character come from.
 *
 * A link cannot take that deal. Its markers are a UUID, and a paragraph reading
 * `See [[Call Bob|^9f1e2c3d-4a5b-6c7d-8e9f-0a1b2c3d4e5f]] before Friday` is not a document anyone
 * would keep writing in. **A link always collapses**, in a read-only row and under a live caret
 * alike, so what is on screen is a name and the id is never seen.
 *
 * That is only safe because of what a link *is* here, and three things have to hold together:
 *
 *  - **The offset table is exact.** [Links.collapse] reports where every original offset landed and
 *    [Links.inverse] walks it back; both directions are tested over every offset of a string.
 *    Nothing is approximated, so the caret is where the arithmetic says it is.
 *  - **A tap on a link follows it** ([followLinks]) rather than taking the caret. So the ordinary
 *    way of reaching the middle of a word cannot reach the middle of a link, and the offsets a
 *    caret can hold are the ones outside it, where the mapping is the identity plus a constant.
 *  - **Backspace over a link removes the whole link.** A collapsed run cannot be dismantled one
 *    invisible character at a time, which is the failure this design would otherwise have: five
 *    presses that change nothing on screen and then a broken `[[Call Bob|^9f1e` left in the file.
 *
 * The one way in that remains is an arrow key walking into a collapsed run, where the drawn caret
 * stops moving for as many presses as the hidden text is long. It is a real edge and it is left
 * alone: it needs a keyboard, and the alternative — showing the UUID to everyone, always — is worse
 * for every other case.
 *
 * ## Task titles
 *
 * A title is deliberately literal — it appears in widgets and notifications that cannot style
 * anything, so emphasis in one is dropped or shown raw and therefore means two different things in
 * two places. A link is the one exception, and it earns it by having a defined plain-text reading:
 * [Links.plain] reduces it to the words, everywhere, deterministically. Those surfaces show "Call
 * Bob", not brackets, and not nothing.
 */

/**
 * What a `[[…|^id]]` on this screen points at, by id, or null when nothing here knows.
 *
 * A composition local because the answer belongs to the *screen* — the node page has resolved every
 * id its blocks mention and the shared row composable has not — while the question is asked deep
 * inside a text field's transformation. Threading it down would mean a parameter on every row
 * composable between the two, most of which have no idea what a link is.
 *
 * The default resolves nothing, which is the correct behaviour and not a degraded one: a link then
 * renders the label stored in the file, which is exactly what that label is for.
 */
val LocalLinkResolver = compositionLocalOf<(String) -> String?> { { null } }

/**
 * What tapping a link does, or null where tapping it should do nothing.
 *
 * Navigation is a property of the screen too — a row composable is shared between the node page and
 * a smart list and has no navigator of its own — and it is separate from [LocalLinkResolver]
 * because the two are not the same permission. A row can know what a link is called without being
 * somewhere it makes sense to travel from.
 */
val LocalLinkOpener = compositionLocalOf<((String) -> Unit)?> { null }

/** Where the styling decisions live, so a row does not have to know the theme's field names. */
data class InlineStyle(
    /** Emphasis markers and the bracket furniture of a raw link. */
    val marker: Color,
    /** A link that resolves to something. */
    val link: Color,
    /** A link whose target is gone, or in a workspace this device has not added. */
    val brokenLink: Color,
)

/**
 * [text] rendered for reading: links collapsed to titles and clickable, emphasis applied and its
 * markers taken out.
 *
 * [resolve] answers "what is this id called now". Returning null falls back to the label stored in
 * the file, drawn in [InlineStyle.brokenLink] — the link still says what it meant, it just cannot
 * prove it. That is the honest rendering of a task in a repository you have not cloned, which is a
 * normal state and not an error.
 *
 * [onOpen] is null on surfaces where following a link makes no sense; the text then still collapses
 * and still tints, it just does not respond.
 */
fun inlineAnnotated(
    text: String,
    style: InlineStyle,
    resolve: (String) -> String? = { null },
    emphasis: Boolean = true,
    onOpen: ((String) -> Unit)? = null,
): AnnotatedString {
    val collapsed = Links.collapse(text, resolve)
    val body = collapsed.text
    val stripped = if (emphasis) stripEmphasis(body) else Pair(AnnotatedString(body), IntArray(body.length + 1) { it })
    val (base, map) = stripped

    return buildAnnotatedString {
        append(base)
        collapsed.shown.forEach { link ->
            val from = map[link.range.first]
            val to = map[link.range.last + 1]
            if (to <= from) return@forEach
            val colour = if (link.resolved) style.link else style.brokenLink
            addStyle(SpanStyle(color = colour, fontWeight = FontWeight.W600), from, to)
            if (onOpen != null) {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = link.targetId,
                        styles = TextLinkStyles(SpanStyle(color = colour, fontWeight = FontWeight.W600)),
                    ) { onOpen(link.targetId) },
                    from,
                    to,
                )
            } else if (!link.resolved) {
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline), from, to)
            }
        }
    }
}

/**
 * The same reduction with no styling at all, for a widget, a notification or the archive.
 *
 * Deliberately a plain [String] and deliberately in the data layer's terms — see [Links.plain].
 * A surface that cannot render must still be able to say what the task is called.
 */
fun inlinePlain(text: String, resolve: (String) -> String? = { null }): String =
    Links.plain(text, resolve)

/**
 * [text] with its emphasis applied and its markers removed, plus where each original offset landed.
 *
 * The offset table is the reason this is not just "delete the marker ranges": link spans are
 * computed against the collapsed string, and stripping markers moves them again. Two rewrites in a
 * row need one set of books.
 *
 * Only the outermost run of each nesting is unwrapped: emphasis inside emphasis keeps its inner
 * markers rather than risking an offset this cannot verify.
 */
private fun stripEmphasis(text: String): Pair<AnnotatedString, IntArray> {
    val runs = Markdown.runs(text).filter { true }
    val map = IntArray(text.length + 1)
    val out = StringBuilder(text.length)
    val styles = ArrayList<Triple<SpanStyle, Int, Int>>()

    var at = 0
    var lastEnd = -1
    runs.forEach { run ->
        if (run.outer.first < lastEnd) return@forEach   // nested inside one already unwrapped
        while (at < run.outer.first) { map[at] = out.length; out.append(text[at]); at++ }
        // The opening markers vanish: every offset they occupied lands where the inner text starts.
        while (at < run.inner.first) { map[at] = out.length; at++ }
        val from = out.length
        while (at <= run.inner.last) { map[at] = out.length; out.append(text[at]); at++ }
        styles += Triple(styleFor(run.kind), from, out.length)
        while (at <= run.outer.last) { map[at] = out.length; at++ }
        lastEnd = run.outer.last + 1
    }
    while (at < text.length) { map[at] = out.length; out.append(text[at]); at++ }
    map[text.length] = out.length

    val body = out.toString()
    return AnnotatedString(
        body,
        styles.map { (s, a, b) -> AnnotatedString.Range(s, a, b) },
    ) to map
}

private fun styleFor(kind: Markdown.Kind): SpanStyle = when (kind) {
    Markdown.Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
    Markdown.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    Markdown.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    Markdown.Kind.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
}

/**
 * What an editable field shows: emphasis dimmed in place, links collapsed to the name they carry.
 *
 * See the note at the top of this file for why a link collapses even under a caret when an
 * asterisk does not.
 */
class InlineTransformation(
    private val style: InlineStyle,
    private val emphasis: Boolean,
    private val resolve: (String) -> String?,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText = collapsed(text.text)

    /**
     * No links, so nothing changes length: emphasis markers stay put and are merely dimmed, and the
     * mapping is the identity — the property the rest of this app's inline rendering is built on.
     */
    private fun plain(source: String): TransformedText {
        val spans = if (emphasis) markdownSpans(source, style.marker) else emptyList()
        return TransformedText(AnnotatedString(source, spans), OffsetMapping.Identity)
    }

    private fun collapsed(source: String): TransformedText {
        if (!Links.hasLink(source)) return plain(source)
        val c = Links.collapse(source, resolve)
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        if (emphasis) spans += markdownSpans(c.text, style.marker)
        c.shown.forEach { link ->
            if (link.range.isEmpty()) return@forEach
            spans += AnnotatedString.Range(
                SpanStyle(
                    color = if (link.resolved) style.link else style.brokenLink,
                    fontWeight = FontWeight.W600,
                ),
                link.range.first,
                link.range.last + 1,
            )
        }
        return TransformedText(AnnotatedString(c.text, spans), CollapseMapping(c.map, c.back))
    }

    /**
     * The resolver is part of the identity, and leaving it out was a real bug.
     *
     * A text field re-runs its transformation when the transformation changes, so a resolver that
     * learned the titles a frame later while comparing equal to the one that did not meant every
     * link on a freshly-opened page stayed drawn as unresolvable. Comparing lambdas by identity is
     * exactly right here: the caller holds it in a `remember` keyed on the title map, so it changes
     * when — and only when — the answers do.
     */
    override fun equals(other: Any?): Boolean =
        other is InlineTransformation && other.style == style && other.emphasis == emphasis &&
            other.resolve === resolve

    override fun hashCode(): Int =
        (style.hashCode() * 31 + emphasis.hashCode()) * 31 + System.identityHashCode(resolve)
}

/**
 * The offset table [Links.collapse] produced, in the shape Compose asks for.
 *
 * Both directions are looked up rather than computed, and both clamp: Compose treats an offset
 * outside the corresponding string as a crash, not as a rounding error, and the two strings change
 * length on different frames while a field is being recomposed.
 */
private class CollapseMapping(
    private val forward: IntArray,
    private val backward: IntArray,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int =
        forward[offset.coerceIn(0, forward.size - 1)]

    override fun transformedToOriginal(offset: Int): Int =
        backward[offset.coerceIn(0, backward.size - 1)]
}

// ---- following a link from inside a text field ----------------------------------------------

/**
 * Makes a tap that lands on a collapsed link follow it, instead of putting the caret there.
 *
 * A read-only row gets this for free — an [AnnotatedString] carries a [LinkAnnotation] and Compose
 * handles the rest. `BasicTextField` does not: link annotations in a [VisualTransformation] are
 * drawn and never clicked, because inside a field every tap already means "put the caret here".
 * So the hit test is done by hand, against the *transformed* layout the field actually drew.
 *
 * It runs in [PointerEventPass.Initial], which is the whole trick: the initial pass walks parent to
 * child, so this sees the touch before the field's own gesture detector and can take it away. The
 * down is consumed only once the position is known to be on a link, so every other tap on the line
 * reaches the field untouched and behaves exactly as it did before.
 *
 * A gesture that turns into a drag is let go rather than navigated — you can still start a scroll
 * with your finger on a link, it just will not open anything on the way past.
 *
 * [spans] and [layout] are read through lambdas on purpose: both change on every keystroke, and
 * capturing them by value would pin the gesture block to whatever the line said when it was first
 * composed. That is the same stale-closure trap the swipe-to-start gesture documents.
 */
fun Modifier.followLinks(
    key: Any?,
    layout: () -> TextLayoutResult?,
    spans: () -> List<Links.Shown>,
    onOpen: ((String) -> Unit)?,
): Modifier {
    if (onOpen == null) return this
    return this.pointerInput(key, onOpen) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val hit = linkAt(layout(), spans(), down.position) ?: return@awaitEachGesture
            down.consume()
            // Long-press belongs to the row (it is how a block earns its handles), so a touch held
            // past that threshold is not this gesture's to answer.
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(PointerEventPass.Initial)
            }
            if (up != null) {
                up.consume()
                onOpen(hit)
            }
        }
    }
}

/** Which link, if any, sits under [position] in the drawn text. */
private fun linkAt(
    layout: TextLayoutResult?,
    spans: List<Links.Shown>,
    position: Offset,
): String? {
    if (layout == null || spans.isEmpty()) return null
    // Outside the glyphs entirely — the padding around a short line is the row's, not a link's.
    if (position.y < 0f || position.y > layout.size.height) return null
    val line = layout.getLineForVerticalPosition(position.y)
    if (position.x < 0f || position.x > layout.getLineRight(line)) return null
    val offset = layout.getOffsetForPosition(position)
    return spans.firstOrNull { offset >= it.range.first && offset <= it.range.last }?.targetId
}
