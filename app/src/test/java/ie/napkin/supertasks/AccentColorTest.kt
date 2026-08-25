package ie.napkin.supertasks

import ie.napkin.supertasks.data.label.LabelPalette
import ie.napkin.supertasks.ui.theme.AccentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Letting the accent move is the one thing the colour law was written to prevent, so the rules
 * that make it safe are asserted rather than trusted: no option may enter the priority band, and
 * none may land on a label hue.
 */
class AccentColorTest {

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private data class Lch(val l: Float, val c: Float, val h: Float)

    private fun lch(argb: Long): Lch {
        val r = srgbToLinear(((argb shr 16) and 0xFF).toFloat() / 255f)
        val g = srgbToLinear(((argb shr 8) and 0xFF).toFloat() / 255f)
        val b = srgbToLinear((argb and 0xFF).toFloat() / 255f)
        val lp = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val mp = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val sp = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        val ll = 0.2104542553f * lp + 0.7936177850f * mp - 0.0040720468f * sp
        val a = 1.9779984951f * lp - 2.4285922050f * mp + 0.4505937099f * sp
        val bb = 0.0259040371f * lp + 0.7827717662f * mp - 0.8086757660f * sp
        return Lch(ll, hypot(a, bb), (Math.toDegrees(atan2(bb, a).toDouble()).toFloat() + 360f) % 360f)
    }

    private fun argb(c: androidx.compose.ui.graphics.Color): Long =
        (((c.alpha * 255f).toInt().toLong() shl 24) or
            ((c.red * 255f).toInt().toLong() shl 16) or
            ((c.green * 255f).toInt().toLong() shl 8) or
            (c.blue * 255f).toInt().toLong())

    private fun hueGap(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    @Test
    fun `coral is exactly the brand ink, not a regenerated approximation`() {
        // The glyph file is the source of truth for coral; a rounding difference here would show
        // up as a drawn glyph disagreeing with the surface beside it.
        assertEquals(0xFFD85A30L, argb(AccentColor.CORAL.ink(dark = false)))
        assertEquals(0xFFE8865FL, argb(AccentColor.CORAL.ink(dark = true)))
    }

    @Test
    fun `no accent enters the priority band`() {
        // crimson H~25 and amber H~71 are the world asking, and they share a task row with the
        // accent. This is the rule that cannot bend.
        AccentColor.entries.filter { it != AccentColor.CORAL }.forEach { a ->
            listOf(false, true).forEach { dark ->
                val h = lch(argb(a.ink(dark))).h
                assertTrue("${a.label} sits inside the priority band at hue $h", h < 20f || h > 75f)
            }
        }
    }

    @Test
    fun `no accent lands on a label hue`() {
        // A teal tag and a teal progress arc would say two different things in one colour.
        val labelHues = LabelPalette.swatches.map { lch(it.light).h }
        AccentColor.entries.filter { it != AccentColor.CORAL }.forEach { a ->
            val h = lch(argb(a.ink(dark = false))).h
            val nearest = labelHues.minOf { hueGap(h, it) }
            assertTrue("${a.label} is only ${nearest}° from a label hue", nearest > 18f)
        }
    }

    @Test
    fun `every accent carries coral's weight`() {
        // Switching accent must change the hue of the app, not how loud it is. Chroma is capped
        // where sRGB cannot reach coral's, so it may be lower — never higher.
        val coralLight = lch(argb(AccentColor.CORAL.ink(false)))
        val coralDark = lch(argb(AccentColor.CORAL.ink(true)))
        AccentColor.entries.forEach { a ->
            val l = lch(argb(a.ink(false)))
            val d = lch(argb(a.ink(true)))
            assertEquals("${a.label} light lightness", coralLight.l, l.l, 0.02f)
            assertEquals("${a.label} dark lightness", coralDark.l, d.l, 0.02f)
            assertTrue("${a.label} is more chromatic than coral", l.c <= coralLight.c + 0.005f)
            assertTrue("${a.label} is more chromatic than coral", d.c <= coralDark.c + 0.005f)
        }
    }

    @Test
    fun `accents keep their hue across themes`() {
        AccentColor.entries.forEach { a ->
            val gap = hueGap(lch(argb(a.ink(false))).h, lch(argb(a.ink(true))).h)
            assertTrue("${a.label} shifts hue between themes by $gap°", gap < 6f)
        }
    }

    @Test
    fun `accents are distinguishable from each other`() {
        val hues = AccentColor.entries.map { lch(argb(it.ink(false))).h }
        for (i in hues.indices) for (j in i + 1 until hues.size) {
            assertTrue(
                "${AccentColor.entries[i].label} and ${AccentColor.entries[j].label} are too close",
                hueGap(hues[i], hues[j]) > 40f,
            )
        }
    }

    @Test
    fun `an unknown or missing stored value falls back to coral`() {
        // A downgrade, a corrupted pref, or a removed option must not crash or blank the accent.
        assertEquals(AccentColor.CORAL, AccentColor.from(null))
        assertEquals(AccentColor.CORAL, AccentColor.from("MAGENTA"))
        assertEquals(AccentColor.CORAL, AccentColor.from(""))
        assertEquals(AccentColor.INDIGO, AccentColor.from("INDIGO"))
    }
}
