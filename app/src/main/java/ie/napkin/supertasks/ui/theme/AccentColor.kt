package ie.napkin.supertasks.ui.theme

import androidx.compose.ui.graphics.Color
import ie.napkin.supertasks.ui.components.YantraInk

/**
 * Which ink means "the user's own effort".
 *
 * This reopens something the colour law closed. The law's argument was never that coral is
 * special — it was that *one* hue has to own the effort layer, and that a free hue wheel would
 * eventually paint effort in the priority hue and make the glyphs unreadable. That argument is
 * satisfied by a closed set as well as by a single value, so this is a closed set:
 *
 *  - **Nothing enters the priority band.** Crimson (H≈25) and amber (H≈71) are the world asking,
 *    and they sit on the same task row as the accent. Every option here is far outside 20°–75°,
 *    which is the one rule that genuinely cannot bend.
 *  - **Nothing lands on a label hue.** [ie.napkin.supertasks.data.label.LabelPalette] occupies
 *    140/190/240/290/335, so these four sit in the gaps between them — 22°–25° clear. A teal
 *    tag and a teal progress arc would have said two different things in one colour.
 *  - **Every option carries coral's weight.** Each is generated at coral's OKLCH lightness and
 *    chroma, capped where sRGB cannot reach that far (jade and azure are the two that clamp), so
 *    switching accent changes the hue of the app and not how loud it is.
 *
 * Coral stays the default and keeps its exact brand values from [YantraInk] rather than being
 * regenerated — the glyph file is the source of truth for it, and a rounding difference between
 * the two would be a real bug.
 */
enum class AccentColor(val label: String, private val lightArgb: Long, private val darkArgb: Long) {
    CORAL("Coral", 0, 0),                          // sentinel: reads YantraInk directly, see [ink]
    JADE("Jade", 0xFF00A072, 0xFF3BBE8F),
    AZURE("Azure", 0xFF0097B1, 0xFF00B8D6),
    INDIGO("Indigo", 0xFF5480EB, 0xFF7BA1F7),
    ORCHID("Orchid", 0xFFA864CF, 0xFFC08BE0);

    /** The ink for this accent in the given theme. */
    fun ink(dark: Boolean): Color =
        if (this == CORAL) YantraInk.coral(dark)
        else Color(if (dark) darkArgb else lightArgb)

    companion object {
        /** Tolerant lookup for the persisted name — an unknown value falls back to the default. */
        fun from(name: String?): AccentColor =
            entries.firstOrNull { it.name == name } ?: CORAL
    }
}
