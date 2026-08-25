package ie.napkin.supertasks.data.repo

import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.FocusOutcome
import ie.napkin.supertasks.data.db.PomodoroSessionEntity
import ie.napkin.supertasks.data.workspace.WorkspaceReconciler
import ie.napkin.supertasks.data.workspace.Workspaces
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Focus sessions, appended to the workspace log.
 *
 * A session is written twice: once when it starts and once when it ends. That is what append-only
 * costs, and it is worth paying — rewriting a shared line would put two devices in conflict over
 * work neither of them disagrees about, whereas two tails merge without anyone deciding anything.
 * The reader keeps the last line for each id, so the second write supersedes the first.
 *
 * Writing on start rather than only on completion is what lets the timer survive process death: the
 * in-flight session is on disk, so a reindex cannot forget it and [openSession] can find it again.
 */
class PomodoroRepository(private val db: AppDatabase, private val ws: Workspaces) {
    private val dao = db.pomodoroDao()

    fun forNode(nodeId: String) = dao.forNode(nodeId)
    fun all() = dao.all()
    fun perNode() = dao.perNode()

    /** Everything given to a task and its subtasks. Never summed across tasks — see the DAO. */
    fun secondsOnSubtree(nodeId: String) = dao.secondsOnSubtree(nodeId)

    /** Everything given in a window, counted once. */
    fun totalBetween(from: Long, to: Long) = dao.totalBetween(from, to)

    /** Sessions in a window, newest first. */
    fun between(from: Long, to: Long) = dao.between(from, to)
    suspend fun openSession() = dao.openSession()
    suspend fun lastSession() = dao.lastSession()
    suspend fun nodeTitle(nodeId: String): String? = db.nodeDao().byId(nodeId)?.title

    private val month = DateTimeFormatter.ofPattern("yyyy-MM")

    private suspend fun append(s: PomodoroSessionEntity) = ws.writerFor(s.nodeId).appendPomodoro(
        WorkspaceReconciler.sessionLine(s),
        month.format(Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault())),
    )

    suspend fun startSession(nodeId: String, plannedSecs: Int): String {
        val ts = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        append(
            PomodoroSessionEntity(
                id = id, nodeId = nodeId, startedAt = ts, plannedSecs = plannedSecs,
                createdAt = ts, updatedAt = ts,
            )
        )
        return id
    }

    /**
     * Closes a session and writes how it ended.
     *
     * **A mis-tap is dropped and nothing else is.** Under a minute without reaching a target is a
     * timer started by accident, and nobody will ever want it back; anything longer is real, up to
     * and including a deliberate three-minute commitment that ran its course. The threshold people
     * usually mean by "too short to be interesting" — a few minutes — is a *display* rule and lives
     * in the history view, because filtering at write time would bake a display decision into an
     * append-only record and understate effort in the one direction nothing on screen would reveal.
     * See `ARCHITECTURE.md` §2a, F2 and F3.
     */
    suspend fun endSession(sessionId: String, actualSecs: Int, outcome: String) {
        val session = dao.byId(sessionId) ?: return
        // A mis-tap is closed like anything else and simply counts nowhere. Returning early here —
        // which is what this used to do — left the session open in the log forever, so `openSession`
        // kept finding it and the timer resurrected a session that had already been stopped.
        val settled =
            if (actualSecs < MIN_KEPT_SECS && outcome != FocusOutcome.RAN_OUT) FocusOutcome.DISCARDED
            else outcome
        val ts = System.currentTimeMillis()
        append(session.copy(endedAt = ts, actualSecs = actualSecs, outcome = settled, updatedAt = ts))
    }

    private companion object {
        /** Below this, and with no promise kept, a session is a fat-fingered start. */
        const val MIN_KEPT_SECS = 60
    }
}
