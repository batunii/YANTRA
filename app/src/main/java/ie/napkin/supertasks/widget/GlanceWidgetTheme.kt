package ie.napkin.supertasks.widget

import android.content.Context
import android.content.res.Configuration
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as glanceColorProviders
import ie.napkin.supertasks.ui.theme.YantraColors
import ie.napkin.supertasks.ui.theme.loadThemeController
import ie.napkin.supertasks.ui.theme.materialScheme
import ie.napkin.supertasks.ui.theme.resolve
import ie.napkin.supertasks.ui.theme.yantraColors

/**
 * Widget colors from the persisted in-app theme (same prefs ThemeController uses). Never null now:
 * there is no Material You escape hatch, because the widget has to speak the same colour law as the
 * app — a wallpaper-tinted bindu would say "your effort" in a hue that means nothing.
 * A user-fixed mode wins over the launcher's light/dark, so both providers get the same scheme.
 */
fun yantraGlanceColors(context: Context): ColorProviders? {
    val theme = loadThemeController(context)
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val scheme = materialScheme(yantraColors(theme.mode.resolve(systemDark)))
    return glanceColorProviders(light = scheme, dark = scheme)
}

/**
 * The palette's meaning-bearing inks (priority crimson/amber, effort coral) for widget content.
 * Only the light/dark decision matters — every hue is fixed by the colour law. A user-fixed mode
 * wins over the launcher's, matching [yantraGlanceColors].
 */
fun yantraStatusColors(context: Context): YantraColors {
    val theme = loadThemeController(context)
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return yantraColors(theme.mode.resolve(systemDark))
}
