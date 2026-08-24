package ie.napkin.supertasks.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Holds the user's live theme choice, which is now only a [mode] (Dark / OLED / Light).
 *
 * The accent hue and the Material You toggle used to live here too. Both are gone: under the
 * design language's colour law coral means "the user's own effort", so it cannot be reassigned —
 * not by a slider, and not by the wallpaper. What the user still chooses is how dark the paper is.
 */
class ThemeController(mode: ThemeMode) {
    var mode by mutableStateOf(mode)

    fun update(context: Context, mode: ThemeMode = this.mode) {
        this.mode = mode
        persist(context, mode)
    }
}

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("LocalThemeController not provided")
}

private const val PREFS = "yantra_settings"
private const val KEY_MODE = "theme_mode"

fun loadThemeController(context: Context): ThemeController {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val mode = runCatching { ThemeMode.valueOf(p.getString(KEY_MODE, null) ?: ThemeMode.DARK.name) }
        .getOrDefault(ThemeMode.DARK)
    return ThemeController(mode)
}

private fun persist(context: Context, mode: ThemeMode) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_MODE, mode.name).apply()
}
