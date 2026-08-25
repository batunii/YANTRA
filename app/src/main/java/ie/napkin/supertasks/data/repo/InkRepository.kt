package ie.napkin.supertasks.data.repo

import androidx.ink.strokes.Stroke
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.ink.StrokeCodec
import ie.napkin.supertasks.data.workspace.Workspaces
import kotlinx.coroutines.flow.first

/**
 * Strokes live in a sidecar beside the page, as the exact bytes [StrokeCodec] produces.
 *
 * The sidecar is rewritten whole on every change, because a stroke set has no line structure for
 * git to merge and pretending otherwise would corrupt drawings rather than reconcile them. That is
 * also why ink is the one thing the plan settles by last-writer-wins without trying anything
 * cleverer first.
 */
class InkRepository(private val db: AppDatabase, private val ws: Workspaces) {
    private val dao = db.inkDao()

    fun strokes(nodeId: String) = dao.strokes(nodeId)
    fun strokesUnder(parentId: String) = dao.strokesUnder(parentId)

    /** Current bytes for this block, in draw order — the index holds them ranked already. */
    private suspend fun current(nodeId: String): List<ByteArray> =
        dao.strokes(nodeId).first().map { it.data }

    suspend fun addStroke(nodeId: String, stroke: Stroke, familyName: String) =
        ws.writerFor(nodeId).writeInk(nodeId, current(nodeId) + StrokeCodec.encode(stroke, familyName))

    suspend fun undoLast(nodeId: String) =
        ws.writerFor(nodeId).writeInk(nodeId, current(nodeId).dropLast(1))

    suspend fun clear(nodeId: String) = ws.writerFor(nodeId).writeInk(nodeId, emptyList())

    /** The eraser. Stroke ids are positional in the index, so this removes by position. */
    suspend fun deleteStroke(id: String) {
        val row = dao.strokeById(id) ?: return
        val kept = dao.strokes(row.nodeId).first().filterNot { it.id == id }.map { it.data }
        ws.writerFor(row.nodeId).writeInk(row.nodeId, kept)
    }
}
