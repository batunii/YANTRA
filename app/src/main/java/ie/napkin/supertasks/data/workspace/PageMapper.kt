package ie.napkin.supertasks.data.workspace

import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.data.format.Block
import ie.napkin.supertasks.data.format.Bullet
import ie.napkin.supertasks.data.format.DueSpec
import ie.napkin.supertasks.data.format.DueValue
import ie.napkin.supertasks.data.format.Heading
import ie.napkin.supertasks.data.format.ImageRef
import ie.napkin.supertasks.data.format.InkRef
import ie.napkin.supertasks.data.format.Numbered
import ie.napkin.supertasks.data.format.PageDoc
import ie.napkin.supertasks.data.format.Prose
import ie.napkin.supertasks.data.format.TaskRef
import ie.napkin.supertasks.data.format.TaskStatus
import ie.napkin.supertasks.data.rank.Rank
import ie.napkin.supertasks.data.time.localDateOf
import ie.napkin.supertasks.data.time.startOfDay
import java.time.Instant
import java.time.ZoneId

/** A label mentioned on a line. Names resolve to ids against the workspace registry. */
data class LabelLink(val nodeId: String, val name: String)

/** One page's worth of index rows. Ink blobs are loaded separately, keyed by [inkNodeIds]. */
data class MappedPage(
    val page: NodeEntity,
    val children: List<NodeEntity>,
    val values: List<PropertyValueEntity>,
    val labels: List<LabelLink>,
    val inkNodeIds: List<String>,
) {
    val nodes: List<NodeEntity> get() = listOf(page) + children
}

/**
 * Turns a page file into index rows and back — the join between GIT_WORKSPACES_PLAN.md §2 and the
 * Room schema.
 *
 * The direction of authority is the thing to keep straight. **Files are the truth; these rows are
 * derived and disposable.** So this mapper never invents anything it cannot re-derive, and anything
 * the schema holds that the format deliberately drops — `collapsed`, `deleted_at`, `canvas_*` —
 * comes back at its default rather than being preserved somewhere sneaky.
 *
 * `rank` is regenerated from line position on every import. It exists only so the rest of the app,
 * which sorts by it, keeps working; two devices that agree on a file will agree on the ranks it
 * produces, so the index never becomes a second source of ordering.
 */
object PageMapper {

    /**
     * Ids for blocks the format does not name.
     *
     * Only ink and image blocks carry an id in the file, because they are the only ones that own a
     * sidecar. Everything else is identified by where it sits, so its row id is derived from the
     * page and the line number — stable while the page is, regenerated when it is not. That is
     * sound for a disposable index, with one consequence worth knowing: a re-index during editing
     * renumbers blocks below an insertion, so anything holding a block id across a sync (a caret,
     * an active row) has to tolerate it changing.
     *
     * The separator is `~` rather than `#`, and that is not cosmetic. A node id is dropped into a
     * navigation route, which is parsed as a URI — `#` starts a fragment there, so `node/<page>#3`
     * was read as `node/<page>` and tapping a derived-id block navigated onto the page it was
     * already on. `~` is an unreserved URI character and means nothing to any parser between here
     * and the screen. (Routes encode the id as well; both, because either alone is a rule someone
     * has to remember.)
     */
    fun blockId(pageId: String, index: Int): String = "$pageId$BLOCK_SEP$index"

    /** Separator between a page id and a derived block's line number. See [blockId]. */
    const val BLOCK_SEP = "~"

    // ---- file -> rows ----

    fun toRows(page: PageDoc, workspaceId: String = "", zone: ZoneId = ZoneId.systemDefault()): MappedPage {
        val ts = page.modifiedAt.toEpochMilli()
        val children = ArrayList<NodeEntity>(page.blocks.size)
        val values = ArrayList<PropertyValueEntity>()
        val labels = ArrayList<LabelLink>()
        val ink = ArrayList<String>()

        var rank = Rank.after(null)
        page.blocks.forEachIndexed { i, block ->
            val id = when (block) {
                is InkRef -> block.id
                is TaskRef -> block.id.ifEmpty { blockId(page.id, i) }
                else -> blockId(page.id, i)
            }
            children += NodeEntity(
                id = id,
                workspaceId = workspaceId,
                parentId = page.id,
                type = typeOf(block),
                title = titleOf(block),
                rank = rank,
                done = block is TaskRef && block.status == TaskStatus.DONE,
                inProgress = block is TaskRef && block.status == TaskStatus.IN_PROGRESS,
                indent = block.indent,
                createdAt = ts,
                updatedAt = ts,
            )
            rank = Rank.after(rank)

            if (block is InkRef) ink += block.id
            if (block is TaskRef) {
                values += valuesFor(id, block, ts, zone, workspaceId)
                block.labels.forEach { labels += LabelLink(id, it) }
            }
        }

        return MappedPage(
            page = NodeEntity(
                id = page.id,
                workspaceId = workspaceId,
                parentId = page.parent,
                type = page.type,
                // Authoritative only at the top level; the reconciler overwrites it from the
                // parent's line for anything nested. See PageDoc.title.
                title = page.title,
                rank = Rank.after(null),
                systemKey = page.systemKey,
                createdAt = ts,
                updatedAt = ts,
            ),
            children = children,
            values = values,
            labels = labels,
            inkNodeIds = ink,
        )
    }

    private fun typeOf(b: Block): String = when (b) {
        is TaskRef -> NodeType.TASK
        is Heading -> NodeType.HEADING
        is Bullet -> NodeType.BULLET
        is Numbered -> NodeType.NUMBERED
        is Prose -> NodeType.PARAGRAPH
        is InkRef -> NodeType.INK
        is ImageRef -> NodeType.IMAGE
    }

    private fun titleOf(b: Block): String? = when (b) {
        is TaskRef -> b.title
        is Heading -> b.text
        is Bullet -> b.text
        is Numbered -> b.text
        is Prose -> b.text
        is ImageRef -> b.uri
        is InkRef -> null
    }

    private fun valuesFor(id: String, t: TaskRef, ts: Long, zone: ZoneId, ws: String): List<PropertyValueEntity> {
        val out = ArrayList<PropertyValueEntity>(3)
        t.due?.let { due ->
            // The encoding on BuiltIns: v_bool carries hasTime, v_number the reminder offset.
            val allDay = due.value is DueValue.AllDay
            out += PropertyValueEntity(
                nodeId = id, defId = BuiltIns.DUE_DEF_ID, workspaceId = ws,
                vDate = when (val v = due.value) {
                    is DueValue.AllDay -> startOfDay(v.date, zone)
                    is DueValue.At -> v.instant.toEpochMilli()
                },
                vBool = !allDay,
                vNumber = due.reminderMin?.toDouble(),
                updatedAt = ts,
            )
        }
        t.deadline?.let {
            out += PropertyValueEntity(
                nodeId = id, defId = BuiltIns.DEADLINE_DEF_ID, workspaceId = ws,
                vDate = startOfDay(it, zone), updatedAt = ts,
            )
        }
        t.priority?.let {
            out += PropertyValueEntity(
                nodeId = id, defId = BuiltIns.PRIORITY_DEF_ID, workspaceId = ws, vText = it, updatedAt = ts,
            )
        }
        t.assignee?.let {
            out += PropertyValueEntity(
                nodeId = id, defId = BuiltIns.ASSIGNEE_DEF_ID, workspaceId = ws, vText = it, updatedAt = ts,
            )
        }
        return out
    }

    // ---- rows -> file ----

    /**
     * Rebuilds a page from its rows. [children] must already be in the order they should appear —
     * rank order, since that is what rank is for.
     *
     * [titleIsOwn] says whether this node's title belongs in its own frontmatter. False for anything
     * with a parent, because there the line owns it; passing it explicitly keeps the rule in one
     * place rather than re-deriving it from `parentId` here and somewhere else too.
     */
    fun toPage(
        node: NodeEntity,
        children: List<NodeEntity>,
        valuesByNode: Map<String, List<PropertyValueEntity>>,
        labelNamesByNode: Map<String, List<String>>,
        device: String?,
        titleIsOwn: Boolean = node.parentId == null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PageDoc = PageDoc(
        id = node.id,
        type = node.type,
        parent = node.parentId,
        title = node.title?.takeIf { titleIsOwn },
        systemKey = node.systemKey,
        modifiedAt = Instant.ofEpochMilli(node.updatedAt),
        device = device,
        blocks = children.map { child ->
            blockFor(child, valuesByNode[child.id].orEmpty(), labelNamesByNode[child.id].orEmpty(), zone)
        },
    )

    private fun blockFor(
        n: NodeEntity,
        values: List<PropertyValueEntity>,
        labels: List<String>,
        zone: ZoneId,
    ): Block = when (n.type) {
        NodeType.HEADING -> Heading(n.title.orEmpty(), n.indent)
        NodeType.BULLET -> Bullet(n.title.orEmpty(), n.indent)
        NodeType.NUMBERED -> Numbered(n.title.orEmpty(), n.indent)
        NodeType.INK -> InkRef(n.id, n.indent)
        NodeType.IMAGE -> ImageRef(n.title.orEmpty(), n.indent)
        NodeType.TASK -> TaskRef(
            id = n.id,
            title = n.title.orEmpty(),
            status = when {
                n.done -> TaskStatus.DONE
                n.inProgress -> TaskStatus.IN_PROGRESS
                else -> TaskStatus.OPEN
            },
            indent = n.indent,
            due = dueFrom(values, zone),
            deadline = values.firstOrNull { it.defId == BuiltIns.DEADLINE_DEF_ID }
                ?.vDate?.let { localDateOf(it, zone) },
            priority = values.firstOrNull { it.defId == BuiltIns.PRIORITY_DEF_ID }?.vText,
            labels = labels,
            assignee = values.firstOrNull { it.defId == BuiltIns.ASSIGNEE_DEF_ID }?.vText,
        )
        else -> Prose(n.title.orEmpty(), n.indent)
    }

    private fun dueFrom(values: List<PropertyValueEntity>, zone: ZoneId): DueSpec? {
        val v = values.firstOrNull { it.defId == BuiltIns.DUE_DEF_ID } ?: return null
        val date = v.vDate ?: return null
        val value =
            if (v.vBool == true) DueValue.At(Instant.ofEpochMilli(date))
            else DueValue.AllDay(localDateOf(date, zone))
        return DueSpec(value, v.vNumber?.toInt())
    }
}
