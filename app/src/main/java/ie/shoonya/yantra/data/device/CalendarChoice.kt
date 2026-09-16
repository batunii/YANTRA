package ie.shoonya.yantra.data.device

import android.content.Context

/**
 * Which device calendars to draw, remembered on this device — CALENDAR_PLAN.md §5.
 *
 * **Device-local, deliberately.** A phone and a tablet signed into different accounts have different
 * answers, and provider ids are local numbers: writing this choice into the repository would make
 * one device's calendars appear on the other as ids that mean nothing there. It is the same argument
 * the plan makes for why reading is safe and writing was not — a calendar belongs to an account, not
 * to this workspace.
 *
 * The absence of a stored choice is not the same as choosing none. Until somebody has been asked,
 * [chosen] returns null and the caller falls back to whatever the owning app has ticked, which is
 * the answer a person means by "my calendar".
 */
class CalendarChoice(private val context: Context) {

    /** The chosen ids, or null when nobody has chosen yet. */
    fun chosen(): Set<Long>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return null
        return prefs.getString(KEY, null).orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
    }

    /**
     * What to actually query: the choice if there is one, else everything the owning app shows.
     *
     * Falling back to the provider's own visibility rather than to "all" matters on an account with
     * a dozen subscribed calendars — birthdays, holidays, a team's shared feed — most of which the
     * person has already turned off where they turned them off.
     */
    fun effective(source: DeviceCalendarSource): Set<Long> = chosen() ?: source.visibleCalendarIds()

    fun set(ids: Set<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, ids.joinToString(","))
            .apply()
    }

    /** Forgets the choice, so the owning app's own visibility decides again. */
    fun clear() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

private const val PREFS = "yantra_settings"
private const val KEY = "device_calendars"
