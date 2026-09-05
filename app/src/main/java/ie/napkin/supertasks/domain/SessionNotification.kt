package ie.napkin.supertasks.domain

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ie.napkin.supertasks.App
import ie.napkin.supertasks.MainActivity
import ie.napkin.supertasks.R
import ie.napkin.supertasks.ui.theme.ThemeMode
import ie.napkin.supertasks.ui.theme.loadThemeController
import ie.napkin.supertasks.ui.theme.resolve
import ie.napkin.supertasks.widget.ListWidgetProvider
import kotlinx.coroutines.launch

/**
 * The running session, on the lock screen.
 *
 * A session is the one thing the app is doing *while you are not looking at it*, so it is the one
 * thing that has to be reachable from outside it. Stopping what you are working on should not cost
 * an unlock, a launch and a navigation — by the time that is done the thing you actually meant to
 * do has been displaced by the app.
 *
 * The clock is the system's, not ours: [NotificationCompat.Builder.setUsesChronometer] renders it
 * from the start time and ticks it itself. Posting a fresh notification every second to move two
 * digits would keep the process awake for the length of every session, and would be the one place
 * in the app where something animates at rest.
 *
 * Its own channel, at low importance. A reminder interrupts you on purpose; this is a status, and a
 * status that buzzes is a status you turn off.
 */
object SessionNotification {
    const val CHANNEL_ID = "session"
    const val ACTION_STOP = "ie.napkin.supertasks.action.SESSION_STOP"
    const val ACTION_DONE = "ie.napkin.supertasks.action.SESSION_DONE"

    /** Fixed: there is only ever one session, so its notification replaces itself. */
    private const val ID = 0x5E5

    fun show(context: Context, title: String, nodeId: String, elapsedSecs: Int) {
        val canNotify = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!canNotify) return

        // The same ink the app is drawn in — see the note in ReminderReceiver.
        val theme = loadThemeController(context)
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val accent = theme.accent.ink(theme.mode.resolve(systemDark) != ThemeMode.LIGHT)

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ListWidgetProvider.EXTRA_OPEN_NODE, nodeId)
            putExtra(ListWidgetProvider.EXTRA_OPEN_SMART, false)
            data = Uri.parse("yantra://open/$nodeId")
        }

        fun action(act: String, target: String) = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, SessionReceiver::class.java).apply {
                action = act
                data = Uri.parse("yantra://session/$target/$nodeId")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setColor(accent.toArgb())
            .setContentTitle(title.ifBlank { "Untitled" })
            .setContentText("Running")
            // Counting up from when it started, ticked by the system.
            .setWhen(System.currentTimeMillis() - elapsedSecs * 1000L)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            // The whole point: actionable without unlocking.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(0, "Stop", action(ACTION_STOP, "stop"))
            .addAction(0, "Done", action(ACTION_DONE, "done"))
            .build()
        NotificationManagerCompat.from(context).notify(ID, notification)
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}

/**
 * Stop and Done from the lock screen.
 *
 * Both go through the same places the in-app buttons do, so the ledger cannot tell which surface
 * ended a session. Done ends it as well as finishing the task, because a task you have just marked
 * finished is not still being worked on.
 */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as App).container
        val nodeId = container.running.timingId ?: return
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    // Stop ends the session and leaves the task marked — you stopped timing, not
                    // working. Done finishes the task, which clears the mark on its own.
                    SessionNotification.ACTION_STOP -> container.running.stopTiming()
                    SessionNotification.ACTION_DONE -> {
                        container.running.stopTiming()
                        container.nodes.setDone(nodeId, true)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
