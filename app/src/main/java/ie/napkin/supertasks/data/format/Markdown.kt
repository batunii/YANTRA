package ie.napkin.supertasks.data.format

/**
 * Where the app agrees with itself about what `**bold**` means.
 *
 * Emphasis lives in the stored text — a title has always been an arbitrary string, so the markers
 * simply sit in it, stay greppable, survive export and sync, and are what you typed. That makes
 * *reading* them a property of the format rather than of any one screen, which is why the rules are
 * here and not beside a `SpanStyle`.
 *
 * Two consumers need different things from the same rule. A screen that can style text keeps the
 * markers and dims them, so the string it renders is exactly as long as the string it stores and the
 * caret cannot drift. A widget, a notification or anywhere else that cannot style at all needs them
 * gone, because `**ship it**` with visible asterisks is worse than either alternative. Both come
 * from [runs], so the two can never disagree about where the emphasis is.
 */
object Markdown {

    enum class Kind { CODE, BOLD, ITALIC, BOLD_ITALIC }

    /** One run of emphasis: [outer] includes the markers, [inner] is the text between them. */
    data class Run(val kind: Kind, val outer: IntRange, val inner: IntRange)

    /**
     * `***both***` needs its own pattern and has to be tried first.
     *
     * The bold pattern would otherwise match `***both**` — two markers at the front, two at the back
     * — and leave a stray asterisk behind, which is precisely the punctuation stripping exists to
     * remove.
     */
    private val BOLD_ITALIC = Regex("""\*\*\*(?=\S)(.+?)(?<=\S)\*\*\*""")
    private val BOLD = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
    private val ITALIC = Regex("""(?<!\*)\*(?=\S)([^*]+?)(?<=\S)\*(?!\*)""")
    private val CODE = Regex("""`(?=\S)([^`]+?)(?<=\S)`""")

    /**
     * Every emphasis run in [text], nested ones included, in the order they start.
     *
     * Nesting matters more for stripping than for styling. `**a *b* c**` styles acceptably either
     * way, but a stripper that only saw the outer run would leave the inner asterisks behind — the
     * exact punctuation it exists to remove.
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

    /**
     * [text] with the markers removed and the words kept.
     *
     * For everywhere that cannot render emphasis at all. Losing the styling is a fair trade; showing
     * the asterisks is not, because they are punctuation the reader never wrote for the reader.
     */
    fun strip(text: String): String {
        val runs = runs(text)
        if (runs.isEmpty()) return text
        // Marker positions rather than run boundaries: with nesting the runs overlap, and copying
        // "everything outside a run" would drop the inner text of every nested pair.
        val marker = BooleanArray(text.length)
        runs.forEach { run ->
            for (i in run.outer.first until run.inner.first) marker[i] = true
            for (i in run.inner.last + 1..run.outer.last) marker[i] = true
        }
        val sb = StringBuilder(text.length)
        text.forEachIndexed { i, ch -> if (!marker[i]) sb.append(ch) }
        return sb.toString()
    }

}
