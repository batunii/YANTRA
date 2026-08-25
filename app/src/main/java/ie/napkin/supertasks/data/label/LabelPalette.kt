package ie.napkin.supertasks.data.label


/**
 * The five colours a label may wear.
 *
 * Labels are the app's one open-ended, user-extensible mechanism, and they are the only place a
 * user picks a colour — the accent is not a preference (see ThemeController) and never becomes
 * one. So this is a closed, curated set rather than a colour wheel: five hues that were chosen to
 * sit on warm paper, not every colour that exists.
 *
 * **Why these five.** The colour law gives three hues jobs already — crimson (H≈25), coral (H≈38)
 * and amber (H≈70) — so the whole 24°–71° arc is spoken for and a label may not enter it. The five
 * below are spread across the remaining arc at roughly 50° apart, which is the closest two labels
 * can sit and still be told apart on an 11sp chip.
 *
 * **Why they look like a set.** Every swatch is the same OKLCH lightness and chroma; only the hue
 * moves. That is the same discipline the grounds are built with, and it is why none of them
 * out-shouts another — in HSL a fixed lightness would make the green glare and the blue go muddy.
 * Chroma is capped at what teal can actually reach in sRGB (the binding constraint at this
 * lightness), so the set stays uniform rather than letting four hues be vivid and one be flat.
 * The result sits deliberately below coral's chroma: a label says *which* thing, not *how urgent*.
 *
 * **Stored vs shown.** A label stores its light-mode value; [display] swaps in the dark twin at
 * render time, exactly as [ie.napkin.supertasks.ui.ink.InkTheme.displayColor] does for ink. So a
 * label named in light mode still reads correctly at night, one stored Long covers both, and
 * anything not from this palette passes through untouched.
 *
 * Deliberately free of Compose types: the repository assigns a default colour on create, and
 * nothing under `data/` may reach up into `ui/`. The Color-shaped convenience lives at the call
 * site in the chip renderer instead.
 */
object LabelPalette {

    /** One colour, in its two theme dresses. [light] is the canonical stored value. */
    data class Swatch(val name: String, val light: Long, val dark: Long)

    // Generated at OKLCH L=0.600 C=0.104 (light) and L=0.730 C=0.125 (dark).
    // Hues: 140 / 190 / 240 / 290 / 335 — every one of them clear of the 24°–71° reserved arc.
    val swatches: List<Swatch> = listOf(
        Swatch("Moss",   0xFF5D8F52, 0xFF7CBB6E),
        Swatch("Teal",   0xFF00948E, 0xFF0AC0B9),
        Swatch("Blue",   0xFF3D88B8, 0xFF54B1EE),
        Swatch("Violet", 0xFF8075BA, 0xFFA799F1),
        Swatch("Plum",   0xFFA66799, 0xFFD889C7),
    )

    private val darkOf: Map<Long, Long> = swatches.associate { it.light to it.dark }
    private val lightOf: Map<Long, Long> = swatches.associate { it.dark to it.light }

    /**
     * The value to actually paint. Palette colours swap to their twin for the current theme;
     * everything else — a legacy value, a select option's colour — is returned unchanged.
     */
    fun display(stored: Long, dark: Boolean): Long =
        if (dark) darkOf[stored] ?: stored else lightOf[stored] ?: stored

    /**
     * The colour a label gets when nobody picks one.
     *
     * Derived from the name rather than assigned in order, so the same tag is the same colour on
     * every device and across a reinstall — and so a handful of new labels come out different
     * colours instead of all landing on the first swatch. The user can always override it.
     */
    fun defaultFor(name: String): Long {
        val key = name.trim().lowercase()
        var h = 0
        for (ch in key) h = h * 31 + ch.code
        return swatches[((h % swatches.size) + swatches.size) % swatches.size].light
    }
}
