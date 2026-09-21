package ie.shoonya.yantra.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

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

    /**
     * On 31 and 32 this is the user-revocable SCHEDULE_EXACT_ALARM; from 33 it is USE_EXACT_ALARM,
     * auto-granted to alarm apps and never withdrawn. Asking either way costs nothing and keeps
     * one answer to "may this fire on time".
     */
    fun canExact(): Boolean = am.canScheduleExactAlarms()

    fun schedule(nodeId: String, offsetMin: Int, atMillis: Long) {
        val pi = firePendingIntent(nodeId, offsetMin, atMillis)
        if (canExact()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, atMillis, 10 * 60_000L, pi)
        }
    }

    // Extras don't participate in Intent.filterEquals, so atMillis = 0 still matches. The offset
    // does participate, because it is in the data URI — which is the whole point of it being there.
    fun cancel(nodeId: String, offsetMin: Int) = am.cancel(firePendingIntent(nodeId, offsetMin, 0L))

    private fun firePendingIntent(nodeId: String, offsetMin: Int, atMillis: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).apply {
                action = Reminders.ACTION_FIRE
                // Per *reminder* uniqueness via the data URI — no requestCode hashing collisions.
                //
                // The offset is in the path because a task can carry several reminders, and two
                // PendingIntents that differ only in an extra are the same PendingIntent as far as
                // AlarmManager is concerned: `Intent.filterEquals` ignores extras. Keyed by node
                // alone, setting "30 minutes before" and "1 day before" would arm one alarm, fire
                // once, and lose the other without a word.
                data = Uri.parse("yantra://reminder/$nodeId/$offsetMin")
                putExtra(Reminders.EXTRA_NODE_ID, nodeId)
                putExtra(Reminders.EXTRA_OFFSET, offsetMin)
                putExtra(Reminders.EXTRA_AT, atMillis)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
