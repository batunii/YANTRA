package ie.napkin.supertasks.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Thin AlarmManager wrapper. Exact delivery matters for reminders, so this prefers
 * [AlarmManager.setExactAndAllowWhileIdle]; on API 31/32 the user can revoke
 * SCHEDULE_EXACT_ALARM, in which case a 10-minute [AlarmManager.setWindow] keeps reminders
 * merely approximate instead of silently dropping them (API 33+ uses USE_EXACT_ALARM,
 * auto-granted for reminder apps). Doze rate-limits while-idle alarms (~1/9min per app) —
 * fine for user reminders; aggressive OEM battery managers can still delay delivery.
 */
class ReminderScheduler(private val context: Context) {
    private val am = context.getSystemService(AlarmManager::class.java)

    fun canExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    fun schedule(nodeId: String, atMillis: Long) {
        val pi = firePendingIntent(nodeId, atMillis)
        if (canExact()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, atMillis, 10 * 60_000L, pi)
        }
    }

    // Extras don't participate in Intent.filterEquals, so atMillis = 0 still matches.
    fun cancel(nodeId: String) = am.cancel(firePendingIntent(nodeId, 0L))

    private fun firePendingIntent(nodeId: String, atMillis: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).apply {
                action = Reminders.ACTION_FIRE
                // Per-node uniqueness via the data URI — no requestCode hashing collisions.
                data = Uri.parse("yantra://reminder/$nodeId")
                putExtra(Reminders.EXTRA_NODE_ID, nodeId)
                putExtra(Reminders.EXTRA_AT, atMillis)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
