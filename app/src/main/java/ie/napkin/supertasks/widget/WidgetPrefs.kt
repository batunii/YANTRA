package ie.napkin.supertasks.widget

import android.content.Context

/** Persists which node (list / smart list) each placed widget shows. */
object WidgetPrefs {
    private const val FILE = "list_widget_prefs"
    private fun nodeKey(id: Int) = "node_$id"
    private fun smartKey(id: Int) = "smart_$id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setBinding(context: Context, appWidgetId: Int, nodeId: String, isSmart: Boolean) {
        prefs(context).edit()
            .putString(nodeKey(appWidgetId), nodeId)
            .putBoolean(smartKey(appWidgetId), isSmart)
            .apply()
    }

    fun nodeId(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(nodeKey(appWidgetId), null)

    fun isSmart(context: Context, appWidgetId: Int): Boolean =
        prefs(context).getBoolean(smartKey(appWidgetId), false)

    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(nodeKey(appWidgetId)).remove(smartKey(appWidgetId)).apply()
    }
}
