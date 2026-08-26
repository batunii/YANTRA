package ie.napkin.supertasks

import ie.napkin.supertasks.data.label.LabelPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The palette's promises are numeric, so they can be checked rather than eyeballed: the swatches
 * are a uniform set, none of them trespasses on a hue the colour law has already assigned, and no
 * two are close enough to be confused on a chip.
 */
class LabelPaletteTest {

    // --- sRGB -> OKLCH, the inverse of ui/theme/OklchColor.kt's oklch() ---

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

    /** Shortest angular distance between two hues, degrees. */
    private fun hueGap(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    @Test
    fun `every swatch shares one lightness and chroma`() {
        // This is the whole reason the set reads as a set — only the hue may move.
        val light = LabelPalette.swatches.map { lch(it.light) }
        val dark = LabelPalette.swatches.map { lch(it.dark) }
        light.forEach { assertEquals(0.600f, it.l, 0.01f); assertEquals(0.104f, it.c, 0.008f) }
        dark.forEach { assertEquals(0.730f, it.l, 0.01f); assertEquals(0.125f, it.c, 0.008f) }
    }

    @Test
    fun `a swatch keeps its hue across themes`() {
        // Light and dark are the same colour in two dresses, not two different colours.
        LabelPalette.swatches.forEach { sw ->
            assertTrue(
                "${sw.name} shifts hue between themes",
                hueGap(lch(sw.light).h, lch(sw.dark).h) < 2f,
            )
        }
    }

    @Test
    fun `no swatch enters the hues the colour law has spoken for`() {
        // crimson H~25, coral H~38, amber H~71 — a label must never be mistaken for priority
        // or for the user's own effort.
        LabelPalette.swatches.forEach { sw ->
            listOf(25f to "crimson", 38f to "coral", 71f to "amber").forEach { (reserved, who) ->
                assertTrue(
                    "${sw.name} sits too close to $who",
                    hueGap(lch(sw.light).h, reserved) > 35f,
                )
            }
        }
    }

    @Test
    fun `no two swatches are close enough to confuse`() {
        val hues = LabelPalette.swatches.map { lch(it.light).h }
        for (i in hues.indices) for (j in i + 1 until hues.size) {
            assertTrue(
                "${LabelPalette.swatches[i].name} and ${LabelPalette.swatches[j].name} are too close",
                hueGap(hues[i], hues[j]) > 40f,
            )
        }
    }

    @Test
    fun `display swaps a swatch for its twin and leaves everything else alone`() {
        val sw = LabelPalette.swatches.first()
        assertEquals(sw.dark, LabelPalette.display(sw.light, dark = true))
        assertEquals(sw.light, LabelPalette.display(sw.dark, dark = false))
        // Already correct for the theme: unchanged.
        assertEquals(sw.light, LabelPalette.display(sw.light, dark = false))
        // A select option's colour is not ours to reinterpret.
        val priorityHigh = 0xFFFF4A1FL
        assertEquals(priorityHigh, LabelPalette.display(priorityHigh, dark = true))
    }

    @Test
    fun `display round-trips`() {
        LabelPalette.swatches.forEach { sw ->
            val there = LabelPalette.display(sw.light, dark = true)
            assertEquals(sw.light, LabelPalette.display(there, dark = false))
        }
    }

    @Test
    fun `the default colour is stable for a name and ignores case and padding`() {
        // Same tag, same colour on every device and after a reinstall.
        assertEquals(LabelPalette.defaultFor("groceries"), LabelPalette.defaultFor("  Groceries "))
        assertEquals(LabelPalette.defaultFor("errands"), LabelPalette.defaultFor("errands"))
    }

    @Test
    fun `the default colour is always a light swatch value`() {
        val lights = LabelPalette.swatches.map { it.light }.toSet()
        listOf("a", "work", "home", "errands", "reading", "", "ünïcode", "12345").forEach {
            assertTrue("defaultFor($it) escaped the palette", LabelPalette.defaultFor(it) in lights)
        }
    }

    @Test
    fun `different names generally get different colours`() {
        val names = listOf("work", "home", "errands", "reading", "health")
        val distinct = names.map { LabelPalette.defaultFor(it) }.toSet().size
        // Not a guarantee for any given pair — five buckets, so collisions exist — but the
        // assignment must not collapse everything onto one swatch.
        assertNotEquals(1, distinct)
    }
}
