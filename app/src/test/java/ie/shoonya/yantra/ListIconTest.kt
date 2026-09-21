package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.ListIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One user-perceived character, whatever the keyboard sent.
 *
 * **Why this needs a test at all.** An emoji is very often not one `char`, and frequently not one
 * code point either: a flag is two regional indicators, a profession is a person joined to an
 * object by a zero-width joiner, a skin tone is a modifier hanging off the end, and several carry a
 * variation selector that is invisible and load-bearing. Anything that reasons in `length`, `take`
 * or `first()` cuts those in half and stores a fragment — which renders as a different emoji, or as
 * two, or as a box.
 *
 * The failure is also quiet: the file is written, the page round-trips, and the only evidence is a
 * list wearing something nobody chose.
 */
class ListIconTest {

    @Test
    fun `a plain emoji comes back whole`() {
        assertEquals("📥", ListIcon.clean("📥"))
    }

    @Test
    fun `a joined emoji is not cut at the joiner`() {
        // Woman technologist: woman + ZWJ + laptop. Taking one code point leaves a woman; taking
        // one char leaves half a surrogate pair.
        assertEquals("👩‍💻", ListIcon.clean("👩‍💻"))
    }

    @Test
    fun `a skin tone stays on the hand it belongs to`() {
        assertEquals("👍🏽", ListIcon.clean("👍🏽"))
    }

    @Test
    fun `a flag is two code points and one character`() {
        assertEquals("🇮🇪", ListIcon.clean("🇮🇪"))
    }

    @Test
    fun `a variation selector is kept, because without it the glyph changes`() {
        // ✈️ is ✈ plus U+FE0F. Dropping the selector leaves the monochrome text form, which looks
        // like a different icon rather than like the same one.
        val plane = "✈️"
        assertEquals(plane, ListIcon.clean(plane))
    }

    @Test
    fun `two emoji keep the first rather than being refused`() {
        // The field is fed by a keyboard: people type one, change their mind, and type another.
        // Refusing the pair would be a field that silently does nothing.
        assertEquals("📥", ListIcon.clean("📥💼"))
    }

    @Test
    fun `a word keeps its first letter`() {
        // Not emoji-only on purpose — a letter renders perfectly well in the slot, and the
        // alternative is a table of what counts as an emoji that is wrong the week Unicode ships.
        assertEquals("W", ListIcon.clean("Work"))
    }

    @Test
    fun `nothing usable is null rather than an empty string`() {
        assertNull(ListIcon.clean(null))
        assertNull(ListIcon.clean(""))
        assertNull(ListIcon.clean("   "))
        // Null is what the file format means by "no icon"; an empty string would be written out as
        // `icon: ` and read back as a blank choice nobody made.
        assertNull(ListIcon.clean("\n\t"))
    }

    @Test
    fun `surrounding space is not part of the choice`() {
        assertEquals("🎯", ListIcon.clean("  🎯  "))
    }

    @Test
    fun `every suggested icon survives its own cleaning`() {
        // The grid writes what it shows, so a suggestion that does not round-trip would store
        // something other than the cell that was tapped.
        ListIcon.suggested.forEach { emoji ->
            assertEquals("the grid offers something it cannot store: $emoji", emoji, ListIcon.clean(emoji))
        }
    }

    @Test
    fun `the grid has no duplicates`() {
        // Two identical cells would both light up as selected, and one of them is a typo.
        assertEquals(ListIcon.suggested.size, ListIcon.suggested.distinct().size)
    }
}
