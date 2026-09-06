package ie.napkin.supertasks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import ie.napkin.supertasks.domain.FocusSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var openTarget by mutableStateOf<OpenTarget?>(null)

    /**
     * The notification permission, asked for on behalf of a session that is already running.
     *
     * The in-app starts ask for themselves, at the moment they start. A widget cannot: its key is an
     * `ActionCallback` with no activity behind it, so a session begun from the home screen has no
     * way to put a dialog on the screen — and a permission reset by a reinstall then leaves the
     * session running with its notification silently suppressed and nothing anywhere offering to
     * fix it. Registered as a field so it exists before onStart, which is where the ask happens.
     */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The session posted its notification into a permission it did not have; nothing else
            // will post it again on its own.
            if (granted) FocusSessionService.refresh(applicationContext)
        }

    /**
     * GitHub's Setup URL, coming back the other way.
     *
     * A custom scheme because this app hosts nothing: an https link would need a domain that exists
     * and an assetlinks.json on it, and there is neither.
     */
    private fun isInstallReturn(uri: android.net.Uri): Boolean =
        uri.scheme == "yantra" && uri.host == "installed"

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

    /**
     * Reclaims the foreground service for a session that is still running.
     *
     * A session can outlive the process, and when the process comes back on its own — `START_STICKY`
     * after a low-memory kill, a widget tap, a worker — Android refuses to let it start a foreground
     * service from the background. [FocusSessionService.sync] handles that by falling back to a
     * plain notification, so the session stays visible and correct; what it cannot do is decide when
     * the app is next allowed to try again.
     *
     * This is that moment, and without it the fallback was permanent: the collector that starts the
     * service only fires on [ie.napkin.supertasks.domain.FocusTimer] *transitions*, and a restore
     * produces none, so a session that survived a kill spent the rest of its life on a dismissible
     * notification with the process back in the cached pool it had just been killed out of.
     *
     * Idempotent — a service already in the foreground simply gets another `onStartCommand`.
     */
    override fun onStart() {
        super.onStart()
        val container = (application as App).container
        container.appScope.launch {
            container.timer.restoreIfNeeded()
            val live = container.timer.state.value?.takeIf { !it.isFinished }
            if (live != null) {
                FocusSessionService.sync(applicationContext, live = true, state = live)
                // Only with a session actually running, so this is never a question about nothing:
                // there is a notification owed right now and it is not being shown. Android stops
                // showing the dialog of its own accord once it has been refused twice, so this
                // cannot become a thing that asks every time the app is opened.
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    withContext(Dispatchers.Main) {
                        runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    }
                }
            }
        }
    }

    /**
     * Leaving the foreground refreshes the widgets and commits whatever is still batched.
     *
     * Android may kill the process from here without warning, and an edit that reached its file but
     * never became a commit would sit there until the app happened to be opened again.
     */
    override fun onStop() {
        super.onStop()
        val container = (application as App).container
        container.syncNow("app went to the background")
        container.appScope.launch {
            // A rebuild deferred by the last keystroke: harmless to lose, since the files are
            // written either way and the next launch reads them — but the widgets refresh from the
            // index a line below, and they should not show the title from before the last word.
            container.workspaces.flushIndexes()
            WidgetRefresh.refreshAll(applicationContext)
        }
    }

    private fun targetFrom(intent: Intent?): OpenTarget? {
        // GitHub's Setup URL, which it opens once the App has been installed. Landing back inside
        // the app *is* the last step: the sign-in screen re-asks whether the App is installed every
        // time it resumes, so arriving here is enough to finish. Without it the browser simply sits
        // on GitHub's "all set" page and the user has to find their way back and tap again.
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.let(::isInstallReturn) == true) {
            return OpenTarget(nodeId = null, isSmart = false, github = true)
        }
        if (intent?.getBooleanExtra(WidgetIntents.EXTRA_OPEN_FOCUS, false) == true) {
            return OpenTarget(nodeId = null, isSmart = false, focus = true)
        }
        val nodeId = intent?.getStringExtra(WidgetIntents.EXTRA_OPEN_NODE) ?: return null
        return OpenTarget(nodeId, intent.getBooleanExtra(WidgetIntents.EXTRA_OPEN_SMART, false))
    }
}
