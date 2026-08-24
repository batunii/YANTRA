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
    override fun onReceive(context: Context, intent: Intent) {
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
