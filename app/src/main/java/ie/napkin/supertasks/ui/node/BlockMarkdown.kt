package ie.napkin.supertasks.ui.node

import ie.napkin.supertasks.data.db.NodeType

/**
 * Block-level markdown: the marker you type at the start of a line, and what the line becomes.
 *
 * Only the *block* shapes are here. Inline emphasis (`**bold**`) is deliberately absent — it is
 * rendered from the markers stored in the text itself and never changes what kind of block this is
 * (see `InlineText`), as are links.
 *
 * Lives in its own file, away from the screen, because it is the half of the feature that can be
 * tested: whether "## " means a heading is a question with an answer, and it should not need an
 * emulator and a keyboard to ask it.
 */
internal object BlockMarkdown {

    /**
     * Longest marker first.
     *
     * `## ` has to be tried before `# `, or a level-two heading is matched by the level-one rule
     * and the second hash is left in the text. They both land on the same block type here, so
     * nothing observable turned on it yet — but the ordering is the kind of thing that is true
     * until someone adds `#> ` and then silently is not.
     */
    private val MARKERS: List<Pair<Regex, String>> = listOf(
        Regex("^#### ") to NodeType.HEADING,
        Regex("^### ") to NodeType.HEADING,
        Regex("^## ") to NodeType.HEADING,
        Regex("^# ") to NodeType.HEADING,
        Regex("^[-*+] ") to NodeType.BULLET,
        Regex("""^\d+[.)] """) to NodeType.NUMBERED,
        Regex("""^\[[ xX]?] """) to NodeType.TASK,
    )

    /** What a line has just become, and what is left of its text once the marker is eaten. */
    data class Become(val type: String, val rest: String)

    /**
     * The marker [text] starts with, if it names a kind this block is not already.
     *
     * Null for everything else, including a marker that names the kind the block already has —
     * typing `- ` in a bullet is someone writing a dash, not asking for a bullet again.
     */
    fun match(text: String, currentType: String): Become? {
        for ((marker, becomes) in MARKERS) {
            val hit = marker.find(text) ?: continue
            // The first marker that matches decides, whether or not it is a useful answer. Carrying
            // on down the list after a match would let "- " fall through to some later rule the
            // moment it was already a bullet, which is not what the line says.
            if (becomes == currentType) return null
            return Become(becomes, text.removeRange(hit.range))
        }
        return null
    }
}
