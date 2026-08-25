package ie.napkin.supertasks.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Keeps the home-screen icon on the same ink as the app.
 *
 * A launcher icon is a static resource, resolved in the *launcher's* process from the manifest —
 * there is no API to tint one from app state, and the `<monochrome>` layer is no help either
 * because on Android 13+ that is tinted from the user's wallpaper rather than from anything we
 * choose. The one supported mechanism is a set of `<activity-alias>` entries, each carrying its own
 * icon, with exactly one enabled.
 *
 * Two rules make that safe, and both are load-bearing:
 *
 *  - **Enable before disabling.** The enabled aliases are the app's only LAUNCHER entries. Disable
 *    first and there is a window — short, but real, and it survives a crash — in which the app has
 *    no icon and cannot be opened from the launcher at all.
 *  - **Never write a state that already holds.** Every enable/disable makes the launcher drop and
 *    re-add the entry, which is visible. [apply] returns early when the right alias is already
 *    live, so a cold start costs one cheap query and nothing else.
 *
 * The cost that cannot be designed away: a home-screen *shortcut* pinned to the old alias points at
 * a component that no longer resolves, so it may need re-adding. Widgets are unaffected — they bind
 * to their own provider components, and everything else launches MainActivity by class.
 */
object LauncherIcon {

    /** Alias declared in the manifest for each accent. Names are API — do not rename casually. */
    private fun aliasFor(accent: AccentColor): String = when (accent) {
        AccentColor.CORAL -> "ie.napkin.supertasks.LaunchCoral"
        AccentColor.JADE -> "ie.napkin.supertasks.LaunchJade"
        AccentColor.AZURE -> "ie.napkin.supertasks.LaunchAzure"
        AccentColor.INDIGO -> "ie.napkin.supertasks.LaunchIndigo"
        AccentColor.ORCHID -> "ie.napkin.supertasks.LaunchOrchid"
    }

    /**
     * Point the launcher at [accent]'s icon. Safe to call from anywhere and on any thread —
     * a no-op when nothing needs to change, which is the common case on startup.
     */
    fun apply(context: Context, accent: AccentColor) {
        val pm = context.packageManager
        val wanted = ComponentName(context.packageName, aliasFor(accent))

        // COMPONENT_ENABLED_STATE_DEFAULT means "whatever the manifest says", which for the coral
        // alias is enabled and for the rest is disabled — so it is not a synonym for "off".
        fun isLive(name: ComponentName, isManifestDefault: Boolean): Boolean =
            when (pm.getComponentEnabledSetting(name)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                else -> isManifestDefault
            }

        if (isLive(wanted, accent == AccentColor.CORAL)) return

        // Enable first: these are the app's only launcher entries, and a gap between the two calls
        // is a moment with no way in.
        pm.setComponentEnabledSetting(
            wanted,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AccentColor.entries.filter { it != accent }.forEach { other ->
            val name = ComponentName(context.packageName, aliasFor(other))
            if (!isLive(name, other == AccentColor.CORAL)) return@forEach
            pm.setComponentEnabledSetting(
                name,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
