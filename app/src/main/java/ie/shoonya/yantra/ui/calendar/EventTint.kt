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
 * **No inheritance.** The workspace used to be the fallback for a block with no colour of its own,
 * and that was one rule doing two jobs: the same 3dp of green then meant "this repository" on a
 * widget row and "this block, specifically" here, and with five swatches there is no telling those
 * apart. So the two facts were given two places on the block — the **spine** is the repository and
 * the **fill** is the block — and a colour is never read out of a hue alone, because the name is
 * always within a glance of it.
 *
 * What a block's fill can still borrow is the thing it *is*: a sitting is a task seen as an hour,
 * so an uncoloured sitting wears the colour of the list its task lives on. An uncoloured
 * appointment has nothing to borrow and stays in the accent, which is what a calendar with no
 * opinions about colour looked like before any of this.
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
}
