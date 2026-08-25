package ie.napkin.supertasks.data.workspace

import ie.napkin.supertasks.data.db.InkStrokeEntity
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeLabelEntity
import ie.napkin.supertasks.data.db.PomodoroSessionEntity
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
    val pomodoro: List<PomodoroSessionEntity> = emptyList(),
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

    /**
     * [now] is accepted for callers that have a clock to hand, but **nothing derived from it ends up
     * in a row**. See [stampFor].
     */
    /**
     * [mapCache] lets a caller that rebuilds repeatedly — which is every caller, since the index is
     * rebuilt after every write — reuse the mapping of pages that did not change. Keyed by page id
     * and validated by *identity*: [WorkspaceStore] returns the same [PageDoc] instance while a file
     * is untouched, so same instance means same file. Omit it and every page is mapped afresh.
     */
    fun read(
        store: WorkspaceStore,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        mapCache: MutableMap<String, Pair<PageDoc, MappedPage>>? = null,
    ): WorkspaceIndex {
        val pages = store.readPages()
        val problems = ArrayList<String>()

        val ws = store.id
        val stamp = stampFor(store, now)
        val mapped = pages.map { page ->
            val hit = mapCache?.get(page.id)
            if (hit != null && hit.first === page) hit.second
            else PageMapper.toRows(page, ws, zone).also { mapCache?.put(page.id, page to it) }
        }
        // A page that is gone stays in the cache otherwise, one entry per deleted page forever.
        mapCache?.keys?.retainAll(pages.mapTo(HashSet()) { it.id })

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
            ink += strokesFor(store, m, stamp, problems, ws)
        }

        val labels = resolveLabels(store, links, stamp, ws)
        val workspaceLabel = workspaceLabel(store, ws, stamp)
        val workspaceLinks = if (workspaceLabel == null) emptyList() else
            nodes.filter { it.type == ie.napkin.supertasks.data.db.NodeType.TASK }
                .map { NodeLabelEntity(it.id, workspaceLabel.id, ws, stamp) }
        return WorkspaceIndex(
            nodes = nodes,
            values = values,
            labels = (labels.values + listOfNotNull(workspaceLabel)).sortedBy { it.name },
            nodeLabels = (
                links.mapNotNull { l ->
                    labels[l.name.lowercase()]?.let { NodeLabelEntity(l.nodeId, it.id, ws, stamp) }
                } + workspaceLinks
                ).distinctBy { it.nodeId to it.labelId },
            defs = store.readProperties().map {
                PropertyDefEntity(
                    id = it.id, name = it.name, kind = it.kind, config = it.config,
                    isBuiltIn = true, createdAt = stamp, updatedAt = stamp,
                )
            },
            smartLists = store.readSmartLists().map {
                SmartListDefEntity(
                    nodeId = it.nodeId, workspaceId = ws, scopeRootId = it.scopeRootId, filterJson = it.filterJson,
                    sortJson = it.sortJson, homeParentId = it.homeParentId,
                    applyOnCreateJson = it.applyOnCreateJson,
                )
            },
            ink = ink,
            pomodoro = readSessions(store, pageIds, ws, problems),
            problems = problems,
        )
    }

    /**
     * One line per focus session. A line naming a task this workspace does not have is reported and
     * skipped rather than inserted — the foreign key would reject it anyway, and failing the whole
     * reindex over one stale log line would take the rest of the workspace down with it.
     */
    private fun readSessions(
        store: WorkspaceStore,
        knownNodes: Set<String>,
        ws: String,
        problems: MutableList<String>,
    ): List<PomodoroSessionEntity> = store.readPomodoro().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 7) { problems += "unreadable pomodoro line: $line"; return@mapNotNull null }
        val nodeId = f[1]
        if (nodeId !in knownNodes) {
            problems += "pomodoro session ${f[0]} names a task ($nodeId) this workspace does not have"
            return@mapNotNull null
        }
        val started = f[2].toLongOrNull() ?: return@mapNotNull null
        PomodoroSessionEntity(
            id = f[0], workspaceId = ws, nodeId = nodeId, startedAt = started,
            endedAt = f[3].toLongOrNull(), plannedSecs = f[4].toIntOrNull() ?: 0,
            actualSecs = f[5].toIntOrNull(), completed = f[6] == "1",
            createdAt = started, updatedAt = f[3].toLongOrNull() ?: started,
        )
    }
        // A session is appended once when it starts and again when it ends, so the last line for an
        // id is the current one. Keeping both would leave every finished session looking open.
        .associateBy { it.id }.values.toList()

    /** The inverse of [readSessions]; the writer appends whatever this returns. */
    fun sessionLine(s: PomodoroSessionEntity): String = listOf(
        s.id, s.nodeId, s.startedAt, s.endedAt ?: "", s.plannedSecs,
        s.actualSecs ?: "", if (s.completed) "1" else "0",
    ).joinToString("\t")

    private fun strokesFor(
        store: WorkspaceStore,
        m: MappedPage,
        now: Long,
        problems: MutableList<String>,
        ws: String,
    ): List<InkStrokeEntity> = m.inkNodeIds.flatMap { nodeId ->
        val blobs = store.readInk(nodeId)
        if (blobs.isEmpty() && !store.inkFile(nodeId).exists()) {
            problems += "page ${m.page.id} references ink $nodeId but no sidecar is present"
        }
        var rank = Rank.after(null)
        blobs.map { data ->
            InkStrokeEntity(
                id = "$nodeId#${rank}", workspaceId = ws, nodeId = nodeId, data = data,
                rank = rank, createdAt = now, updatedAt = now,
            ).also { rank = Rank.after(rank) }
        }
    }

    /**
     * The label that stands for the workspace itself.
     *
     * Derived, never written to a file. Where a page came from is provenance, not content — a file
     * that claimed its own workspace would be lying the moment the repo was cloned as a second one,
     * and a hand-edit could move a task between workspaces by deleting a tag. So the column is the
     * truth and this is a view of it, generated on import so smart lists can say "everything from
     * Work" through the label machinery that already exists, with no new filter syntax.
     *
     * Null for the local workspace, which has no name and nothing to be told apart from.
     */
    private fun workspaceLabel(store: WorkspaceStore, ws: String, now: Long): LabelEntity? {
        if (ws.isEmpty()) return null
        val name = store.readManifest()?.name ?: return null
        return LabelEntity(
            id = "$ws:workspace",
            workspaceId = ws,
            name = name,
            color = LabelPalette.defaultFor(name),
            createdAt = now, updatedAt = now,
        )
    }

    /**
     * Names to label rows, reusing the workspace registry and inventing what is missing.
     *
     * Matching is case-insensitive because people type tags casually, but the registry's spelling
     * wins — otherwise `#Sync` on one device and `#sync` on another would be two tags that look
     * like one.
     */
    /**
     * A timestamp for rows whose real creation time nothing records.
     *
     * Labels, property definitions, ink strokes and label links carry `created_at`/`updated_at`
     * because the tables have the columns, and **no query reads any of them** — only `node.created_at`
     * is ever ordered by, and that comes from the page's own `modified_at`.
     *
     * Stamping those with the rebuild clock quietly cost a great deal. It made every row differ from
     * the identical row written a moment earlier, so the index could never be compared with itself
     * and every rebuild had to rewrite every table — on every keystroke. Taking the workspace's
     * creation date instead makes the index **a pure function of the working tree**, which is what it
     * always claimed to be, and lets a rebuild write only the tables that actually changed.
     */
    private fun stampFor(store: WorkspaceStore, fallback: Long): Long =
        store.readManifest()?.createdAt ?: fallback

    private fun resolveLabels(
        store: WorkspaceStore,
        links: List<LabelLink>,
        now: Long,
        ws: String,
    ): Map<String, LabelEntity> {
        val byName = LinkedHashMap<String, LabelEntity>()
        store.readLabels().forEach {
            byName[it.name.lowercase()] = LabelEntity(it.id, ws, it.name, it.color, now, now)
        }
        links.forEach { link ->
            val key = link.name.lowercase()
            if (key !in byName) {
                byName[key] = LabelEntity(
                    // Scoped: two repos may both use #sync without meaning one tag, and an
                    // unscoped id would silently merge them into the same row.
                    id = "$ws:label:$key",
                    workspaceId = ws,
                    name = link.name,
                    color = LabelPalette.defaultFor(link.name),
                    createdAt = now, updatedAt = now,
                )
            }
        }
        return byName
    }
}
