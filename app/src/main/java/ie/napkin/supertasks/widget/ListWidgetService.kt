package ie.napkin.supertasks.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import ie.napkin.supertasks.App
import ie.napkin.supertasks.R
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Serves the task rows of the bound list to a widget's ListView. */
class ListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ListRemoteViewsFactory(applicationContext, intent)
}

private class ListRemoteViewsFactory(
    private val context: Context,
    intent: Intent,
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private var items: List<NodeEntity> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val nodeId = WidgetPrefs.nodeId(context, appWidgetId)
        if (nodeId == null) {
            items = emptyList()
            return
        }
        val container = (context.applicationContext as App).container
        items = runBlocking {
            if (WidgetPrefs.isSmart(context, appWidgetId)) {
                val def = container.smartLists.defById(nodeId)
                if (def != null) container.smartLists.query(def).first() else emptyList()
            } else {
                container.nodes.childrenOnce(nodeId).filter { it.type == NodeType.TASK }
            }
        }
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val node = items[position]
        val row = RemoteViews(context.packageName, R.layout.widget_row)
        row.setTextViewText(R.id.widget_row_title, node.title?.ifBlank { "Untitled" } ?: "Untitled")
        row.setImageViewResource(
            R.id.widget_row_check,
            if (node.done) R.drawable.ic_widget_check_done else R.drawable.ic_widget_check_idle,
        )
        row.setTextColor(
            R.id.widget_row_title,
            ContextCompat.getColor(context, if (node.done) R.color.widget_dim else R.color.widget_text),
        )
        // Tapping a row opens that task in the app (filled into the ListView's template).
        row.setOnClickFillInIntent(
            R.id.widget_row_root,
            Intent().putExtra(ListWidgetProvider.EXTRA_OPEN_NODE, node.id),
        )
        return row
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
    override fun onDestroy() { items = emptyList() }
}
