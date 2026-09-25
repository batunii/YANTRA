package ie.shoonya.yantra.data.label

import ie.shoonya.yantra.data.db.LabelEntity
import ie.shoonya.yantra.data.filter.Field
import ie.shoonya.yantra.data.workspace.WorkspaceReconciler

/**
 * One tag per name, whichever workspaces it was typed in.
 *
 * The index keeps a `label` row per workspace — `<ws>:label:<name>` — because the uniqueness
 * constraint on (workspace_id, name) and the cascade from `node_label` both depend on a row never
 * belonging to two repositories. That is storage. To the person using the app `#sync` is one tag,
 * and it was being shown as two: a picker listing it twice, a chip in one colour on a Personal task
 * and another colour on a task from a second repo, a recolour that only reached one of them.
 *
 * So every surface reads labels through here, keyed by the lowercased name. Each name gets one
 * row to speak for it, and every row's id maps to that one, so a chip resolved from any workspace's
 * attachment draws the same name in the same colour.
 */
object LabelCanon {

    /** What makes two labels the same tag: the name, ignoring case and stray whitespace. */
    fun key(name: String): String = name.trim().lowercase()

    /** One row per tag, in the order given — [LabelDao.all] already sorts by name. */
    fun distinct(rows: List<LabelEntity>): List<LabelEntity> =
        rows.groupBy { key(it.name) }.values.map(::speakerFor)

    /** Every row's id to the row that speaks for its tag, so any attachment resolves to one chip. */
    fun byId(rows: List<LabelEntity>): Map<String, LabelEntity> {
        val speakers = rows.groupBy { key(it.name) }.mapValues { (_, same) -> speakerFor(same) }
        return rows.associate { it.id to speakers.getValue(key(it.name)) }
    }

    /**
     * Whether two label ids name the same tag. Filters and pins remember the id that was picked,
     * which may be another workspace's row for the same name.
     */
    fun sameTag(a: String, b: String): Boolean {
        if (a == b) return true
        val k = WorkspaceReconciler.nameKeyOf(a) ?: return false
        return k == WorkspaceReconciler.nameKeyOf(b)
    }

    /** Whether [fields] holds a label field for the tag [labelId] names, from any workspace. */
    fun namesTag(fields: Set<Field>, labelId: String): Boolean =
        fields.any { it is Field.Label && sameTag(it.labelId, labelId) }

    /**
     * Which of one tag's rows decides its colour.
     *
     * A colour somebody chose beats one the palette seeded from the name: a tag first typed in a
     * second repo is seeded there, and would otherwise outvote the recolour made where it began.
     * After that the oldest workspace, then the id, so the answer never depends on index order.
     */
    private fun speakerFor(same: List<LabelEntity>): LabelEntity =
        same.minWith(
            compareBy<LabelEntity> { it.color == LabelPalette.defaultFor(it.name) }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        )
}
