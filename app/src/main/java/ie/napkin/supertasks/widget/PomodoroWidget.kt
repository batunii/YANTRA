package ie.napkin.supertasks.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import ie.napkin.supertasks.App
import ie.napkin.supertasks.MainActivity
import ie.napkin.supertasks.R
import ie.napkin.supertasks.domain.PomodoroTimer
import ie.napkin.supertasks.widget.actions.PomodoroAction

/**
 * Focus-timer widget. The running countdown is a RemoteViews Chronometer (count-down mode)
 * embedded via [AndroidRemoteViews]: the launcher process renders the ticks, so the widget
 * stays live with zero updates from us — even across force-stop. We re-render only on state
 * transitions (AppContainer collector) and let a WorkManager job finalize a session whose end
 * passes while the process is dead. Pause is process-bound (accepted caveat).
 */
class PomodoroWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as App).container
        container.timer.restoreIfNeeded()
        val initialLast = container.pomodoro.lastSession()
            ?.let { it.nodeId to (container.pomodoro.nodeTitle(it.nodeId) ?: "") }
        provideContent {
            // Observed, not snapshotted: a live Glance session ignores update() re-renders, so
            // pause/dismiss taps must recompose via the StateFlow (also closes the start race).
            val state by container.timer.state.collectAsState()
            val last by produceState(initialValue = initialLast, state) {
                if (state == null) {
                    value = container.pomodoro.lastSession()
                        ?.let { it.nodeId to (container.pomodoro.nodeTitle(it.nodeId) ?: "") }
                }
            }
            val custom = yantraGlanceColors(context)
            if (custom != null) GlanceTheme(colors = custom) { PomodoroContent(state, if (state == null) last else null) }
            else GlanceTheme { PomodoroContent(state, if (state == null) last else null) }
        }
    }
}

class PomodoroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PomodoroWidget()
}

@Composable
private fun PomodoroContent(state: PomodoroTimer.State?, last: Pair<String, String>?) {
    val context = LocalContext.current
    val openFocus = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(WidgetIntents.EXTRA_OPEN_FOCUS, true)
        data = Uri.parse("yantra://focus")
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(18.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(openFocus)),
    ) {
        when {
            state == null -> IdleContent(last)
            state.isFinished -> FinishedContent(state)
            state.isRunning -> RunningContent(state)
            else -> PausedContent(state)
        }
    }
}

@Composable
private fun HeaderText(text: String) {
    Text(
        text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

@Composable
private fun TitleText(text: String) {
    Text(
        text.ifBlank { "Focus" },
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
}

@Composable
private fun ActionChip(label: String, command: String) {
    Text(
        label,
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = GlanceModifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clickable(
                actionRunCallback<PomodoroAction>(
                    actionParametersOf(PomodoroAction.Command to command)
                )
            ),
    )
}

@Composable
private fun IdleContent(last: Pair<String, String>?) {
    HeaderText("FOCUS")
    Spacer(GlanceModifier.height(4.dp))
    TitleText(last?.second ?: "Pick a task in the app")
    Spacer(GlanceModifier.height(8.dp))
    if (last != null) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            ActionChip("Start 25m", PomodoroAction.START_LAST)
        }
    }
}

@Composable
private fun RunningContent(state: PomodoroTimer.State) {
    HeaderText("FOCUSING")
    Spacer(GlanceModifier.height(2.dp))
    TitleText(state.nodeTitle)
    // Launcher-rendered live countdown; no process needed while it ticks.
    val rv = RemoteViews(LocalContext.current.packageName, R.layout.widget_pomodoro_chrono).apply {
        setChronometerCountDown(R.id.pomo_chrono, true)
        setChronometer(
            R.id.pomo_chrono,
            SystemClock.elapsedRealtime() + state.remainingSecs * 1000L,
            null,
            true,
        )
    }
    AndroidRemoteViews(rv, modifier = GlanceModifier.fillMaxWidth().height(34.dp))
    Spacer(GlanceModifier.height(8.dp))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        ActionChip("Pause", PomodoroAction.PAUSE)
        Spacer(GlanceModifier.width(8.dp))
        ActionChip("Stop", PomodoroAction.STOP)
    }
}

@Composable
private fun PausedContent(state: PomodoroTimer.State) {
    HeaderText("PAUSED")
    Spacer(GlanceModifier.height(2.dp))
    TitleText(state.nodeTitle)
    val m = state.remainingSecs / 60
    val s = state.remainingSecs % 60
    Text(
        String.format("%d:%02d", m, s),
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold),
    )
    Spacer(GlanceModifier.height(8.dp))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        ActionChip("Resume", PomodoroAction.RESUME)
        Spacer(GlanceModifier.width(8.dp))
        ActionChip("Stop", PomodoroAction.STOP)
    }
}

@Composable
private fun FinishedContent(state: PomodoroTimer.State) {
    HeaderText("DONE")
    Spacer(GlanceModifier.height(2.dp))
    TitleText(state.nodeTitle)
    Text(
        "+1 focus session",
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
    )
    Spacer(GlanceModifier.height(8.dp))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        ActionChip("Dismiss", PomodoroAction.DISMISS)
    }
}
