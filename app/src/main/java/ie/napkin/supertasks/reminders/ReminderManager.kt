package ie.napkin.supertasks.reminders

import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.ReminderRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps AlarmManager in sync with the database by observation, not hooks: the reminders Flow
 * (a property_value ⋈ node query) invalidates on any relevant write — set/change/clear,
 * complete, delete, widget or notification action — so every mutation path is covered without
 * touching the repositories. The in-memory [scheduled] map resets on process death; that's
 * harmless because armed alarms live in the OS, the first emission re-arms idempotently
 * (equal PendingIntents replace), and [ReminderReceiver] re-validates at delivery anyway.
 */
class ReminderManager(
    private val db: AppDatabase,
    private val scheduler: ReminderScheduler,
    scope: CoroutineScope,
) {
    private var scheduled = mapOf<String, Long>()   // nodeId -> armed instant

    init {
        scope.launch {
            // Due's def id is a per-install UUID; waiting on the Flow (not a one-shot
            // lookup) also covers the fresh-install case where seeding hasn't finished.
            val defId = db.propertyDao().observeBuiltInDefIdByName(BuiltIns.DUE_NAME)
                .filterNotNull().first()
            db.propertyDao().observeActiveReminders(defId)
                .distinctUntilChanged()
                .collect { sync(it) }
        }
    }

    @Synchronized
    private fun sync(rows: List<ReminderRow>) {
        val now = System.currentTimeMillis()
        val current = rows.associate { it.nodeId to it.atMillis }
        // Cancel only what left the row set (cleared/done/deleted). A row whose instant merely
        // became "past" keeps its armed alarm — Doze/setWindow can deliver after the nominal
        // time, and cancelling here would silently eat the reminder mid-flight.
        (scheduled.keys - current.keys).forEach(scheduler::cancel)
        current.forEach { (id, at) -> if (at > now && scheduled[id] != at) scheduler.schedule(id, at) }
        scheduled = current
    }

    /** One-shot for [BootReceiver] — alarms don't survive reboot. */
    suspend fun rescheduleAll() {
        val defId = db.propertyDao().builtInDefIdByName(BuiltIns.DUE_NAME) ?: return
        sync(db.propertyDao().activeRemindersOnce(defId))
    }
}
