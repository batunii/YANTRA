package ie.napkin.supertasks.data.repo

import androidx.ink.strokes.Stroke
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.InkStrokeEntity
import ie.napkin.supertasks.data.ink.StrokeCodec
import ie.napkin.supertasks.data.rank.Rank
import java.util.UUID

class InkRepository(private val db: AppDatabase) {
    private val dao = db.inkDao()

    fun strokes(nodeId: String) = dao.strokes(nodeId)
    fun strokesUnder(parentId: String) = dao.strokesUnder(parentId)

    suspend fun addStroke(nodeId: String, stroke: Stroke, familyName: String) {
        val ts = System.currentTimeMillis()
        val bbox = StrokeCodec.bbox(stroke.inputs)
        dao.insert(
            InkStrokeEntity(
                id = UUID.randomUUID().toString(),
                nodeId = nodeId,
                data = StrokeCodec.encode(stroke, familyName),
                bboxX = bbox?.get(0)?.toDouble(),
                bboxY = bbox?.get(1)?.toDouble(),
                bboxW = bbox?.get(2)?.toDouble(),
                bboxH = bbox?.get(3)?.toDouble(),
                rank = Rank.after(dao.lastRank(nodeId)),
                createdAt = ts,
                updatedAt = ts,
            )
        )
    }

    suspend fun undoLast(nodeId: String) = dao.softDeleteLast(nodeId, System.currentTimeMillis())
    suspend fun clear(nodeId: String) = dao.softDeleteAll(nodeId, System.currentTimeMillis())
    suspend fun deleteStroke(id: String) = dao.softDeleteById(id, System.currentTimeMillis())
}
