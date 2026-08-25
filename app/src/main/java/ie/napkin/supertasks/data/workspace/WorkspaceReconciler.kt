package ie.napkin.supertasks.data.workspace

import ie.napkin.supertasks.data.db.InkStrokeEntity
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeLabelEntity
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.data.db.SmartListDefEntity
import ie.napkin.supertasks.data.format.PageDoc
import ie.napkin.supertasks.data.format.TaskRef
import ie.napkin.supertasks.data.label.LabelPalette
import ie.napkin.supertasks.data.rank.Rank
import java.time.ZoneId

/**
 * Everything a workspace directory says, in the shape Room stores it.
 *
 * [problems] is not decoration. A page whose parent is missing, or a line pointing at an ink
 * sidecar that is not there, has to be *reported* — never quietly dropped, because a task that
 * vanishes is indistinguishable from data loss and the file may simply have been written by a
 * newer version of the app.
 */
data class WorkspaceIndex(
    val nodes: List<NodeEntity> = emptyList(),
    val values: List<PropertyValueEntity> = emptyList(),
    val labels: List<LabelEntity> = emptyList(),
    val nodeLabels: List<NodeLabelEntity> = emptyList(),
    val defs: List<PropertyDefEntity> = emptyList(),
    val smartLists: List<SmartListDefEntity> = emptyList(),
    val ink: List<InkStrokeEntity> = emptyList(),
    val problems: List<String> = emptyList(),
)

/**
 * Reads a whole workspace into index rows — the second half of Phase 2.
 *
 * Two things can only be resolved with every page in hand, which is why this exists on top of
 * [PageMapper] rather than inside it:
 *
 *  - **A nested page's title lives on its parent's line**, not in its own frontmatter. Resolving it
 *    means having read the parent.
 *  - **Labels are written by name and stored by id.** The registry is the workspace's, so a tag
 *    typed on one device is the same tag on another; a name with no entry yet gets one, coloured
 *    from the palette exactly as the app would colour it.
 */
object WorkspaceReconciler {

    fun read(store: WorkspaceStore, now: Long, zone: ZoneId = ZoneId.systemDefault()): WorkspaceIndex {
        val pages = store.readPages()
        val problems = ArrayList<String>()

        val mapped = pages.map { PageMapper.toRows(it, zone) }

        // Every row a *line* produced, by id. For a task that also owns a page these are the two
        // halves of one node, and the line holds the half the file does not: what it is called,
        // whether it is done, how deep it sits, and where among its siblings.
        val fromLine = mapped.flatMap { it.children }.associateBy { it.id }
        val pageIds = pages.mapTo(HashSet()) { it.id }

        val nodes = ArrayList<NodeEntity>()
        val values = ArrayList<PropertyValueEntity>()
        val links = ArrayList<LabelLink>()
        val ink = ArrayList<InkStrokeEntity>()

        mapped.forEach { m ->
            val parent = m.page.parentId
            if (parent != null && parent !in pageIds) {
                // Kept, not dropped: the content is real even if its home is missing.
                problems += "page ${m.page.id} names a parent (${parent}) that is not in this workspace"
            }
            val line = fromLine[m.page.id]
            val resolved = when {
                parent == null -> m.page
                line == null -> {
                    problems += "page ${m.page.id} has no line on any page; it will be unreachable"
                    m.page
                }
                // The file supplies identity and everything below the fold; the line supplies the
                // fields that belong to a line. Taking the page row wholesale silently reset every
                // task that owned a page back to open, at indent zero, in an arbitrary order.
                else -> m.page.copy(
                    title = line.title,
                    done = line.done,
                    inProgress = line.inProgress,
                    indent = line.indent,
                    rank = line.rank,
                )
            }

            nodes += resolved
            // A page's own row is a child of its parent too, so drop the placeholder the parent's
            // line produced — the file is the fuller version of the same node.
            nodes += m.children.filterNot { it.id in pageIds }
            values += m.values
            links += m.labels
            ink += strokesFor(store, m, now, problems)
        }

        val labels = resolveLabels(store, links, now)
        return WorkspaceIndex(
            nodes = nodes,
            values = values,
            labels = labels.values.toList().sortedBy { it.name },
            nodeLabels = links.mapNotNull { l ->
                labels[l.name.lowercase()]?.let { NodeLabelEntity(l.nodeId, it.id, now) }
            }.distinctBy { it.nodeId to it.labelId },
            defs = store.readProperties().map {
                PropertyDefEntity(
                    id = it.id, name = it.name, kind = it.kind, config = it.config,
                    isBuiltIn = true, createdAt = now, updatedAt = now,
                )
            },
            smartLists = store.readSmartLists().map {
                SmartListDefEntity(
                    nodeId = it.nodeId, scopeRootId = it.scopeRootId, filterJson = it.filterJson,
                    sortJson = it.sortJson, homeParentId = it.homeParentId,
                    applyOnCreateJson = it.applyOnCreateJson,
                )
            },
            ink = ink,
            problems = problems,
        )
    }

    private fun strokesFor(
        store: WorkspaceStore,
        m: MappedPage,
        now: Long,
        problems: MutableList<String>,
    ): List<InkStrokeEntity> = m.inkNodeIds.flatMap { nodeId ->
        val blobs = store.readInk(nodeId)
        if (blobs.isEmpty() && !store.inkFile(nodeId).exists()) {
            problems += "page ${m.page.id} references ink $nodeId but no sidecar is present"
        }
        var rank = Rank.after(null)
        blobs.map { data ->
            InkStrokeEntity(
                id = "$nodeId#${rank}", nodeId = nodeId, data = data,
                rank = rank, createdAt = now, updatedAt = now,
            ).also { rank = Rank.after(rank) }
        }
    }

    /**
     * Names to label rows, reusing the workspace registry and inventing what is missing.
     *
     * Matching is case-insensitive because people type tags casually, but the registry's spelling
     * wins — otherwise `#Sync` on one device and `#sync` on another would be two tags that look
     * like one.
     */
    private fun resolveLabels(
        store: WorkspaceStore,
        links: List<LabelLink>,
        now: Long,
    ): Map<String, LabelEntity> {
        val byName = LinkedHashMap<String, LabelEntity>()
        store.readLabels().forEach {
            byName[it.name.lowercase()] = LabelEntity(it.id, it.name, it.color, now, now)
        }
        links.forEach { link ->
            val key = link.name.lowercase()
            if (key !in byName) {
                byName[key] = LabelEntity(
                    id = "label-$key",
                    name = link.name,
                    color = LabelPalette.defaultFor(link.name),
                    createdAt = now, updatedAt = now,
                )
            }
        }
        return byName
    }
}
