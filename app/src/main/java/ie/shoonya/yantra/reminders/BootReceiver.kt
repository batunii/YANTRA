package ie.shoonya.yantra.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ie.shoonya.yantra.App
import kotlinx.coroutines.launch

/**
 * Re-arms every future reminder after events that clear or skew AlarmManager state:
 * reboot, app update, time/timezone changes (the fire instants are absolute UTC), and exact-alarm
 * access being granted or taken away.
 *
 * **The last one is not cosmetic.** On 31 and 32 a person can revoke exact alarms in Settings at
 * any time, and Android's response is to cancel every exact alarm the app has already set. Without
 * this the reminders were simply gone — the scheduler already knows how to fall back to an
 * approximate window, and never got the chance to, because nothing told it to try again. Granting
 * it back is worth the same broadcast for the same reason: the alarms armed under the fallback
 * should become exact ones.
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
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
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
