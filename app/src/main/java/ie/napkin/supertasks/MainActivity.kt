package ie.napkin.supertasks

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import ie.napkin.supertasks.ui.AppNav
import ie.napkin.supertasks.ui.OpenTarget
import ie.napkin.supertasks.ui.theme.LocalThemeController
import ie.napkin.supertasks.ui.theme.SuperTasksTheme
import ie.napkin.supertasks.ui.theme.loadThemeController
import ie.napkin.supertasks.ui.theme.resolve
import ie.napkin.supertasks.ui.theme.yantraColors
import ie.napkin.supertasks.widget.WidgetIntents
import ie.napkin.supertasks.widget.WidgetRefresh
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openTarget by mutableStateOf<OpenTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openTarget = targetFrom(intent)
        val theme = loadThemeController(this)
        // Pre-first-frame window background in the stored mode's page color — the XML theme only
        // knows the system night mode, which can disagree with the stored mode (launch flash).
        // With dynamic color on, the stored hue's near-neutral ground is indistinguishable.
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val pageColor = yantraColors(theme.mode.resolve(systemDark), theme.accent).page
        window.setBackgroundDrawable(ColorDrawable(pageColor.toArgb()))
        setContent {
            SuperTasksTheme(mode = theme.mode, accent = theme.accent) {
                CompositionLocalProvider(LocalThemeController provides theme) {
                    AppNav(openTarget = openTarget, onOpenConsumed = { openTarget = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetFrom(intent)?.let { openTarget = it }
    }

    /** Keep placed widgets in sync with edits made in-app when we leave the foreground. */
    override fun onStop() {
        super.onStop()
        val container = (application as App).container
        container.appScope.launch { WidgetRefresh.refreshAll(applicationContext) }
    }

    private fun targetFrom(intent: Intent?): OpenTarget? {
        if (intent?.getBooleanExtra(WidgetIntents.EXTRA_OPEN_FOCUS, false) == true) {
            return OpenTarget(nodeId = null, isSmart = false, focus = true)
        }
        val nodeId = intent?.getStringExtra(WidgetIntents.EXTRA_OPEN_NODE) ?: return null
        return OpenTarget(nodeId, intent.getBooleanExtra(WidgetIntents.EXTRA_OPEN_SMART, false))
    }
}
