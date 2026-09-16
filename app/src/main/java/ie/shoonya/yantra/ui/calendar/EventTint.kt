package ie.shoonya.yantra.ui.calendar

import ie.shoonya.yantra.data.label.LabelPalette

/**
 * What colour a block wears — CALENDAR_PLAN.md §16.
 *
 * **A name in the file, a value in the index.** A line says `col:teal`, and the word is resolved to
 * an ink at render time through [LabelPalette.display], so the same event is a slightly different
 * green on paper and at night. Writing the hex into the file would freeze whichever theme happened
 * to be on when it was picked, and a light-mode colour on a dark ground is the one that goes muddy.
 *
 * The set is [LabelPalette]'s, deliberately, rather than a wheel. That palette was already chosen to
 * sit on this paper, to stay clear of the 24°-71° arc the colour law reserves for priority and
 * effort, and to hold one lightness across every hue so no swatch out-shouts another. A calendar
 * with its own colours would be a second set to learn and a second chance to collide with the
 * accent.
 *
 * **Inheritance.** No colour on the line means the workspace's, which the app already derives from
 * the repository's name — the same hue the smart lists and the widget use to say which repo a task
 * came from. Following that rather than inventing a second rule is what keeps one workspace one
 * colour everywhere you meet it. And, exactly as those do, the workspace hue only applies when more
 * than one repository is open: with a single one it distinguishes nothing, and tinting every block
 * in the app a colour nobody chose would be noise.
 *
 * Nothing at either level leaves the block in the accent, which is what a calendar with no opinions
 * about colour looked like before any of this.
 */
object EventTint {

    /** The words a line may carry, in the order a picker should offer them. */
    val names: List<String> = LabelPalette.swatches.map { it.name }

    /**
     * The stored value for a colour word, or null for one this build does not know.
     *
     * An unrecognised word is not an error — a file written by a newer app should look ordinary
     * here, inheriting as though it had said nothing, rather than going invisible or crashing.
     */
    fun storedOf(name: String?): Long? =
        LabelPalette.swatches.firstOrNull { it.name.equals(name, ignoreCase = true) }?.light

    /** The name for a stored value, so a sheet can show what is already chosen. */
    fun nameOf(stored: Long?): String? =
        LabelPalette.swatches.firstOrNull { it.light == stored }?.name

    /** What a block ends up wearing: its own colour, else its workspace's, else nothing. */
    fun resolve(own: String?, workspace: Long?): Long? = storedOf(own) ?: workspace
}
