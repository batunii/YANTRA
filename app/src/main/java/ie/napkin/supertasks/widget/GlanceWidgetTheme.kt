package ie.napkin.supertasks.widget

import android.content.Context
import android.content.res.Configuration
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as glanceColorProviders
import ie.napkin.supertasks.ui.theme.DEFAULT_HUE
import ie.napkin.supertasks.ui.theme.YantraColors
import ie.napkin.supertasks.ui.theme.loadThemeController
import ie.napkin.supertasks.ui.theme.materialScheme
import ie.napkin.supertasks.ui.theme.resolve
import ie.napkin.supertasks.ui.theme.yantraColors

/**
 * Widget colors from the persisted in-app theme (same prefs ThemeController uses). Returns
 * null when the user enabled Material You dynamic color — the caller then falls back to
 * GlanceTheme's built-in dynamic providers, which track the wallpaper on API 31+.
 * A user-fixed mode wins over the launcher's light/dark, so both providers get the same scheme.
 */
fun yantraGlanceColors(context: Context): ColorProviders? {
    val theme = loadThemeController(context)
    if (theme.dynamic) return null
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val scheme = materialScheme(yantraColors(theme.hue, theme.mode.resolve(systemDark)))
    return glanceColorProviders(light = scheme, dark = scheme)
}

/**
 * The palette's status voices (overdue / warning / done) for widget content. The hue argument is
 * irrelevant here on purpose — those three are pinned outside the hue engine (see [yantraColors]),
 * so this returns the same reds and greens whether or not the user is on Material You, and only
 * the light/dark decision matters. A user-fixed mode wins over the launcher's, matching
 * [yantraGlanceColors].
 */
fun yantraStatusColors(context: Context): YantraColors {
    val theme = loadThemeController(context)
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return yantraColors(DEFAULT_HUE, theme.mode.resolve(systemDark))
}
