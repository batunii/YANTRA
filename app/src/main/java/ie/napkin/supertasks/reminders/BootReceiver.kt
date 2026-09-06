package ie.napkin.supertasks.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ie.napkin.supertasks.App
import kotlinx.coroutines.launch

/**
 * Re-arms every future reminder after events that clear or skew AlarmManager state:
 * reboot, app update, and time/timezone changes (the fire instants are absolute UTC).
 */
class BootReceiver : BroadcastReceiver() {

    /**
     * All four are protected broadcasts and this receiver is not exported, so nothing else can
     * reach it today. The check is here so that stays true by construction rather than by two
     * facts in the manifest happening to hold: an action added to the filter later has to be added
     * here too before it can silently rebuild every alarm in the app.
     */
    private val rearms = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in rearms) return
        val container = (context.applicationContext as App).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.reminders.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}
