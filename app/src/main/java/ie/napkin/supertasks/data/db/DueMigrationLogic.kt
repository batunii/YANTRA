package ie.napkin.supertasks.data.db

import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.time.startOfDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure decisions for MIGRATION_3_4, extracted for plain JUnit coverage — the migration
 * itself is destructive (def deletion + row merges) so every judgment call lives here.
 */
object DueMigrationLogic {

    /**
     * Normalize a pre-v4 Due value to the local-midnight instant of its intended day.
     * Values at exact UTC midnight are the M3 picker's raw output (the pre-v4 bug) and mean
     * "that UTC calendar date"; anything else (the Seeder wrote wall-clock `now`) is
     * interpreted in the local zone. atStartOfDay resolves per date, so DST is handled.
     */
    fun normalizeDueDate(v: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val localDate =
            if (v % 86_400_000L == 0L) Instant.ofEpochMilli(v).atZone(ZoneOffset.UTC).toLocalDate()
            else localDateOf(v, zone)
        return startOfDay(localDate, zone)
    }

    fun localDateOf(v: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        ie.napkin.supertasks.data.time.localDateOf(v, zone)

    /**
     * Merge decision for a task that had both an all-day Due and a Reminder: the reminder
     * instant becomes the timed Due; the old Due date becomes a Deadline only when it was a
     * DIFFERENT day (same-day is subsumed by the timed value).
     */
    fun oldDueBecomesDeadline(normalizedDue: Long, reminderAt: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        localDateOf(normalizedDue, zone) != localDateOf(reminderAt, zone)

    /**
     * Strip every Prop clause referencing [defId] from a filter tree (the def is being
     * deleted; IS_SET on it would never match again, NOT_SET always). Returns null when the
     * whole tree strips away. Unchanged subtrees are returned as the same instances so
     * callers can cheaply detect "nothing changed" via identity.
     */
    fun stripDef(filter: Filter, defId: String): Filter? = when (filter) {
        is Filter.Prop -> if (filter.defId == defId) null else filter
        is Filter.All -> {
            val kept = filter.filters.mapNotNull { stripDef(it, defId) }
            when {
                kept.isEmpty() -> null
                kept == filter.filters -> filter
                else -> Filter.All(kept)
            }
        }
        is Filter.AnyOf -> {
            val kept = filter.filters.mapNotNull { stripDef(it, defId) }
            when {
                kept.isEmpty() -> null
                kept == filter.filters -> filter
                else -> Filter.AnyOf(kept)
            }
        }
        is Filter.Not -> stripDef(filter.filter, defId)?.let { if (it === filter.filter) filter else Filter.Not(it) }
        else -> filter
    }
}
