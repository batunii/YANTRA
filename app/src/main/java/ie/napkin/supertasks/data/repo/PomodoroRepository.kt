package ie.napkin.supertasks.data.repo

import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.PomodoroSessionEntity
import java.util.UUID

class PomodoroRepository(private val db: AppDatabase) {
    private val dao = db.pomodoroDao()

    fun forNode(nodeId: String) = dao.forNode(nodeId)
    fun all() = dao.all()
    fun completedCounts() = dao.completedCounts()

    suspend fun startSession(nodeId: String, plannedSecs: Int): String {
        val ts = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insert(
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
        dao.update(
            session.copy(
                endedAt = ts,
                actualSecs = actualSecs,
                completed = completed,
                updatedAt = ts,
            )
        )
    }
}
