package ie.shoonya.yantra.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ie.shoonya.yantra.App
import ie.shoonya.yantra.AppContainer
import ie.shoonya.yantra.MainActivity
import ie.shoonya.yantra.R
import ie.shoonya.yantra.data.db.BuiltIns
import ie.shoonya.yantra.ui.theme.ThemeMode
import ie.shoonya.yantra.ui.theme.loadThemeController
import ie.shoonya.yantra.ui.theme.resolve
import ie.shoonya.yantra.widget.ListWidgetProvider
import ie.shoonya.yantra.widget.WidgetRefresh
import kotlinx.coroutines.launch
import ie.shoonya.yantra.data.format.Links

/** Fires reminder notifications and handles their "Mark done" action. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nodeId = intent.getStringExtra(Reminders.EXTRA_NODE_ID) ?: return
        val container = (context.applicationContext as App).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    Reminders.ACTION_FIRE -> fire(
                        context, container, nodeId,
                        offsetMin = intent.getIntExtra(Reminders.EXTRA_OFFSET, 0),
                        expectedAt = intent.getLongExtra(Reminders.EXTRA_AT, 0L),
                    )
                    Reminders.ACTION_MARK_DONE -> {
                        container.nodes.setDone(nodeId, true)
                        // Every reminder this task has already posted, not just the one that was
                        // tapped. A task with a warning a day before and another half an hour
                        // before can have both on screen, and finishing it from one of them used to
                        // leave the other sitting there asking about work that is done.
                        cancelAllFor(context, nodeId)
                        WidgetRefresh.refreshListWidgets(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(
        context: Context,
        container: AppContainer,
        nodeId: String,
        offsetMin: Int,
        expectedAt: Long,
    ) {
        // Validate at delivery: the alarm may be stale (task finished/deleted, reminder moved
        // or cleared after arming) — the DB is the source of truth, not the alarm.
        val node = container.nodes.byId(nodeId) ?: return
        if (node.done || node.deletedAt != null) return

        // Two kinds of thing arm an alarm, and each validates against its own row. Without this an
        // event's reminder was armed and then dropped in silence: the Due lookup below found no
        // property value for it and returned, so the alarm fired into nothing.
        val event = container.db.eventDao().byId(nodeId)
        val isEvent = event != null
        // A sitting is a reminder about a *task* — CALENDAR_PLAN.md §13. It has no words of its own,
        // it deep-links to the thing it is time for, and it says nothing if that thing has since
        // been finished or thrown away: an alarm for work already done is the worst kind.
        val forTask = event?.forNodeId?.let { container.nodes.byId(it) }
        if (event?.forNodeId != null && (forTask == null || forTask.done || forTask.deletedAt != null)) return
        if (isEvent) {
            if (event!!.cancelled) return                     // an absence has nothing to announce
            val offsetMin = event.reminderMin ?: return       // reminder cleared since arming
            if (event.startUtc - offsetMin.toLong() * 60_000L != expectedAt) return
        } else {
            val dueDefId = container.db.propertyDao().builtInDefIdByName(BuiltIns.DUE_NAME) ?: return
            val row = container.db.propertyDao().valuesForNodeOnce(nodeId)
                .firstOrNull { it.defId == dueDefId } ?: return
            // This reminder specifically, not "a reminder". A task can carry several, and removing
            // the one-day warning must not leave the half-hour one firing on its behalf — nor the
            // other way round, which is what checking only that *some* offset existed would allow.
            val offsets = ie.shoonya.yantra.data.format.Reminders.parse(row.vReminders)
            if (offsetMin !in offsets) return                 // this reminder was removed
            val at = row.vDate ?: return
            if (at - offsetMin.toLong() * 60_000L != expectedAt) return   // due moved
        }

        // Same contract as a widget tap: MainActivity resolves the extras into a deep link. A
        // sitting opens the task rather than itself — a bare hour with nothing in it is not
        // somewhere to be sent.
        val opens = forTask?.id ?: nodeId
        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ListWidgetProvider.EXTRA_OPEN_NODE, opens)
            putExtra(ListWidgetProvider.EXTRA_OPEN_SMART, false)
            data = Uri.parse("yantra://open/$opens")
        }
        val done = Intent(context, ReminderReceiver::class.java).apply {
            action = Reminders.ACTION_MARK_DONE
            data = Uri.parse("yantra://done/$nodeId")
            putExtra(Reminders.EXTRA_NODE_ID, nodeId)
        }
        // The bhupura in the status bar is tinted by the system from this colour, so a reminder
        // arrives in whatever ink the user chose for effort — the notification is the app speaking
        // from outside itself, and it should not be the one place that still says coral.
        val theme = loadThemeController(context)
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val accent = theme.accent.ink(theme.mode.resolve(systemDark) != ThemeMode.LIGHT)

        val notification = NotificationCompat.Builder(context, Reminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setColor(accent.toArgb())
            .setColorized(false)
            // A notification cannot render a link, so it renders what the link says.
            .setContentTitle(
                Links.plain((forTask ?: node).title.orEmpty()).ifBlank { "Reminder" }
            )
            .setContentText(
                when {
                    // What the bar is saying at the same moment, in the same words.
                    forTask != null -> "It is time"
                    isEvent -> eventWhen(event!!)
                    // How much warning this one is, because it is no longer the only one. Two
                    // notifications for the same task reading "Reminder" are indistinguishable,
                    // and the whole reason for setting two is that they mean different things.
                    else -> leadTime(offsetMin)
                }
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, tap,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            // An event has no done state — it happens, it is not finished — so it gets no button
            // that claims otherwise. Offering one would write `done` onto a node whose line has no
            // checkbox to show it.
            .apply {
                if (!isEvent) addAction(
                    0, "Mark done",
                    PendingIntent.getBroadcast(
                        context, 0, done,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
            .build()
        // The last thing that can go wrong, and it used to go wrong in silence.
        //
        // Everything above here succeeded: the alarm was armed, the phone woke on time, the task
        // was still open and the instant still matched. Posting is the only step left, and on a
        // phone where notifications are off it does nothing at all — so a reminder that was set,
        // stored and delivered simply never appeared, and left nothing behind to explain it. The
        // report that reaches us is "my reminder did not go off", which is indistinguishable from
        // a scheduling bug and sends anybody looking at the wrong half of this file.
        //
        // Three things mean off and the permission was only one of them — see ReminderReach.
        val reach = ReminderReach.of(context)
        if (!reach.willArrive) {
            ie.shoonya.yantra.Trace.warn(
                "reminder",
                "fired for ${ie.shoonya.yantra.Trace.id(nodeId)} and could not be shown: $reach",
            )
            return
        }
        // Tagged by node, numbered by offset. Two reminders on one task are two notifications —
        // keyed by node alone, the half-hour warning would silently replace the one-day warning
        // that is still on screen — and the tag is what lets "Mark done" clear every one of them.
        NotificationManagerCompat.from(context).notify(nodeId, offsetMin, notification)
    }

    /**
     * Every notification this task has posted, whichever reminder posted it.
     *
     * There is no "cancel by tag" in the platform, so the active set is read back and filtered.
     * Cheap, and only ever on an explicit tap.
     */
    private fun cancelAllFor(context: Context, nodeId: String) {
        val manager = NotificationManagerCompat.from(context)
        runCatching {
            manager.activeNotifications
                .filter { it.tag == nodeId }
                .forEach { manager.cancel(it.tag, it.id) }
        }.onFailure { manager.cancel(nodeId, 0) }
    }

    /**
     * "in 30 minutes", for a reminder that is not the only one on its task.
     *
     * Rounded to the unit it was chosen in rather than reported exactly: nobody picks "1440 minutes
     * before", they pick a day, and a notification that says 1440 is the app showing its storage.
     */
    private fun leadTime(offsetMin: Int): String = when {
        offsetMin <= 0 -> "It is time"
        offsetMin % 1440 == 0 -> "in ${offsetMin / 1440} ${if (offsetMin == 1440) "day" else "days"}"
        offsetMin % 60 == 0 -> "in ${offsetMin / 60} ${if (offsetMin == 60) "hour" else "hours"}"
        else -> "in $offsetMin minutes"
    }

    /** "14:00" for a timed event, or the plain word for one that owns the whole day. */
    private fun eventWhen(e: ie.shoonya.yantra.data.db.EventEntity): String {
        if (e.allDay) return "All day"
        val start = runCatching { java.time.LocalDateTime.parse(e.startLocal) }.getOrNull()
            ?: return "Reminder"
        return start.toLocalTime().toString()
    }
}
