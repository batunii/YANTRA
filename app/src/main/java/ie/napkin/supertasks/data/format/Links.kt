package ie.napkin.supertasks.data.format

/**
 * Where the app agrees with itself about what `[[Call Bob|^9f1e…]]` means.
 *
 * A link is one task pointing at another, written into the ordinary text of a block. It lives in
 * the stored string exactly as emphasis does — greppable, diffable, survives export, and is what
 * you typed — which is why the rules are here beside [Markdown] and not next to a `SpanStyle`.
 *
 * ## The syntax, and why each half of it
 *
 * `[[` … `]]` is the family the format already uses for embeds (`![[ink:…]]`, `![[image:…]]`) minus
 * the `!` that means *embed*. `^` is already this format's sigil for "the thing after me is a task
 * id" — it is what a task line writes its own id behind. So a link borrows two conventions the
 * file already has rather than inventing a third.
 *
 * Display text comes **first**, and that is the half that matters for a file people read. `See
 * [[Call Bob|^9f1e…]] before Friday` says something in a terminal, in a diff and in `grep`;
 * `See [[9f1e…]]` says nothing at all.
 *
 * ## The id is not optional
 *
 * `[[Call Bob]]` is not a link. It is the characters `[[Call Bob]]`.
 *
 * Resolving a link by its title is the obvious convenience and it is wrong here: titles are not
 * unique — two tasks called "Call Bob" is a Tuesday — so name resolution has to *pick* one, and a
 * link that quietly points at the wrong task is worse than a link that was never made. Requiring
 * the id costs nothing in practice because the id is never typed: `[[` opens a picker.
 *
 * ## The label is a fallback, not the answer
 *
 * Rendering prefers the target's *current* title, looked up by id, and falls back to the stored
 * label when the id resolves to nothing — the task was deleted, or it lives in a workspace this
 * device has not added. That is why the label is worth storing even though the index could supply
 * it: it is what the file says when nothing is there to resolve it, and it is what a rename leaves
 * behind rather than a dangling id.
 *
 * The alternative — rewriting every link to a task when that task is renamed — was rejected because
 * a rename here happens per keystroke, and it would mean writing other people's files while
 * somebody types.
 */
object Links {

    const val OPEN = "[["
    const val CLOSE = "]]"

    /**
     * `[[label|^id]]`.
     *
     * The label may not contain `[`, `]`, `|` or a newline, and the id may not contain whitespace
     * either — so an unterminated `[[` cannot swallow the rest of a paragraph looking for a close,
     * and a line with two links on it cannot be read as one enormous one.
     */
    private val LINK = Regex("""\[\[([^\[\]|\n]*)\|\^([^\[\]|\s]+)]]""")

    /** One link found in a string. [range] covers the whole `[[…]]`, brackets included. */
    data class Link(
        /** What the file says this is called. See the note on fallbacks in [Links]. */
        val label: String,
        val targetId: String,
        val range: IntRange,
        /** Where the label itself sits inside [range] — the part that survives collapsing. */
        val labelRange: IntRange,
    )

    fun links(text: String): List<Link> =
        LINK.findAll(text).map { m ->
            val label = m.groupValues[1]
            val labelStart = m.range.first + OPEN.length
            Link(
                label = label,
                targetId = m.groupValues[2],
                range = m.range,
                labelRange = labelStart until (labelStart + label.length),
            )
        }.toList()

    /** Every node this text points at, once each, in the order they are written. */
    fun targets(text: String): List<String> = links(text).map { it.targetId }.distinct()

    fun hasLink(text: String): Boolean = LINK.containsMatchIn(text)

    /**
     * A link to [targetId] that reads as [label].
     *
     * The three characters the syntax spends are replaced rather than escaped. An escape would need
     * the parser to know about it too, and a title containing a literal `|` is rare enough that
     * quietly widening it to a space is a better trade than a second grammar nobody can see.
     */
    fun encode(label: String, targetId: String): String {
        val safe = label.map { if (it == '[' || it == ']' || it == '|' || it == '\n') ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        return "$OPEN${safe.ifEmpty { "Untitled" }}|^$targetId$CLOSE"
    }

    // ---- collapsing ----

    /** One link's place in a collapsed string, in the *collapsed* string's coordinates. */
    data class Shown(val range: IntRange, val targetId: String, val resolved: Boolean)

    /**
     * [text] with every link reduced to what it says, and where each landed.
     *
     * [title] answers "what is this id called now"; returning null means it could not be resolved,
     * and the stored label is shown instead.
     *
     * [map] is the offset table: `map[i]` is where original offset `i` ended up. It runs to
     * `text.length` inclusive so a range's exclusive end can be mapped too. It exists for the one
     * caller that needs an `OffsetMapping` and is exact rather than approximate, because a caret
     * that lands one character off is the whole reason the rest of this app leaves its markers
     * visible.
     */
    data class Collapsed(
        val text: String,
        val shown: List<Shown>,
        val map: IntArray,
        /**
         * The other direction: for each offset in the collapsed string, an offset in the original.
         *
         * Not simply the inverse of [map], because [map] is not injective — every character a link
         * removed maps to the same place — so one collapsed offset has to *choose* an original one,
         * and the choice is what a caret sits on.
         *
         * The rule is that the position just past a collapsed link is past the whole link, not just
         * past its label. Those are the same point on screen and forty characters apart in the
         * file, and picking the near one is a bug you can feel: the caret looks like it is at the
         * end of the line, and Backspace deletes the last letter of the *name inside the brackets*
         * — leaving `[[get mil|^2fa676e0…]]` in the file and no visible change on screen.
         */
        val back: IntArray,
    ) {
        /** Where an original range ended up. Empty when the range was removed outright. */
        fun mapRange(r: IntRange): IntRange = map[r.first] until map[r.last + 1]

        // Data classes with an array member need these spelled out, and identity is the honest
        // answer: two collapses of the same text produce equal tables, and nothing compares them.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun collapse(text: String, title: (String) -> String? = { null }): Collapsed {
        val found = links(text)
        if (found.isEmpty()) {
            val identity = identityMap(text.length)
            return Collapsed(text, emptyList(), identity, identity.copyOf())
        }

        val edits = ArrayList<Edit>(found.size * 3)
        found.forEach { link ->
            val resolved = title(link.targetId)
            edits += Edit(link.range.first until link.labelRange.first, "")
            if (resolved != null && resolved != link.label) {
                edits += Edit(link.labelRange, resolved)
            }
            edits += Edit((link.labelRange.last + 1)..link.range.last, "")
        }

        val (out, map) = rewrite(text, edits)
        val shown = found.map { link ->
            Shown(
                range = map[link.labelRange.first] until map[link.labelRange.last + 1],
                targetId = link.targetId,
                resolved = title(link.targetId) != null,
            )
        }
        val back = inverse(map)
        // Past the label is past the link. See Collapsed.back.
        found.forEachIndexed { i, link ->
            val end = shown[i].range.last + 1
            if (end < back.size) back[end] = link.range.last + 1
        }
        return Collapsed(out, shown, map, back)
    }

    /**
     * [text] with every link reduced to plain characters — for surfaces that cannot style anything
     * at all: a notification, a widget, the archive, a share sheet.
     *
     * This is the concession that makes links tolerable in a *task title*. A title is deliberately
     * literal everywhere else in this app precisely because those surfaces exist; a link is the one
     * construct given an exception, and only because it has a defined plain-text reading. Printing
     * `[[Call Bob|^9f1e…]]` in a notification would not be honesty about the stored characters, it
     * would be the app failing to render.
     */
    fun plain(text: String, title: (String) -> String? = { null }): String =
        if (!hasLink(text)) text else collapse(text, title).text

    // ---- the picker's half ----

    /**
     * The `[[…` currently being typed, for a field that wants to offer somewhere to point.
     *
     * The last unclosed `[[` at or before [caret], and the partial name after it. Null when the
     * caret is not inside one — which includes the case where the link has already been closed,
     * because a finished link is a decision the user has made and moved past.
     *
     * Mirrors [ie.napkin.supertasks.data.capture.CaptureParse.listDraft], deliberately: two
     * different completion strips in one app that disagree about when they are open would be worse
     * than either of them being slightly wrong.
     */
    fun draft(text: String, caret: Int): Pair<IntRange, String>? {
        val at = caret.coerceIn(0, text.length)
        val open = text.lastIndexOf(OPEN, (at - 1).coerceAtLeast(0))
        if (open < 0 || open + OPEN.length > at) return null
        val typed = text.substring(open + OPEN.length, at)
        // Anything that ends the token ends the draft. A newline or a bracket means the `[[` was
        // never the start of a link; a `]]` means it was one and is finished.
        if (typed.contains(CLOSE) || typed.any { it == '\n' || it == '[' || it == ']' }) return null
        return (open until at) to typed
    }

    // ---- offset bookkeeping ----

    /** [range] of the original becomes [replacement]. Ranges never overlap. */
    private data class Edit(val range: IntRange, val replacement: String)

    /**
     * Applies [edits] and reports where every original offset ended up.
     *
     * One primitive rather than one per caller: collapsing a link and stripping an emphasis marker
     * are the same operation, and the offsets are the part that has to be right.
     */
    private fun rewrite(source: String, edits: List<Edit>): Pair<String, IntArray> {
        val sorted = edits.sortedBy { it.range.first }
        val out = StringBuilder(source.length)
        val map = IntArray(source.length + 1)
        var at = 0
        sorted.forEach { edit ->
            while (at < edit.range.first) {
                map[at] = out.length
                out.append(source[at])
                at++
            }
            val start = out.length
            out.append(edit.replacement)
            // Everything the edit covered maps to where the replacement begins; the offset just
            // past it maps to just past the replacement. That keeps the table non-decreasing, which
            // is what makes the inverse well-defined.
            while (at <= edit.range.last) {
                map[at] = start
                at++
            }
        }
        while (at < source.length) {
            map[at] = out.length
            out.append(source[at])
            at++
        }
        map[source.length] = out.length
        return out.toString() to map
    }

    private fun identityMap(length: Int) = IntArray(length + 1) { it }

    /**
     * The raw inverse of [Collapsed.map]: the *first* original offset that lands on each collapsed
     * one.
     *
     * The starting point for [Collapsed.back], which then corrects the one position where "first"
     * is the wrong answer. Public because it is the half that is worth testing on its own.
     */
    fun inverse(map: IntArray): IntArray {
        val out = IntArray(map.last() + 1) { -1 }
        for (i in map.indices.reversed()) out[map[i]] = i
        var last = 0
        for (t in out.indices) {
            if (out[t] < 0) out[t] = last else last = out[t]
        }
        return out
    }
}
