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

    /**
     * Everything that changes a block goes through [ie.napkin.supertasks.data.workspace.WorkspaceWriter.mutateInk],
     * which reads and writes under one lock.
     *
     * Reading the current strokes here and passing the new list down would be a read-modify-write
     * with the read outside the lock — and since every finished stroke is saved in its own coroutine,
     * drawing quickly loses strokes to whichever write lands last.
     */
    suspend fun addStroke(nodeId: String, stroke: Stroke, familyName: String) =
        ws.writerFor(nodeId).mutateInk(nodeId) { it + StrokeCodec.encode(stroke, familyName) }

    suspend fun undoLast(nodeId: String) =
        ws.writerFor(nodeId).mutateInk(nodeId) { it.dropLast(1) }

    /** Nothing to read first: the answer does not depend on what is there. */
    suspend fun clear(nodeId: String) = ws.writerFor(nodeId).writeInk(nodeId, emptyList())

    /** The eraser. Stroke ids are positional in the index, so this removes by position. */
    suspend fun deleteStroke(id: String) {
        val row = dao.strokeById(id) ?: return
        val at = dao.strokes(row.nodeId).first().indexOfFirst { it.id == id }
        if (at < 0) return
        // Resolved from the index because that is what the user tapped, then applied to the file
        // under the lock. A stroke added meanwhile lands after this one and leaves the position
        // alone; the ranks only shift for strokes drawn later.
        ws.writerFor(row.nodeId).mutateInk(row.nodeId) { blobs ->
            if (at in blobs.indices) blobs.filterIndexed { i, _ -> i != at } else blobs
        }
    }
}
