package ie.napkin.supertasks.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Holds the user's live theme choice: how dark the paper is ([mode]) and which ink means "your
 * effort" ([accent]).
 *
 * The Material You toggle used to live here too and is not coming back — a wallpaper-derived hue
 * could land anywhere, including on priority's crimson or amber, which is exactly what the colour
 * law forbids. [accent] is safe where a wallpaper is not, because it is a closed set that cannot
 * reach the priority band (see [AccentColor]).
 */
class ThemeController(mode: ThemeMode, accent: AccentColor = AccentColor.CORAL) {
    var mode by mutableStateOf(mode)
    var accent by mutableStateOf(accent)

    fun update(context: Context, mode: ThemeMode = this.mode, accent: AccentColor = this.accent) {
        this.mode = mode
        this.accent = accent
        persist(context, mode, accent)
    }
}

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("LocalThemeController not provided")
}

private const val PREFS = "yantra_settings"
private const val KEY_MODE = "theme_mode"
private const val KEY_ACCENT = "theme_accent"

fun loadThemeController(context: Context): ThemeController {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val mode = runCatching { ThemeMode.valueOf(p.getString(KEY_MODE, null) ?: ThemeMode.DARK.name) }
        .getOrDefault(ThemeMode.DARK)
    return ThemeController(mode, AccentColor.from(p.getString(KEY_ACCENT, null)))
}

private fun persist(context: Context, mode: ThemeMode, accent: AccentColor) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_MODE, mode.name)
        .putString(KEY_ACCENT, accent.name)
        .apply()
}
