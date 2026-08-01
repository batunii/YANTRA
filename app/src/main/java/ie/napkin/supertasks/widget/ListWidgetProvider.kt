package ie.napkin.supertasks.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import ie.napkin.supertasks.App
import ie.napkin.supertasks.MainActivity
import ie.napkin.supertasks.R
import ie.napkin.supertasks.data.db.NodeType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Home-screen widget: shows a chosen list's tasks. Configured via [WidgetConfigActivity]. */
class ListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, mgr, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPrefs.clear(context, it) }
    }

    companion object {
        const val EXTRA_OPEN_NODE = "ie.napkin.supertasks.OPEN_NODE"
        const val EXTRA_OPEN_SMART = "ie.napkin.supertasks.OPEN_SMART"

        private fun piFlags(mutable: Boolean): Int {
            var f = PendingIntent.FLAG_UPDATE_CURRENT
            f = f or if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_IMMUTABLE
            }
            return f
        }

        /** Re-render every placed widget (titles + row data). Call after data changes. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ListWidgetProvider::class.java))
            ids.forEach { updateWidget(context, mgr, it) }
        }

        fun updateWidget(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
            val rv = RemoteViews(context.packageName, R.layout.widget_list)
            val nodeId = WidgetPrefs.nodeId(context, appWidgetId)
            val isSmart = WidgetPrefs.isSmart(context, appWidgetId)

            if (nodeId == null) {
                rv.setTextViewText(R.id.widget_title, "Yantra list")
                rv.setTextViewText(R.id.widget_count, "")
                rv.setTextViewText(R.id.widget_empty, "Not set up")
                mgr.updateAppWidget(appWidgetId, rv)
                return
            }

            val container = (context.applicationContext as App).container
            val (title, summary) = runBlocking {
                val t = container.nodes.byId(nodeId)?.title?.ifBlank { "Untitled" } ?: "List"
                val summary = if (isSmart) {
                    val def = container.smartLists.defById(nodeId)
                    val n = def?.let { container.smartLists.query(it).first().size } ?: 0
                    if (n == 1) "1 task" else "$n tasks"
                } else {
                    val tasks = container.nodes.childrenOnce(nodeId).filter { it.type == NodeType.TASK }
                    "${tasks.count { it.done }} of ${tasks.size} done"
                }
                t to summary
            }
            rv.setTextViewText(R.id.widget_title, title)
            rv.setTextViewText(R.id.widget_count, summary)
            rv.setTextViewText(R.id.widget_empty, "Nothing here yet")

            // Bind the ListView to the collection service (unique per widget via the data uri).
            val serviceIntent = Intent(context, ListWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            rv.setRemoteAdapter(R.id.widget_list, serviceIntent)
            rv.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // Header → open the list/smart-list page.
            val headerIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_NODE, nodeId)
                putExtra(EXTRA_OPEN_SMART, isSmart)
                data = Uri.parse("yantra://open/$nodeId")
            }
            rv.setOnClickPendingIntent(
                R.id.widget_header,
                PendingIntent.getActivity(context, appWidgetId, headerIntent, piFlags(mutable = false)),
            )

            // Row taps → open that task (filled in per row by the factory).
            val templateIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            rv.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getActivity(
                    context, appWidgetId + 1_000_000, templateIntent, piFlags(mutable = true),
                ),
            )

            mgr.updateAppWidget(appWidgetId, rv)
            mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
        }
    }
}
