package ie.shoonya.yantra

import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.ui.calendar.EventTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which colour a block ends up wearing — CALENDAR_PLAN.md §16.
 *
 * The rule is a chain of fallbacks and every link matters: an event's own word wins, a workspace's
 * hue is what it falls back to, and nothing at either level leaves it in the accent. The case worth
 * guarding hardest is a **word this build does not know** — it must inherit, exactly as though the
 * line had said nothing, rather than paint nothing and make the block vanish.
 */
class EventTintTest {

    private val teal = LabelPalette.swatches.first { it.name == "Teal" }.light
    private val plum = LabelPalette.swatches.first { it.name == "Plum" }.light

    @Test
    fun `a known colour is its own`() {
        assertEquals(teal, EventTint.resolve(own = "Teal", workspace = null))
    }

    @Test
    fun `the word is not case-sensitive, because a file is written by hand`() {
        assertEquals(teal, EventTint.resolve(own = "teal", workspace = null))
    }

    @Test
    fun `no colour of its own takes the workspace's`() {
        assertEquals(plum, EventTint.resolve(own = null, workspace = plum))
    }

    @Test
    fun `its own beats the workspace's`() {
        assertEquals(teal, EventTint.resolve(own = "Teal", workspace = plum))
    }

    @Test
    fun `a colour this build does not know inherits rather than disappearing`() {
        assertEquals(plum, EventTint.resolve(own = "Vermilion", workspace = plum))
    }

    @Test
    fun `nothing anywhere is nothing, which is the accent`() {
        assertNull(EventTint.resolve(own = null, workspace = null))
        assertNull(EventTint.resolve(own = "Vermilion", workspace = null))
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
