package ie.napkin.supertasks.widget.actions

import android.content.Context
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import ie.napkin.supertasks.App
import ie.napkin.supertasks.widget.WidgetRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget checkbox tap. Runs in a broadcast-woken process, so this is the whole background
 * write path: build the container, write, re-render every list widget.
 */
class ToggleDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val nodeId = parameters[NodeId] ?: return
        val container = (context.applicationContext as App).container
        val nowDone = !(parameters[Done] ?: false)
        val title = container.nodes.byId(nodeId)?.title?.takeIf { it.isNotBlank() }
        container.nodes.setDone(nodeId, nowDone)
        WidgetRefresh.refreshListWidgets(context)
        // Completing from the widget makes the row vanish. Without a word back, that reads as
        // "did that work?" — so the action confirms itself, and names what it acted on.
        val message = when {
            nowDone && title != null -> "$title completed"
            nowDone -> "Task completed"
            title != null -> "$title reopened"
            else -> "Task reopened"
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        val NodeId = ActionParameters.Key<String>("nodeId")
        val Done = ActionParameters.Key<Boolean>("done")
    }
}
