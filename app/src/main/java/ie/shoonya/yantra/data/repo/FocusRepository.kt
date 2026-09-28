package ie.shoonya.yantra.data.repo

import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.FocusOutcome
import ie.shoonya.yantra.data.db.FocusSessionEntity
import ie.shoonya.yantra.data.workspace.WorkspaceReconciler
import ie.shoonya.yantra.data.workspace.Workspaces
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
class FocusRepository(
    private val db: AppDatabase,
    private val ws: Workspaces,
    /**
     * Which session *this device* has running, kept on the device.
     *
     * A session is written to the log the moment it starts, so it syncs while it is still running —
     * and the log is shared. Restoring "the newest open session" after process death therefore
     * resumed whatever anybody had open: a teammate's timer on a shared list, or your own from the
     * tablet. The clock is this device's alone, so the answer to "what was I timing" is kept here.
     * Null keeps the old rule, for tests that exercise the log rather than the device.
     */
    private val local: android.content.SharedPreferences? = null,
    /** Every login you are signed in with, across workspaces — what makes a session yours. */
    private val myLogins: () -> Set<String> = { emptySet() },
) {
    private val dao = db.focusDao()

    /** Everything given to one task, by anybody: a task's time is the time it has had. */
    fun forNode(nodeId: String) = dao.forNode(nodeId)
    fun all() = dao.all()

    /**
     * Your own sessions only — what Stats is about. A session nobody is named on counts as yours:
     * Personal's, and every one written before sessions said whose they were.
     */
    fun mine() = dao.all().map { all -> all.filter { isMine(it) } }

    private fun isMine(s: FocusSessionEntity): Boolean {
        val by = s.by ?: return true
        return myLogins().any { it.equals(by, ignoreCase = true) }
    }
    fun perNode() = dao.perNode()

    /** Everything given to a task and its subtasks. Never summed across tasks — see the DAO. */
    fun secondsOnSubtree(nodeId: String) = dao.secondsOnSubtree(nodeId)

    /** Everything given in a window, counted once. */
    fun totalBetween(from: Long, to: Long) = dao.totalBetween(from, to)

    /** Sessions in a window, newest first. */
    fun between(from: Long, to: Long) = dao.between(from, to)
    /** The session this device was timing, if it is still open — see [local]. */
    suspend fun openSession(): FocusSessionEntity? {
        val prefs = local ?: return dao.openSession()
        // The one-time bridge: a session already running when this build arrived was never
        // recorded here. It is resumed if it is yours, and from then on the device knows.
        if (!prefs.contains(OPEN_KEY)) return dao.openSession()?.takeIf { isMine(it) }
        val id = prefs.getString(OPEN_KEY, null)?.takeIf { it.isNotEmpty() } ?: return null
        return dao.byId(id)?.takeIf { it.endedAt == null }
    }

    /** Your last session, for "start the last thing again". Somebody else's is not yours to resume. */
    suspend fun lastSession(): FocusSessionEntity? =
        dao.lastSession()?.takeIf { isMine(it) } ?: dao.all().first().firstOrNull { isMine(it) }
    suspend fun nodeTitle(nodeId: String): String? = db.nodeDao().byId(nodeId)?.title

    private val month = DateTimeFormatter.ofPattern("yyyy-MM")

    private suspend fun append(s: FocusSessionEntity) = ws.writerFor(s.nodeId).appendFocus(
        WorkspaceReconciler.sessionLine(s),
        month.format(Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault())),
    )

    suspend fun startSession(nodeId: String, plannedSecs: Int): String {
        val ts = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        append(
            FocusSessionEntity(
                id = id, nodeId = nodeId, startedAt = ts, plannedSecs = plannedSecs,
                // The same login a task's `by:` is stamped with, and null in the same places.
                by = ws.writerFor(nodeId).signedIn(),
                createdAt = ts, updatedAt = ts,
            )
        )
        local?.edit()?.putString(OPEN_KEY, id)?.apply()
        return id
    }

    /**
     * Closes a session and writes how it ended.
     *
     * **A mis-tap is dropped and nothing else is.** Under [FocusOutcome.MIN_KEPT_SECS] without
     * reaching a target is a
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
            if (actualSecs < FocusOutcome.MIN_KEPT_SECS && outcome != FocusOutcome.RAN_OUT) FocusOutcome.DISCARDED
            else outcome
        val ts = System.currentTimeMillis()
        append(session.copy(endedAt = ts, actualSecs = actualSecs, outcome = settled, updatedAt = ts))
        // Written as empty, not removed — and `putString(key, null)` *is* a remove on Android. An
        // absent key is the one-time bridge in openSession, and must stay absent only once.
        if (local?.getString(OPEN_KEY, null) == sessionId) local.edit().putString(OPEN_KEY, "").apply()
    }

    private companion object {
        const val OPEN_KEY = "open_session"
    }
}
