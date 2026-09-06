package ie.shoonya.yantra.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** Re-renders placed widgets. Suspend — callers run on a container/app scope. */
object WidgetRefresh {

    suspend fun refreshListWidgets(context: Context) {
        YantraListWidget().updateAll(context)
        TodayWidget().updateAll(context)
    }

    /**
     * Everything except QuickAdd (static). Called when the app leaves the foreground.
     *
     * Every widget that draws a session belongs here. [BhupuraWidget] was missing, and the cost was
     * not a stale label: its countdown is rendered by the launcher, so a widget nobody re-renders
     * does not freeze — it keeps counting, straight through zero into negative time.
     */
    suspend fun refreshAll(context: Context) {
        refreshListWidgets(context)
        FocusWidget().updateAll(context)
        BhupuraWidget().updateAll(context)
    }
}
