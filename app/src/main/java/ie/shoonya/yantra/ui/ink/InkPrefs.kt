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

/**
 * Where the kit lives on a tablet.
 *
 * A phone has no room for anything but the floating kit in its corner. A tablet has room for a bar
 * docked along an edge that holds the pens, the tools and the settings of whichever one is in hand
 * all at once — so changing a width or an ink is one tap, not two, and nothing opens over the page.
 */
enum class KitDock(val label: String) {
    BOTTOM("Docked at the bottom"),
    TOP("Docked at the top"),
    LEFT("Docked on the left"),
    RIGHT("Docked on the right"),
    FLOAT("Floating in the corner");

    val horizontal: Boolean get() = this == BOTTOM || this == TOP
}

object InkPrefs {
    private const val PREFS = "yantra_settings"
    private const val PEN_BUTTON = "ink_pen_button"
    private const val DOCK = "ink_kit_dock"

    /** Docked at the bottom on a tablet until chosen otherwise; always floating on a phone. */
    fun dock(context: Context, tablet: Boolean): KitDock {
        if (!tablet) return KitDock.FLOAT
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DOCK, null)
            ?.let { saved -> KitDock.entries.firstOrNull { it.name == saved } }
            ?: KitDock.BOTTOM
    }

    fun setDock(context: Context, value: KitDock) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DOCK, value.name).apply()
    }

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
