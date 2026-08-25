package ie.napkin.supertasks.data.repo

import ie.napkin.supertasks.data.db.AppDatabase
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
    fun completedCounts() = dao.completedCounts()
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

    suspend fun endSession(sessionId: String, actualSecs: Int, completed: Boolean) {
        val session = dao.byId(sessionId) ?: return
        val ts = System.currentTimeMillis()
        append(session.copy(endedAt = ts, actualSecs = actualSecs, completed = completed, updatedAt = ts))
    }
}
