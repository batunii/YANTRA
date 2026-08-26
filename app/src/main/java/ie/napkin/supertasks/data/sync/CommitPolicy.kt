package ie.napkin.supertasks.data.sync

/** What kind of change just happened, which is what decides how long it may wait. */
enum class Change {
    /** Created, deleted, or completed. Someone else may be waiting to see it. */
    STRUCTURAL,

    /** A title, a due date, a label, a line of prose. Worth batching. */
    EDIT,

    /** Strokes. They arrive in bursts and each one is large. */
    INK,
}

/**
 * When a batch of changes has waited long enough — GIT_WORKSPACES_PLAN.md §4.
 *
 * Pure on purpose. The cadence is the part with judgement in it and the part most likely to be
 * argued about later, so it is a function of numbers rather than something tangled up in coroutines
 * and wall clocks. [CommitScheduler] owns the timers; this owns the opinion.
 *
 * The shape matters more than the numbers, and the shape is: **what someone else might be waiting
 * for goes now, and everything else waits until you have stopped.** Completing a task is how a
 * collaborator learns not to start it. A title being typed is not — and committing per keystroke
 * would bury the history under a thousand one-character diffs and make every clone slower forever.
 */
object CommitPolicy {

    /** Long enough that typing a sentence is one commit, short enough to feel prompt. */
    const val EDIT_QUIET_MS = 20_000L

    /** Nothing waits longer than this, however continuously you type. */
    const val EDIT_MAX_AGE_MS = 15 * 60_000L

    /** Strokes come in bursts; this is roughly "you have stopped drawing". */
    const val INK_QUIET_MS = 8_000L

    /** A long drawing session still lands in the repo before it is forgotten. */
    const val INK_MAX_AGE_MS = 20 * 60_000L

    data class Pending(
        val edits: Int = 0,
        val ink: Int = 0,
        val oldestAt: Long = 0L,
        val lastAt: Long = 0L,
    ) {
        val isEmpty: Boolean get() = edits == 0 && ink == 0

        fun plus(change: Change, at: Long): Pending {
            val first = if (isEmpty) at else oldestAt
            return when (change) {
                Change.EDIT -> copy(edits = edits + 1, oldestAt = first, lastAt = at)
                Change.INK -> copy(ink = ink + 1, oldestAt = first, lastAt = at)
                Change.STRUCTURAL -> copy(oldestAt = first, lastAt = at)
            }
        }
    }

    /**
     * Why this batch should be committed now, or null to keep waiting.
     *
     * Returning a reason rather than a boolean is not decoration: it ends up in the commit message,
     * so the history says why it exists. "quiet after 6 edits" is a thing you can read a year later;
     * "sync" is not.
     */
    fun reasonToFlush(pending: Pending, now: Long, leavingApp: Boolean = false): String? {
        if (pending.isEmpty) return null

        // Going away is always a flush. Android may not come back — the process can be killed
        // while backgrounded, and work that was only ever in a file and never in a commit would
        // sit there until the next time the app happened to be opened.
        if (leavingApp) return "app went to the background"

        val quiet = now - pending.lastAt
        val age = now - pending.oldestAt

        if (pending.ink > 0) {
            if (quiet >= INK_QUIET_MS) return "finished drawing (${pending.ink} strokes)"
            if (age >= INK_MAX_AGE_MS) return "a long drawing session (${pending.ink} strokes)"
        }
        if (pending.edits > 0) {
            if (quiet >= EDIT_QUIET_MS) return "quiet after ${pending.edits} edits"
            if (age >= EDIT_MAX_AGE_MS) return "${pending.edits} edits, oldest is overdue"
        }
        return null
    }

    /** How long to wait before asking again. Null when there is nothing pending. */
    fun nextCheckDelay(pending: Pending, now: Long): Long? {
        if (pending.isEmpty) return null
        val candidates = buildList {
            if (pending.ink > 0) {
                add(pending.lastAt + INK_QUIET_MS - now)
                add(pending.oldestAt + INK_MAX_AGE_MS - now)
            }
            if (pending.edits > 0) {
                add(pending.lastAt + EDIT_QUIET_MS - now)
                add(pending.oldestAt + EDIT_MAX_AGE_MS - now)
            }
        }
        return candidates.filter { it > 0 }.minOrNull() ?: 0L
    }
}
