package ie.napkin.supertasks.widget.actions

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import ie.napkin.supertasks.App
import ie.napkin.supertasks.widget.PomodoroWidget

/**
 * Pomodoro widget buttons. The AppContainer state collector re-renders the widget on every
 * timer transition; the trailing updateAll here is a defensive refresh for commands that
 * don't change the StateFlow (e.g. a stale tap).
 */
class PomodoroAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = (context.applicationContext as App).container
        val timer = container.timer
        timer.restoreIfNeeded()
        when (parameters[Command]) {
            PAUSE -> timer.pause()
            RESUME -> timer.resume()
            STOP -> timer.abandon()
            DISMISS -> timer.dismissFinished()
            START_LAST -> container.pomodoro.lastSession()?.let { lastSession ->
                timer.start(
                    lastSession.nodeId,
                    container.pomodoro.nodeTitle(lastSession.nodeId).orEmpty(),
                    25 * 60,
                )
            }
        }
        PomodoroWidget().updateAll(context)
    }

    companion object {
        val Command = ActionParameters.Key<String>("command")
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val STOP = "stop"
        const val DISMISS = "dismiss"
        const val START_LAST = "start_last"
    }
}
