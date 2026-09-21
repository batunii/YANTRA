package ie.shoonya.yantra.data.format

import java.text.BreakIterator

/**
 * The one character a list wears instead of its drawn mark.
 *
 * **Why a string and not an index into a set.** The picker offers a curated grid, and a grid is a
 * set somebody chose — which is the right default and the wrong limit. Storing what was picked,
 * rather than which cell it came from, is what lets the field beside the grid accept anything the
 * keyboard can produce without the file format needing to know the grid exists. It also means the
 * grid can be re-ordered, extended or cut without touching a single stored page.
 *
 * **Why exactly one grapheme.** An emoji is frequently several code points — a flag is two, a
 * profession is a person joined to an object by a zero-width joiner, a skin tone is a modifier
 * hanging off the end — so counting `char`s or even code points would cut 👩‍💻 into a woman and a
 * laptop. [BreakIterator] is the only thing here that knows where a *user-perceived character*
 * ends, which is the unit the slot actually holds.
 *
 * Anything is allowed through, not only emoji. A letter or a digit renders perfectly well in the
 * slot and somebody may reasonably want one, and the alternative — a table of what counts as an
 * emoji — is a list that is wrong the week a new Unicode version ships.
 */
object ListIcon {

    /**
     * The first user-perceived character of [input], or null when there is nothing usable in it.
     *
     * Takes the first rather than refusing anything longer, because the field is fed by a keyboard:
     * people paste a word, or type two emoji and change their mind about the second, and refusing
     * the lot would mean a field that silently does nothing. Taking the first is the reading that
     * matches what the preview beside it is already showing them.
     */
    fun clean(input: String?): String? {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return null
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val end = it.next()
        if (end == BreakIterator.DONE || end <= 0) return null
        return text.substring(0, end).takeIf { first -> first.isNotBlank() }
    }

    /**
     * The grid, which is the whole of the choice for almost everybody.
     *
     * Chosen for *what a list is about* rather than as a sample of Unicode: the things people
     * actually name a list after — a place, a job, a subject, a habit. Ordered so the first row is
     * the likeliest, because on a phone the first row is what gets looked at.
     *
     * Deliberately not "recently used". A remembered order would put the grid in a different shape
     * every time it opened, and a picker you have to re-read is slower than a fixed one you learn.
     */
    val suggested: List<String> = listOf(
        "📥", "💼", "🏠", "🎯", "📚", "🛒", "✈️", "💡",
        "🔥", "⭐", "🎨", "🧠", "🌱", "⚙️", "📎", "🎵",
        "❤️", "🧾", "🗓️", "🏃", "🍳", "🔧", "📦", "🎁",
        "🐾", "☕", "🧪", "🗺️", "🎧", "🏆", "🌍", "🔒",
        "📈", "🧹", "👥", "🩺", "🎬", "🪴", "🧩", "🌙",
    )
}
