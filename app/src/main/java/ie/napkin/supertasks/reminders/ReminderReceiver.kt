package ie.napkin.supertasks.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ie.napkin.supertasks.App
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.MainActivity
import ie.napkin.supertasks.R
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.widget.ListWidgetProvider
import ie.napkin.supertasks.widget.WidgetRefresh
import kotlinx.coroutines.launch

/** Fires reminder notifications and handles their "Mark done" action. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nodeId = intent.getStringExtra(Reminders.EXTRA_NODE_ID) ?: return
        val container = (context.applicationContext as App).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    Reminders.ACTION_FIRE ->
                        fire(context, container, nodeId, intent.getLongExtra(Reminders.EXTRA_AT, 0L))
                    Reminders.ACTION_MARK_DONE -> {
                        container.nodes.setDone(nodeId, true)
                        NotificationManagerCompat.from(context).cancel(nodeId.hashCode())
                        WidgetRefresh.refreshListWidgets(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, container: AppContainer, nodeId: String, expectedAt: Long) {
        // Validate at delivery: the alarm may be stale (task finished/deleted, reminder moved
        // or cleared after arming) — the DB is the source of truth, not the alarm.
        val node = container.nodes.byId(nodeId) ?: return
        if (node.done || node.deletedAt != null) return
        val dueDefId = container.db.propertyDao().builtInDefIdByName(BuiltIns.DUE_NAME) ?: return
        val row = container.db.propertyDao().valuesForNodeOnce(nodeId)
            .firstOrNull { it.defId == dueDefId } ?: return
        val offsetMin = row.vNumber ?: return                 // reminder cleared since arming
        val at = row.vDate ?: return
        if (at - offsetMin.toLong() * 60_000L != expectedAt) return   // due/offset moved

        // Same contract as a widget tap: MainActivity resolves the extras into a deep link.
        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ListWidgetProvider.EXTRA_OPEN_NODE, nodeId)
            putExtra(ListWidgetProvider.EXTRA_OPEN_SMART, false)
            data = Uri.parse("yantra://open/$nodeId")
        }
        val done = Intent(context, ReminderReceiver::class.java).apply {
            action = Reminders.ACTION_MARK_DONE
            data = Uri.parse("yantra://done/$nodeId")
            putExtra(Reminders.EXTRA_NODE_ID, nodeId)
        }
        val notification = NotificationCompat.Builder(context, Reminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setContentTitle(node.title?.takeIf { it.isNotBlank() } ?: "Reminder")
            .setContentText("Reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, tap,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0, "Mark done",
                PendingIntent.getBroadcast(
                    context, 0, done,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        val canNotify = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canNotify) NotificationManagerCompat.from(context).notify(nodeId.hashCode(), notification)
    }
}
