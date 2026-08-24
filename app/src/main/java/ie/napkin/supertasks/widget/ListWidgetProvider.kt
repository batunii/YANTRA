package ie.napkin.supertasks.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Receiver for the configurable list widget. The class name predates the Glance migration and
 * must not change: launchers bind placed widgets by ComponentName, so keeping it lets existing
 * placements survive the update (their [WidgetPrefs] bindings are keyed by appWidgetId and
 * carry over untouched).
 */
class ListWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = YantraListWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetPrefs.clear(context, it) }
    }

    companion object {
        // Kept as aliases — MainActivity and ReminderReceiver read these.
        const val EXTRA_OPEN_NODE = WidgetIntents.EXTRA_OPEN_NODE
        const val EXTRA_OPEN_SMART = WidgetIntents.EXTRA_OPEN_SMART
    }
}
