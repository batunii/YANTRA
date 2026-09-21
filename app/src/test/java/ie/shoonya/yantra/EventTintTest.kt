package ie.shoonya.yantra

import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.ui.calendar.EventTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The word a line carries, resolved to an ink — CALENDAR_PLAN.md §16.
 *
 * There is no chain of fallbacks here any more: the workspace moved to the spine, so this answers
 * one question and answers it from one word. The case worth guarding hardest is a **word this build
 * does not know** — it must come back as nothing, exactly as though the line had said nothing, so a
 * file written by a newer app looks ordinary here rather than making the block vanish.
 *
 * What a block does with a null is [ie.shoonya.yantra.ui.calendar.CalendarBucketer]'s business, and
 * [CalendarBucketTest] holds it: a sitting borrows its task's list, an appointment takes the accent.
 */
class EventTintTest {

    private val teal = LabelPalette.swatches.first { it.name == "Teal" }.light
    private val plum = LabelPalette.swatches.first { it.name == "Plum" }.light

    @Test
    fun `a known colour is its own`() {
        assertEquals(teal, EventTint.storedOf("Teal"))
    }

    @Test
    fun `the word is not case-sensitive, because a file is written by hand`() {
        assertEquals(teal, EventTint.storedOf("teal"))
        assertEquals(plum, EventTint.storedOf("  PLUM  ".trim()))
    }

    @Test
    fun `a colour this build does not know is nothing, not a wrong colour`() {
        assertNull(EventTint.storedOf("Vermilion"))
    }

    @Test
    fun `no word at all is nothing, which is the accent`() {
        assertNull(EventTint.storedOf(null))
    }

    @Test
    fun `every offered name resolves, or the picker offers a colour that does nothing`() {
        EventTint.names.forEach { assertNotNull(it, EventTint.storedOf(it)) }
        assertEquals(EventTint.names.size, EventTint.names.mapNotNull { EventTint.storedOf(it) }.toSet().size)
    }

    @Test
    fun `a stored value names itself again, so a sheet can show what is chosen`() {
        assertEquals("Teal", EventTint.nameOf(teal))
        assertNull(EventTint.nameOf(null))
    }
}
