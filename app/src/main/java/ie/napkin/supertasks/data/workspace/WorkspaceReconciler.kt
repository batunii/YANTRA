package ie.napkin.supertasks.data.workspace

import ie.napkin.supertasks.data.db.InkStrokeEntity
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeLabelEntity
import ie.napkin.supertasks.data.db.FocusOutcome
import ie.napkin.supertasks.data.db.FocusSessionEntity
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
    val focus: List<FocusSessionEntity> = emptyList(),
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
        val knownNodes = nodes.mapTo(HashSet()) { it.id }
        // Checked against the rows that are emitted, not the map they came from. The two agree only
        // because one is built out of the other, and that is precisely the kind of agreement an edit
        // breaks without noticing: appending a label to this list and not to the map is how the
        // foreign key came to fail in the first place.
        val labelRows = labels.values.sortedBy { it.name }
        val knownLabels = labelRows.mapTo(HashSet()) { it.id }
        return WorkspaceIndex(
            nodes = nodes,
            values = values,
            labels = labelRows,
            nodeLabels = links
                .mapNotNull { l ->
                    labels[l.name.lowercase()]?.let { NodeLabelEntity(l.nodeId, it.id, ws, stamp) }
                }
                .distinctBy { it.nodeId to it.labelId }
                .filter { keep(it, knownNodes, knownLabels, problems) },
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
            // Every node, not only the pages. A session belongs to whatever you focused on, and
            // most tasks never own a page — a page exists only once a task holds something. Checking
            // against page ids therefore threw away the sessions of every plain task: appended to the
            // log, dropped by the very next rebuild, and reported as naming a task the workspace does
            // not have. The foreign key is on `node`, which is exactly this set.
            focus = readSessions(store, knownNodes, ws, problems),
            problems = problems,
        )
    }

    /**
     * Whether a label attachment names rows this index actually has — a floor under the reindex.
     *
     * Unlike [readSessions], which filters a *file* that can honestly go stale, both ends of an
     * attachment are generated by this very pass. An orphan here is therefore always a bug in the
     * code above, never something a working tree did, and it is deliberately not the mechanism that
     * makes attachments correct: [resolveLabels] is, by minting every label id in one map so a name
     * cannot produce two. This only decides what a bug of that shape costs.
     *
     * It has to exist because the cost was the whole app. `node_label` has a foreign key on each
     * end, and a violation is an exception on a background dispatcher inside the launch job — the
     * app died on its splash screen, on every start, with no screen reachable to fix it from. One
     * tag quietly missing and a line in [WorkspaceIndex.problems] is a bad outcome; it is not that
     * outcome. Loud enough to find, cheap enough that nobody is locked out while it is being found.
     */
    private fun keep(
        link: NodeLabelEntity,
        knownNodes: Set<String>,
        knownLabels: Set<String>,
        problems: MutableList<String>,
    ): Boolean {
        val missing = when {
            link.nodeId !in knownNodes -> "task ${link.nodeId}"
            link.labelId !in knownLabels -> "label ${link.labelId}"
            else -> return true
        }
        problems += "a label attachment names a $missing this workspace does not have; dropped " +
            "(this is a bug in indexing, not something the files did)"
        return false
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
    ): List<FocusSessionEntity> = store.also {
        // Here rather than only on append, so a device that reads a workspace without ever focusing
        // in it still moves the old directory rather than leaving it to sit under the wrong name.
        it.migrateLegacyFocusDir()
    }.readFocus().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 7) { problems += "unreadable focus line: $line"; return@mapNotNull null }
        val nodeId = f[1]
        if (nodeId !in knownNodes) {
            problems += "focus session ${f[0]} names a task ($nodeId) this workspace does not have"
            return@mapNotNull null
        }
        val started = f[2].toLongOrNull() ?: return@mapNotNull null
        FocusSessionEntity(
            id = f[0], workspaceId = ws, nodeId = nodeId, startedAt = started,
            endedAt = f[3].toLongOrNull(), plannedSecs = f[4].toIntOrNull() ?: 0,
            actualSecs = f[5].toIntOrNull(),
            // Field 8 is new. A line written by an older build has only the boolean, so it is read
            // the way that build meant it: finished means the target was reached, and anything else
            // is a session that stopped — which is true, and is all the old field ever knew.
            outcome = f.getOrNull(7)?.takeIf { it.isNotEmpty() }
                ?: if (f[6] == "1") FocusOutcome.RAN_OUT else FocusOutcome.STOPPED,
            createdAt = started, updatedAt = f[3].toLongOrNull() ?: started,
        )
    }
        // A session is appended once when it starts and again when it ends, so the last line for an
        // id is the current one. Keeping both would leave every finished session looking open.
        .associateBy { it.id }.values.toList()

    /** The inverse of [readSessions]; the writer appends whatever this returns. */
    fun sessionLine(s: FocusSessionEntity): String = listOf(
        s.id, s.nodeId, s.startedAt, s.endedAt ?: "", s.plannedSecs,
        s.actualSecs ?: "",
        // The old boolean, still written, so a build from before the outcome existed keeps reading
        // these lines correctly rather than skipping them.
        if (s.outcome == FocusOutcome.RAN_OUT) "1" else "0",
        s.outcome,
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

    /**
     * Names to label rows, reusing the workspace registry and inventing what is missing.
     *
     * Matching is case-insensitive because people type tags casually, but the registry's spelling
     * wins — otherwise `#Sync` on one device and `#sync` on another would be two tags that look
     * like one.
     *
     * Every label id is minted here and nowhere else, which is what makes a name produce one row
     * rather than two. `label` is unique on (workspace_id, name), so two ids for one name is not a
     * duplicate the database tolerates — it resolves the argument by replacing one, and whatever
     * still points at the loser fails its foreign key. That happened: the workspace's own derived
     * label was minted separately and appended past this map, a repo called `v2-tasks` met a
     * `#v2-tasks` tag, and the reindex took the app's launch down with it. That label is now
     * [Filter.InWorkspace] and no longer a label at all, but the rule it broke is the one worth
     * keeping: nothing outside this function decides what a label is called or called by.
     */
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
