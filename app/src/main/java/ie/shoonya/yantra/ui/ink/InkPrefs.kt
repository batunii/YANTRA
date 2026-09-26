package ie.shoonya.yantra.ui.ink

import android.content.Context

/**
 * What the pen's side button does while it is held.
 *
 * Kept on the device, like the calendar's zoom: how you like your pen to behave is about your hand,
 * not about the work, and it has no business in a repository somebody else might open.
 */
enum class PenButton(val label: String, val tool: EditorTool?) {
    ERASER("Eraser", EditorTool.ERASE),
    LASSO("Lasso", EditorTool.LASSO),
    OFF("Nothing", null);

    /** The next choice, for a control that steps through them in place. */
    fun next(): PenButton = entries[(ordinal + 1) % entries.size]
}

object InkPrefs {
    private const val PREFS = "yantra_settings"
    private const val PEN_BUTTON = "ink_pen_button"

    fun penButton(context: Context): PenButton =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PEN_BUTTON, null)
            ?.let { saved -> PenButton.entries.firstOrNull { it.name == saved } }
            ?: PenButton.ERASER

    fun setPenButton(context: Context, value: PenButton) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PEN_BUTTON, value.name).apply()
    }
}

/** The kit key a canvas tool lights up. */
internal fun EditorTool.asMode(): InkMode = when (this) {
    EditorTool.DRAW -> InkMode.DRAW
    EditorTool.ERASE -> InkMode.ERASE
    EditorTool.LASSO -> InkMode.LASSO
    EditorTool.SHAPE -> InkMode.SHAPE
}
