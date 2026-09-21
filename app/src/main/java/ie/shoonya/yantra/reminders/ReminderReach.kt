package ie.shoonya.yantra.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * Whether a reminder set now could actually reach the person setting it.
 *
 * **The gap this closes.** Everything about a reminder worked except arriving. The app asked for
 * `POST_NOTIFICATIONS` when one was confirmed, stored it, armed the alarm, woke on time, validated
 * against the database — and then ended at `if (canNotify)` and posted nothing. On a phone where
 * notifications had never been granted, every reminder ever set was accepted and silently discarded,
 * and no screen in the app said anything other than the time it would arrive.
 *
 * **Three different things mean "off", and only one of them was being checked.** The runtime
 * permission is the one everybody thinks of. Notifications can also be switched off for the whole
 * app, and the Reminders channel can be switched off by itself — and in that last case the
 * permission check passes, `notify()` returns normally, and nothing appears. Anything asking "will
 * this work" has to ask all three, which is why this is one function rather than a check at each
 * call site.
 *
 * Exact-alarm access is deliberately *not* in here. Losing it makes a reminder late, not absent,
 * and [ReminderScheduler] already falls back to a window — telling somebody their reminder will not
 * arrive when it will merely be imprecise is its own kind of wrong.
 */
enum class ReminderReach {
    /** It will arrive. */
    Fine,

    /** 33+, and `POST_NOTIFICATIONS` has not been granted. The ask is still worth making. */
    NotPermitted,

    /** Notifications are off for the whole app, which only Settings can undo. */
    AppOff,

    /** The Reminders channel alone is off. The permission check passes and nothing appears. */
    ChannelOff,
    ;

    val willArrive: Boolean get() = this == Fine

    /**
     * What to tell somebody, in the sheet where they are choosing the reminder.
     *
     * Each one names the thing that is off rather than saying "notifications are disabled" three
     * times, because the three have three different remedies and only one of them is a tap inside
     * this app.
     *
     * **One line, and short enough to stay one.** The sheet it appears in cannot scroll — an M3
     * date picker nested in a vertical scroll lays itself out at unbounded height and takes the
     * whole sheet with it, which is why that column is deliberately fixed. So anything added here
     * is height taken from something below, and the something below is Cancel, Clear and Set. A
     * two-line explanation with an action underneath it pushed all three off the bottom of the
     * screen: the warning was perfectly legible and the sheet could no longer be used, which is a
     * worse bug than the one being reported.
     */
    val message: String get() = when (this) {
        Fine -> ""
        NotPermitted -> "Not allowed to notify — tap to allow"
        AppOff -> "Notifications are off for Yantra — tap to fix"
        ChannelOff -> "The Reminders notification is off — tap to fix"
    }

    companion object {
        /**
         * Asked of the system every time, never cached.
         *
         * All three answers change outside the app — in Settings, or in the permission dialog — and
         * a remembered one is wrong exactly when somebody has just gone and fixed it.
         */
        fun of(context: Context): ReminderReach {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return NotPermitted
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return AppOff
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(Reminders.CHANNEL_ID)
            // A null channel is not "off": on the first launch the check can run before App.onCreate
            // has created it, and saying a reminder will not arrive because we asked too early is a
            // worse answer than saying nothing.
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return ChannelOff
            }
            return Fine
        }

        /** Whether a reminder will be on time, as opposed to whether it will arrive at all. */
        fun onTime(context: Context): Boolean =
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }
}
