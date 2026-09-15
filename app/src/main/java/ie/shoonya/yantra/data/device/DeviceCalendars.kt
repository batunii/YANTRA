package ie.shoonya.yantra.data.device

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One of the account's calendars, as the picker lists it. */
data class DeviceCalendar(
    val id: Long,
    val name: String,
    val account: String,
    /** The colour the owning app gives it. Drawn as-is: it is that calendar's identity, not ours. */
    val color: Int?,
)

/**
 * One occurrence of one of them, already expanded by the provider.
 *
 * [eventId] is the *event*, not the instance, and it is what a tap opens — the calendar app that
 * owns it wants to be handed the event, and which occurrence you were looking at is carried beside
 * it in the intent's time range.
 */
data class DeviceEvent(
    val instanceId: Long,
    val eventId: Long,
    val title: String,
    val beginUtc: Long,
    val endUtc: Long,
    val allDay: Boolean,
    val location: String?,
    val color: Int?,
    /**
     * The identity the **sync source** gave this event: its iCalendar UID, or failing that the id
     * the account assigned it.
     *
     * Never [CalendarContract.Instances.EVENT_ID], which is a row number this device made up — a
     * different number on your other phone and gone after a reinstall. A UID is the same wherever
     * the event is, which is what makes it safe to write into a repository that syncs.
     *
     * Null when the provider gives neither, in which case the event simply cannot be annotated.
     */
    val uid: String?,
)

/**
 * What the owning calendar knows about a meeting, beyond its hours — CALENDAR_PLAN.md §22.
 *
 * Shown on the task's page and never written into the file, so the answer to "where are the
 * details" and the answer to "where are my notes" are the same page, and neither is a copy.
 */
data class DeviceEventDetails(
    val title: String,
    val location: String?,
    val description: String?,
    val organiser: String?,
    val calendarName: String?,
    val guests: List<String>,
)

/**
 * The phone's own calendars, read and never written — CALENDAR_PLAN.md §5.
 *
 * **The boundary is absolute and the OS enforces it.** Only `READ_CALENDAR` is in the manifest;
 * `WRITE_CALENDAR` is not, and will not be. That is a stronger guarantee than this class promising
 * to behave, and it is what removes most of the original two-way plan: no identity mapping, no echo
 * suppression, no conflict rule, no journal. A provider event is read, drawn, and forgotten.
 *
 * **[CalendarContract.Instances], not `Events`.** The provider expands recurrence for us, exceptions
 * and cancellations included, which is the one place this app gets expansion for free. It is also
 * why reading is so much cheaper than writing would have been: occurrences arrive resolved, in the
 * window asked for, with no rule left to interpret.
 *
 * Every call is wrapped: a content provider can be missing, revoked mid-flight, or simply throw on a
 * particular OEM's build. None of that is worth taking a calendar screen down for — the overlay is
 * an addition to your own events, never a precondition for them, so its failure mode is "no overlay".
 */
class DeviceCalendarSource(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Every calendar the account exposes, visible ones first. Empty without permission. */
    fun calendars(): List<DeviceCalendar> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
        )
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null,
                "${CalendarContract.Calendars.VISIBLE} DESC, " +
                    "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            DeviceCalendar(
                                id = c.getLong(0),
                                name = c.getString(1).orEmpty().ifBlank { "Calendar" },
                                account = c.getString(2).orEmpty(),
                                color = if (c.isNull(3)) null else c.getInt(3),
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Which of them are ticked in the owning app, for choosing a sensible default. */
    fun visibleCalendarIds(): Set<Long> {
        if (!hasPermission()) return emptySet()
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null,
            )?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
        }.getOrDefault(emptySet())
    }

    /**
     * Every occurrence between two instants, in the chosen calendars.
     *
     * The range goes in the **URI**, which is how `Instances` is queried — putting it in the
     * selection instead returns the whole table and filters afterwards. Calendar ids are inlined
     * rather than bound because they are longs read from the provider a moment ago, not text from
     * anywhere a person could reach.
     */
    fun instances(fromUtc: Long, toUtc: Long, calendarIds: Set<Long>): List<DeviceEvent> {
        if (!hasPermission() || calendarIds.isEmpty()) return emptyList()
        val uri: Uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { ContentUris.appendId(it, fromUtc); ContentUris.appendId(it, toUtc); it.build() }
        val projection = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DISPLAY_COLOR,
            // The identity the sync source gave it. UID_2445 is the iCalendar UID and is what an
            // organiser's system generated, so it is the same string wherever the event reaches;
            // _SYNC_ID is the account's own id for the row and is the fallback when a provider
            // leaves the UID null, which some of them do. Neither is this device's row number.
            CalendarContract.Instances.UID_2445,
            // Not exposed on Instances, but the view joins Events, so the Events constant names the
            // same column. Reaching for Instances._SYNC_ID does not compile; the column is there.
            CalendarContract.Events._SYNC_ID,
        )
        val where = "${CalendarContract.Instances.CALENDAR_ID} IN " +
            calendarIds.joinToString(prefix = "(", postfix = ")") +
            // A cancelled occurrence is an absence. The provider already drops most of them; this
            // catches the ones that arrive marked rather than missing.
            " AND ${CalendarContract.Instances.STATUS} IS NOT ${CalendarContract.Events.STATUS_CANCELED}"
        return runCatching {
            context.contentResolver.query(uri, projection, where, null, CalendarContract.Instances.BEGIN)
                ?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val begin = c.getLong(3)
                            val end = c.getLong(4)
                            if (end < begin) continue          // a row that cannot be drawn
                            add(
                                DeviceEvent(
                                    instanceId = c.getLong(0),
                                    eventId = c.getLong(1),
                                    title = c.getString(2).orEmpty().ifBlank { "Busy" },
                                    beginUtc = begin,
                                    endUtc = end,
                                    allDay = c.getInt(5) == 1,
                                    location = c.getString(6)?.takeIf { it.isNotBlank() },
                                    color = if (c.isNull(7)) null else c.getInt(7),
                                    uid = c.getString(8)?.takeIf { it.isNotBlank() }
                                        ?: c.getString(9)?.takeIf { it.isNotBlank() },
                                )
                            )
                        }
                    }
                }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Everything the owning calendar knows about one meeting — CALENDAR_PLAN.md §22.
     *
     * Read on demand and never stored: this is what a task's page shows above your own writing, so
     * the place and the guests live in exactly one place — the calendar that owns them — and cannot
     * drift from it. Without the permission it is simply null, and the task is still a task.
     *
     * Matched on the identity the sync source gave the event, not on a row id, for the same reason
     * the link is written that way: a row id means nothing on your other phone.
     */
    fun detailsFor(uid: String, calendarIds: Set<Long>): DeviceEventDetails? {
        if (!hasPermission() || uid.isBlank()) return null
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ORGANIZER,
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection,
                "${CalendarContract.Events.UID_2445} = ? OR ${CalendarContract.Events._SYNC_ID} = ?",
                arrayOf(uid, uid), null,
            )?.use { c ->
                if (!c.moveToFirst()) null else {
                    val eventId = c.getLong(4)
                    DeviceEventDetails(
                        title = c.getString(0).orEmpty(),
                        location = c.getString(1)?.takeIf { it.isNotBlank() },
                        description = c.getString(2)?.takeIf { it.isNotBlank() },
                        organiser = c.getString(3)?.takeIf { it.isNotBlank() },
                        calendarName = c.getString(5)?.takeIf { it.isNotBlank() },
                        guests = guestsOf(eventId),
                    )
                }
            }
        }.getOrNull()
    }

    /** Who is coming, as the provider lists them. Names where it has one, addresses otherwise. */
    private fun guestsOf(eventId: Long): List<String> = runCatching {
        context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val who = c.getString(0)?.takeIf { it.isNotBlank() }
                        ?: c.getString(1)?.takeIf { it.isNotBlank() }
                    if (who != null) add(who)
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Emits whenever the provider changes, so the overlay follows an edit made in the other app.
     *
     * Harmless by construction: the only thing it can cause is a re-query and a redraw. It emits
     * once on collection so a screen does not have to ask separately for its first answer.
     */
    fun changes(): Flow<Unit> = callbackFlow {
        trySend(Unit)
        if (!hasPermission()) {
            awaitClose { }
            return@callbackFlow
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI, true, observer,
            )
        }.isSuccess
        awaitClose {
            if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    /**
     * Hands an occurrence back to the app that owns it.
     *
     * The only thing this app ever does *to* a device event. A view intent with the instance's time
     * range, so the other calendar opens on the occurrence you were looking at rather than on the
     * series' first one.
     */
    fun viewIntent(eventId: Long, beginUtc: Long, endUtc: Long): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginUtc)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endUtc)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
