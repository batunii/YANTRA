package ie.napkin.supertasks.data.db

/**
 * Identities of the fixed built-in property defs. Due predates stable ids and keeps its
 * per-install random UUID — look it up by name (the codebase-wide convention); Deadline is
 * newer and has a fixed id shared by the Seeder and MIGRATION_3_4.
 *
 * Due value encoding (kind "date"):
 *  - v_date: local-midnight instant (all-day) or exact instant (timed)
 *  - v_bool: hasTime — non-null whenever a Due row exists
 *  - v_number: reminder offset in minutes BEFORE v_date (0 = on time, 30/60/1440 presets,
 *    -540 = 09:00 on the day for all-day tasks; NULL = no reminder). Fire instant =
 *    v_date - v_number*60000. Offsets are fixed real time — they don't re-resolve across DST.
 * Deadline: v_date local-midnight only.
 */
object BuiltIns {
    const val DUE_NAME = "Due"
    const val DEADLINE_DEF_ID = "builtin-deadline"
    const val DEADLINE_NAME = "Deadline"
    const val PRIORITY_NAME = "Priority"
}
