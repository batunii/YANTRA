package ie.napkin.supertasks.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Holds the user's live theme choice — one [hue] (0..360) and a [mode] (Dark / OLED / Light).
 * Backed by Compose state so changing either re-derives the whole palette instantly. Persisted
 * to SharedPreferences via [loadThemeController] / [persist].
 */
class ThemeController(mode: ThemeMode, hue: Float, dynamic: Boolean) {
    var mode by mutableStateOf(mode)
    var hue by mutableFloatStateOf(hue)

    /** Material You: derive the palette hue from the wallpaper instead of [hue] (API 31+). */
    var dynamic by mutableStateOf(dynamic)

    fun update(
        context: Context,
        mode: ThemeMode = this.mode,
        hue: Float = this.hue,
        dynamic: Boolean = this.dynamic,
    ) {
        this.mode = mode
        this.hue = hue
        this.dynamic = dynamic
        persist(context, mode, hue, dynamic)
    }
}

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("LocalThemeController not provided")
}

private const val PREFS = "yantra_settings"
private const val KEY_MODE = "theme_mode"
private const val KEY_HUE = "hue_degrees"
private const val KEY_DYNAMIC = "dynamic_color"

fun loadThemeController(context: Context): ThemeController {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val mode = runCatching { ThemeMode.valueOf(p.getString(KEY_MODE, null) ?: ThemeMode.DARK.name) }
        .getOrDefault(ThemeMode.DARK)
    val hue = p.getFloat(KEY_HUE, DEFAULT_HUE)
    val dynamic = p.getBoolean(KEY_DYNAMIC, false)
    return ThemeController(mode, hue, dynamic)
}

private fun persist(context: Context, mode: ThemeMode, hue: Float, dynamic: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_MODE, mode.name).putFloat(KEY_HUE, hue).putBoolean(KEY_DYNAMIC, dynamic).apply()
}
