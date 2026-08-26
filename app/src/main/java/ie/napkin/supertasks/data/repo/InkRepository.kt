package ie.napkin.supertasks.data.repo

import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.workspace.Workspaces

/**
 * Strokes live in a sidecar beside the page, as the exact bytes the stroke codec produces.
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
     * Replaces a block's strokes with exactly this list.
     *
     * The only mutation there is. The drawing screen holds the session and hands over the whole set,
     * so nothing here reads-then-writes — which is what used to lose strokes when several finished
     * at once, each having read the same list before any of them wrote.
     */
    suspend fun replace(nodeId: String, strokes: List<ByteArray>) =
        ws.writerFor(nodeId).writeInk(nodeId, strokes)
}
