package ie.shoonya.yantra.ui.ink

import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import ie.shoonya.yantra.data.ink.StrokeCodec

/** A persisted stroke paired with its row id, so the editor can erase a specific stroke. */
data class StrokeItem(val id: String, val stroke: Stroke)

/**
 * Ink is theme-native: the default pen is "ink" (black on light paper, white on dark paper).
 * Strokes are STORED with whatever color they were drawn in; at render time the two ink
 * colors swap with the theme, so a note written in light mode reads naturally in dark mode
 * and vice versa. Accent colors pass through untouched.
 */
object InkTheme {
    // Warm graphite and warm off-white, matching the app's paper. These were an indigo/starlight
    // pair from the old palette, which meant a sketch sat on visibly bluer paper than the page it
    // was embedded in — a hand-drawn stroke is the one place a mismatched ground shows immediately.
    const val BLACK_INK = 0xFF23211CL   // graphite ink (light paper)
    const val WHITE_INK = 0xFFF1EEE7L   // chalk ink (night paper)



    fun defaultPen(dark: Boolean): Long = if (dark) WHITE_INK else BLACK_INK

    fun displayColor(stored: Int, dark: Boolean): Int = when {
        dark && stored == BLACK_INK.toInt() -> WHITE_INK.toInt()
        !dark && stored == WHITE_INK.toInt() -> BLACK_INK.toInt()
        else -> stored
    }

    /**
     * Theme-swap a single stroke's ink color (accent colors pass through).
     *
     * **The same recoloured stroke every time**, remembered against the original. The screen asks
     * again whenever a stroke is added, and a fresh copy of every other stroke each time made each
     * of them a stranger to [StrokeCodec]'s per-stroke geometry — so every stroke drawn in the
     * other theme's ink was measured again, point by point, on every new stroke. The copy also
     * reuses the original's mesh: the shape does not depend on the colour.
     */
    private fun remap(stroke: Stroke, dark: Boolean): Stroke {
        val mapped = displayColor(stroke.brush.colorIntArgb, dark)
        if (mapped == stroke.brush.colorIntArgb) return stroke
        val memo = if (dark) toDark else toLight
        synchronized(memo) { memo[stroke]?.let { return it } }
        return Stroke(
            brush = Brush.createWithColorIntArgb(
                family = stroke.brush.family,
                colorIntArgb = mapped,
                size = stroke.brush.size,
                epsilon = stroke.brush.epsilon,
            ),
            inputs = stroke.inputs,
            shape = stroke.shape,
        ).also {
            StrokeCodec.shareGeometry(stroke, it)
            synchronized(memo) { memo[stroke] = it }
        }
    }

    // Weak and by identity, like StrokeCodec's: a stroke that is gone takes its copy with it.
    private val toDark = java.util.WeakHashMap<Stroke, Stroke>()
    private val toLight = java.util.WeakHashMap<Stroke, Stroke>()

    // Highlighters always sit behind pen/marker strokes, even when drawn later. sortedBy is
    // stable, so drawing order is preserved within each layer.
    private fun layerKey(stroke: Stroke): Int = if (StrokeCodec.isHighlighter(stroke)) 0 else 1

    fun displayStrokes(strokes: List<Stroke>, dark: Boolean): List<Stroke> =
        strokes.sortedBy { layerKey(it) }.map { remap(it, dark) }

    /** Like [displayStrokes] but preserves each stroke's id for the eraser. */
    fun displayItems(items: List<StrokeItem>, dark: Boolean): List<StrokeItem> =
        items.sortedBy { layerKey(it.stroke) }.map { StrokeItem(it.id, remap(it.stroke, dark)) }
}
