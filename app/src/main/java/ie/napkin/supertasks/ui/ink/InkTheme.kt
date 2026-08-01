package ie.napkin.supertasks.ui.ink

import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import ie.napkin.supertasks.data.ink.StrokeCodec

/** A persisted stroke paired with its row id, so the editor can erase a specific stroke. */
data class StrokeItem(val id: String, val stroke: Stroke)

/**
 * Ink is theme-native: the default pen is "ink" (black on light paper, white on dark paper).
 * Strokes are STORED with whatever color they were drawn in; at render time the two ink
 * colors swap with the theme, so a note written in light mode reads naturally in dark mode
 * and vice versa. Accent colors (coral, blue, …) pass through untouched.
 */
object InkTheme {
    const val BLACK_INK = 0xFF1A2150L   // indigo ink (light paper)
    const val WHITE_INK = 0xFFEDEBFFL   // starlight ink (night paper)

    const val PAPER_LIGHT = 0xFFFFFFFF.toInt()
    const val PAPER_DARK = 0xFF0E1435.toInt()

    fun paper(dark: Boolean): Int = if (dark) PAPER_DARK else PAPER_LIGHT

    fun defaultPen(dark: Boolean): Long = if (dark) WHITE_INK else BLACK_INK

    fun displayColor(stored: Int, dark: Boolean): Int = when {
        dark && stored == BLACK_INK.toInt() -> WHITE_INK.toInt()
        !dark && stored == WHITE_INK.toInt() -> BLACK_INK.toInt()
        else -> stored
    }

    /** Theme-swap a single stroke's ink color (accent colors pass through). */
    private fun remap(stroke: Stroke, dark: Boolean): Stroke {
        val mapped = displayColor(stroke.brush.colorIntArgb, dark)
        return if (mapped == stroke.brush.colorIntArgb) stroke
        else Stroke(
            brush = Brush.createWithColorIntArgb(
                family = stroke.brush.family,
                colorIntArgb = mapped,
                size = stroke.brush.size,
                epsilon = stroke.brush.epsilon,
            ),
            inputs = stroke.inputs,
        )
    }

    // Highlighters always sit behind pen/marker strokes, even when drawn later. sortedBy is
    // stable, so drawing order is preserved within each layer.
    private fun layerKey(stroke: Stroke): Int = if (StrokeCodec.isHighlighter(stroke)) 0 else 1

    fun displayStrokes(strokes: List<Stroke>, dark: Boolean): List<Stroke> =
        strokes.sortedBy { layerKey(it) }.map { remap(it, dark) }

    /** Like [displayStrokes] but preserves each stroke's id for the eraser. */
    fun displayItems(items: List<StrokeItem>, dark: Boolean): List<StrokeItem> =
        items.sortedBy { layerKey(it.stroke) }.map { StrokeItem(it.id, remap(it.stroke, dark)) }
}
