package ie.napkin.supertasks.data.format

/**
 * Where the app agrees with itself about what `**bold**` means.
 *
 * Emphasis lives in the stored text — a block is an arbitrary string, so the markers simply sit in
 * it, stay greppable, survive export and sync, and are what you typed. That makes *reading* them a
 * property of the format rather than of any one screen, which is why the rules are here and not
 * beside a `SpanStyle`.
 *
 * **Prose only.** A note, a heading, a bullet — things written to be read. A task title is not prose
 * but a name, and it is deliberately literal: it appears on surfaces that cannot style anything at
 * all (a widget, a notification, the archive, the focus screen), where emphasis could only be
 * dropped or shown raw. Rather than have a title mean one thing on a page and another in a widget,
 * it means exactly the characters it contains, everywhere.
 *
 * Rendering keeps the markers and merely dims them, so the string shown is exactly as long as the
 * string stored and the caret cannot drift.
 */
object Markdown {

    enum class Kind { CODE, BOLD, ITALIC, BOLD_ITALIC }

    /** One run of emphasis: [outer] includes the markers, [inner] is the text between them. */
    data class Run(val kind: Kind, val outer: IntRange, val inner: IntRange)

    /**
     * `***both***` needs its own pattern and has to be tried first.
     *
     * The bold pattern would otherwise match `***both**` — two markers at the front, two at the back
     * — leaving a stray asterisk undimmed and the closing pair unmatched.
     */
    private val BOLD_ITALIC = Regex("""\*\*\*(?=\S)(.+?)(?<=\S)\*\*\*""")
    private val BOLD = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
    private val ITALIC = Regex("""(?<!\*)\*(?=\S)([^*]+?)(?<=\S)\*(?!\*)""")
    private val CODE = Regex("""`(?=\S)([^`]+?)(?<=\S)`""")

    /**
     * Every emphasis run in [text], nested ones included, in the order they start.
     *
     * Nesting is why this recurses: `**a *b* c**` has an italic run inside a bold one, and seeing
     * only the outer run would leave the inner markers undimmed and the inner words unitalicised.
     *
     * Code is the one thing not recursed into: inside backticks an asterisk is content, and
     * `` `2 * 3` `` must survive intact.
     */
    fun runs(text: String): List<Run> = runsIn(text, 0, text.length).sortedBy { it.outer.first }

    private fun runsIn(text: String, from: Int, to: Int): List<Run> {
        if (to - from < 3) return emptyList()
        val slice = text.substring(from, to)
        val found = mutableListOf<Run>()
        // Claimed characters, so `*italic*` cannot match the inner asterisks of `**bold**`, and code
        // spans win over anything that looks like emphasis inside them.
        val claimed = BooleanArray(slice.length)

        fun scan(pattern: Regex, markerLength: Int, kind: Kind) {
            for (match in pattern.findAll(slice)) {
                val outer = match.range
                if (outer.any { claimed[it] }) continue
                val innerStart = outer.first + markerLength
                val innerEnd = outer.last + 1 - markerLength
                if (innerEnd <= innerStart) continue
                outer.forEach { claimed[it] = true }
                found += Run(
                    kind,
                    (from + outer.first)..(from + outer.last),
                    (from + innerStart) until (from + innerEnd),
                )
                // Inside a code span the markers are content; anywhere else they are markers.
                if (kind != Kind.CODE) {
                    found += runsIn(text, from + innerStart, from + innerEnd)
                }
            }
        }

        // Longest marker first: *** before **, ** before *, or each pattern eats part of a longer
        // pair and leaves the remainder stranded.
        scan(CODE, 1, Kind.CODE)
        scan(BOLD_ITALIC, 3, Kind.BOLD_ITALIC)
        scan(BOLD, 2, Kind.BOLD)
        scan(ITALIC, 1, Kind.ITALIC)
        return found
    }
}
