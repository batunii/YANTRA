package ie.napkin.supertasks.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** Re-renders placed widgets. Suspend — callers run on a container/app scope. */
object WidgetRefresh {

    suspend fun refreshListWidgets(context: Context) {
        YantraListWidget().updateAll(context)
        TodayWidget().updateAll(context)
    }

    /** Everything except QuickAdd (static). Called when the app leaves the foreground. */
    suspend fun refreshAll(context: Context) {
        refreshListWidgets(context)
        PomodoroWidget().updateAll(context)
    }
}
