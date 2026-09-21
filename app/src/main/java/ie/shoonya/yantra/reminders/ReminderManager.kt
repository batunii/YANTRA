package ie.shoonya.yantra.reminders

import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.BuiltIns
import ie.shoonya.yantra.data.db.DueReminderRow
import ie.shoonya.yantra.data.db.ReminderRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
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
    private var scheduled = mapOf<String, ReminderRow>()   // ReminderRow.key -> what is armed

    init {
        scope.launch {
            // Due's def id is a per-install UUID; waiting on the Flow (not a one-shot
            // lookup) also covers the fresh-install case where seeding hasn't finished.
            val defId = db.propertyDao().observeBuiltInDefIdByName(BuiltIns.DUE_NAME)
                .filterNotNull().first()
            // Two sources, one scheduler. A task's reminder lives in property_value and an event's
            // in the event table, but an alarm is an alarm — combining here rather than running two
            // managers is what keeps [sync]'s "cancel whatever left the set" honest, since a
            // manager that could only see half the rows would cancel the other half's alarms every
            // time it ran.
            combine(
                db.propertyDao().observeActiveReminders(defId),
                db.eventDao().observeEventReminders(),
            ) { tasks, events -> expand(tasks) + events }
                .distinctUntilChanged()
                .collect { sync(it) }
        }
    }

    @Synchronized
    private fun sync(rows: List<ReminderRow>) {
        apply(Plan.from(scheduled, rows, System.currentTimeMillis()))
    }

    /**
     * Arms everything future as though nothing were armed, and forgets what it thought it knew.
     *
     * **Not the same call as [sync], and the difference is the whole point.** [sync] skips a row
     * whose instant it believes is already armed, which is what stops every keystroke re-arming
     * every alarm. That belief is exactly what is wrong after the system has cancelled the alarms
     * underneath us — revoking exact-alarm access does precisely that — so syncing then would
     * compare the rows against a map that still says "armed", change nothing, and leave the person
     * with no reminders at all and no way to tell.
     *
     * Harmless when the belief was right: arming the same instant again replaces an equal
     * PendingIntent, which is the same no-op it would have been to skip.
     */
    @Synchronized
    private fun rearm(rows: List<ReminderRow>) {
        scheduled = emptyMap()
        apply(Plan.from(emptyMap(), rows, System.currentTimeMillis()))
    }

    private fun apply(plan: Plan) {
        plan.cancel.forEach { scheduler.cancel(it.nodeId, it.offsetMin) }
        plan.arm.forEach { scheduler.schedule(it.nodeId, it.offsetMin, it.atMillis) }
        scheduled = plan.armed
    }

    /**
     * A due row's comma-separated offsets, as one alarm each.
     *
     * In Kotlin because the offsets are one column and SQLite has no readable way to turn a string
     * into rows. A row whose offsets do not parse yields nothing rather than an alarm at the due
     * instant itself, which would be a reminder nobody asked for at a moment they did not choose.
     */
    private fun expand(rows: List<DueReminderRow>): List<ReminderRow> =
        rows.flatMap { row ->
            ie.shoonya.yantra.data.format.Reminders.parse(row.reminders).map { offset ->
                ReminderRow(row.nodeId, offset, row.dueMillis - offset.toLong() * 60_000L)
            }
        }

    /**
     * One-shot for [BootReceiver] — after a reboot, an update, a clock change, or exact-alarm
     * access being taken away, none of which leave an alarm behind.
     */
    suspend fun rescheduleAll() {
        val defId = db.propertyDao().builtInDefIdByName(BuiltIns.DUE_NAME)
        val tasks = if (defId == null) emptyList() else db.propertyDao().activeRemindersOnce(defId)
        // Events do not depend on the property registry having been seeded, so they are rearmed even
        // when the Due def is somehow missing — a boot that lost the registry should not also lose
        // every meeting alarm.
        rearm(expand(tasks) + db.eventDao().eventRemindersOnce())
    }

    /**
     * What to cancel and what to arm — the whole decision, with no AlarmManager in it.
     *
     * Separated so it can be tested. Everything this class does that could be wrong is in these
     * eight lines, and reaching them through Room, a Flow and the alarm service meant none of it
     * had ever been tested at all.
     */
    internal data class Plan(
        val cancel: List<ReminderRow>,
        val arm: List<ReminderRow>,
        /** What is armed once this plan is applied — the caller's new memory. */
        val armed: Map<String, ReminderRow>,
    ) {
        companion object {
            fun from(scheduled: Map<String, ReminderRow>, rows: List<ReminderRow>, now: Long): Plan {
                // Keyed by node *and* offset: two reminders on one task are two alarms, and the
                // node alone stopped being enough to name one the moment a task could carry more
                // than a single reminder.
                val current = rows.associateBy { it.key }
                return Plan(
                    // Cancel only what left the row set (cleared/done/deleted). A row whose instant
                    // merely became "past" keeps its armed alarm — Doze and setWindow can deliver
                    // after the nominal time, and cancelling here would silently eat a reminder
                    // mid-flight. It is safe to leave armed because the receiver validates against
                    // the database before it shows anything.
                    cancel = (scheduled.keys - current.keys).mapNotNull { scheduled[it] },
                    // Only the future, and only where the instant is not the one already armed.
                    arm = current.values.filter {
                        it.atMillis > now && scheduled[it.key]?.atMillis != it.atMillis
                    },
                    armed = current,
                )
            }
        }
    }
}
