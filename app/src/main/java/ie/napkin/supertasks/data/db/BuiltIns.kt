package ie.napkin.supertasks.data.db

/**
 * Identities of the fixed built-in property defs.
 *
 * All three ids are fixed strings, and that is load-bearing rather than tidy. A smart list stores
 * its rule as JSON containing `defId`, so when Due and Priority carried a per-install random UUID,
 * a rule written on one device matched nothing on another — invisible until someone opened a shared
 * workspace and found their Today list empty. Look-up by name still works and is still used by the
 * reminder path; the ids are what travel.
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
    const val DUE_DEF_ID = "builtin-due"
    const val DUE_NAME = "Due"
    const val DEADLINE_DEF_ID = "builtin-deadline"
    const val DEADLINE_NAME = "Deadline"
    const val PRIORITY_DEF_ID = "builtin-priority"
    const val PRIORITY_NAME = "Priority"

    /**
     * Who a task belongs to, as a GitHub login.
     *
     * A property value rather than a column: it is single-valued and per-node like `done`, so a
     * column was the obvious shape, but routing it through the property registry means smart lists
     * can filter on it for free — "assigned to me" is an ordinary `Prop` clause and needed no new
     * kind of question.
     *
     * It went unseeded for a long time and the consequence is worth remembering: every layer
     * existed — the `@login` token parsed and rendered, the value mapped both ways, the repository
     * set and cleared it — and none of it was reachable, because a value whose def row is missing
     * draws no chip and gets no editor. The feature was complete and invisible. A def is not
     * bookkeeping; it is the thing that makes a value appear.
     */
    const val ASSIGNEE_DEF_ID = "builtin-assignee"
    const val ASSIGNEE_NAME = "Assignee"

    /** Every built-in identity, so a scaffold and a re-index agree on what exists. */
    val ALL_DEF_IDS = listOf(PRIORITY_DEF_ID, DUE_DEF_ID, DEADLINE_DEF_ID, ASSIGNEE_DEF_ID)
}
