package ie.napkin.supertasks.data.workspace

import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.format.Block
import ie.napkin.supertasks.data.format.Bullet
import ie.napkin.supertasks.data.format.Heading
import ie.napkin.supertasks.data.format.ImageRef
import ie.napkin.supertasks.data.format.InkRef
import ie.napkin.supertasks.data.format.Numbered
import ie.napkin.supertasks.data.format.PageDoc
import ie.napkin.supertasks.data.format.Prose
import ie.napkin.supertasks.data.format.PageCodec
import ie.napkin.supertasks.data.format.TaskStatus
import ie.napkin.supertasks.data.format.TaskRef
import ie.napkin.supertasks.data.db.SystemKey
import ie.napkin.supertasks.data.sync.Change
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/**
 * Every write the app makes, expressed as a change to a file.
 *
 * This is where the inversion actually happens. The repositories used to hand a row to Room and be
 * done; now they hand a transformation to this, which rewrites the page it belongs to and then
 * rebuilds the index from what is on disk. The order is the point — **the file changes first, and
 * Room only ever learns what the files already say.** Nothing can end up in the index that is not in
 * the repo, so nothing can be lost by throwing the index away.
 *
 * Almost every mutation turns out to touch exactly one file: the page holding the line. A task's
 * title, its status, its indent, its position, its due date, its labels — all of those live on the
 * line, so changing any of them rewrites the parent's page and nothing else. Only creating,
 * deleting and re-homing touch two.
 */
class WorkspaceWriter(
    private val store: WorkspaceStore,
    private val db: AppDatabase,
    private val indexer: Indexer,
    private val device: String? = null,
    /**
     * Where a deferred index rebuild runs. Null rebuilds inline after every write, which is what
     * tests want and what any one-shot caller should get.
     */
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
    /**
     * Told about every write that actually happened, so something can decide when files become a
     * commit. Reported here rather than by each repository because a mutation that changed nothing
     * must not count — and this is the only place that knows whether one did.
     */
    private val onChange: (Change) -> Unit = {},
) {
    /**
     * One writer at a time.
     *
     * Read-modify-write on a whole file has no smaller unit to be atomic at: two coroutines editing
     * different tasks on the same page would each load it, apply their own change, and write it
     * back, and whichever finished second would erase the other. The UI is happy to fire several
     * of these at once — a rename racing an indent change is ordinary.
     */
    private val mutex = Mutex()

    private fun now() = System.currentTimeMillis()

    private fun newId(): String = UUID.randomUUID().toString()

    /** Rebuilds the index from disk. Returns anything the workspace could not resolve. */
    suspend fun reindex(): List<String> = indexer.rebuild(store)

    private var pendingIndex: kotlinx.coroutines.Job? = null
    private var indexOwed = false

    /**
     * Brings the index up to date after a write — at once, or after the typing stops.
     *
     * The file is already written by the time this runs; the index is a cache of what the files say,
     * and rebuilding it is by far the most expensive part of a write. Measured on an S24, a workspace
     * of thirty pages spent about 45ms of that per keystroke and one of eighty pages about 135ms,
     * which is a keyboard the app cannot keep up with.
     *
     * So a plain edit defers, exactly as a burst of strokes does and for the same reason: the text
     * being typed is already on screen from the field's own state, nothing else on screen depends on
     * it mid-word, and a deferred rebuild can never be *wrong* — it reads the files, which are always
     * current. The worst it can be is late.
     *
     * A **structural** change never defers. Creating, deleting, completing or moving a block changes
     * what the list contains, and that list is drawn from the index — defer it and the new task does
     * not appear until the timer fires.
     */
    private suspend fun refreshIndex(change: Change) {
        val scope = scope
        if (scope == null || change != Change.EDIT) {
            pendingIndex?.cancel()
            pendingIndex = null
            indexOwed = false
            indexer.rebuild(store)
            return
        }
        indexOwed = true
        pendingIndex?.cancel()
        pendingIndex = scope.launch {
            delay(INDEX_QUIET_MS)
            // Fresh acquisition: the caller is holding the mutex right now and will release it long
            // before this runs.
            mutex.withLock {
                if (indexOwed) {
                    indexer.rebuild(store)
                    indexOwed = false
                }
            }
        }
    }

    /**
     * Rebuilds anything a deferred edit still owes, now.
     *
     * For leaving the app: the files are safe either way, but coming back to an index that is one
     * edit behind would show the old title until something else happened to trigger a rebuild.
     */
    suspend fun flushIndex() {
        pendingIndex?.cancel()
        mutex.withLock {
            if (indexOwed) {
                indexer.rebuild(store)
                indexOwed = false
            }
        }
    }

    // ---- pages ----

    /**
     * Loads a page, applies [transform], writes it back, and reindexes.
     *
     * [PageDoc.modifiedAt] is stamped here rather than by callers, so the LWW clock cannot be
     * forgotten by whichever mutation is added next. It is the field the conflict resolver reads,
     * and a page that lies about when it changed loses arbitrations it should win.
     */
    suspend fun editPage(
        pageId: String,
        change: Change = Change.EDIT,
        transform: (PageDoc) -> PageDoc,
    ): Unit = mutex.withLock {
        val page = loadPage(pageId) ?: return
        val next = transform(page)

        // An edit that changes nothing writes nothing. The UI asks for these constantly — a tap
        // that lands on a row, a rename to the text already there — and honouring them would bump
        // modified_at each time. That is not merely wasteful: modified_at is what the conflict
        // resolver arbitrates on, so a page that keeps claiming to be newer would start winning
        // against real edits made elsewhere, and the sync history would fill with empty diffs.
        if (next.copy(modifiedAt = page.modifiedAt, device = page.device) == page) return

        store.writePage(next.copy(modifiedAt = Instant.ofEpochMilli(now()), device = device))
        refreshIndex(change)
        onChange(change)
    }

    // Through the store's cache rather than straight off disk: every write loads the page it is
    // about, and the rebuild that follows immediately parses the same file again.
    private fun loadPage(pageId: String): PageDoc? = store.readPage(pageId)

    /** A page with no parent: a list, a group, a smart list. Its title lives in its own frontmatter. */
    suspend fun createTopLevel(type: String, title: String?, systemKey: String? = null): String =
        mutex.withLock {
            val id = newId()
            store.writePage(
                PageDoc(
                    id = id, type = type, parent = null, title = title, systemKey = systemKey,
                    modifiedAt = Instant.ofEpochMilli(now()), device = device, blocks = emptyList(),
                )
            )
            refreshIndex(Change.STRUCTURAL)
            onChange(Change.STRUCTURAL)
            id
        }

    /**
     * Makes sure a task has a page of its own, because it is about to hold something.
     *
     * A task with nothing under it is only a line; giving every one a file would fill the repo with
     * empty documents and make every clone slower for nothing. The file appears the moment there is
     * something to put in it.
     */
    private suspend fun ensurePage(id: String) {
        if (store.pageFile(id).exists()) return
        val row = db.nodeDao().byId(id) ?: return
        store.writePage(
            PageDoc(
                id = id, type = row.type, parent = row.parentId,
                // Only a top-level page carries its own title; anything nested is named by its line.
                title = row.title?.takeIf { row.parentId == null },
                systemKey = row.systemKey,
                modifiedAt = Instant.ofEpochMilli(now()), device = device, blocks = emptyList(),
            )
        )
    }

    // ---- blocks on a page ----

    /**
     * Adds a block to [pageId], after [afterId] or at the end.
     *
     * Returns the new block's id. For anything that is not a task the id is positional and will be
     * regenerated by the next reindex — see [PageMapper.blockId] — so it is only good for the call
     * that made it.
     */
    suspend fun addBlock(
        pageId: String,
        type: String,
        title: String?,
        afterId: String? = null,
        indent: Int = 0,
    ): String = mutex.withLock {
        val id = if (type == NodeType.TASK || type == NodeType.INK) newId() else ""
        // A task is only a line until it holds something — this is the moment it earns a document.
        // Without this, the first block added to any task went nowhere at all: no page to load, no
        // write, no error, and a screen that looked dead to the touch.
        ensurePage(pageId)
        val page = loadPage(pageId) ?: return@withLock ""
        val block = blockOf(type, id, title.orEmpty(), indent)
        val at = page.blocks.indexOfFirst { blockIdOf(it, page.id, page.blocks) == afterId }
        val blocks = page.blocks.toMutableList()
        if (afterId != null && at >= 0) blocks.add(at + 1, block) else blocks += block

        store.writePage(page.copy(blocks = blocks, modifiedAt = Instant.ofEpochMilli(now()), device = device))
        refreshIndex(Change.STRUCTURAL)
        onChange(Change.STRUCTURAL)
        if (id.isNotEmpty()) id else PageMapper.blockId(pageId, blocks.indexOf(block))
    }

    /** Applies [transform] to whichever block on whichever page carries [nodeId]. */
    suspend fun editBlock(
        nodeId: String,
        change: Change = Change.EDIT,
        transform: (Block) -> Block,
    ) {
        val home = homePageOf(nodeId) ?: return
        editPage(home, change) { page ->
            page.copy(
                blocks = page.blocks.mapIndexed { i, b ->
                    if (blockIdOf(b, page.id, page.blocks, i) == nodeId) transform(b) else b
                }
            )
        }
    }

    /** [transform] applied only if the block is a task line; other kinds are left alone. */
    suspend fun editTask(
        nodeId: String,
        change: Change = Change.EDIT,
        transform: (TaskRef) -> TaskRef,
    ) = editBlock(nodeId, change) { if (it is TaskRef) transform(it) else it }

    /** Moves a block to [toIndex] among its siblings, which is what reordering *is* in this format. */
    suspend fun moveBlock(nodeId: String, toIndex: Int) {
        val home = homePageOf(nodeId) ?: return
        editPage(home) { page ->
            val from = page.blocks.indexOfFirst { i -> blockIdOf(i, page.id, page.blocks) == nodeId }
            if (from < 0) return@editPage page
            val blocks = page.blocks.toMutableList()
            val moved = blocks.removeAt(from)
            blocks.add(toIndex.coerceIn(0, blocks.size), moved)
            page.copy(blocks = blocks)
        }
    }

    /**
     * Removes a block, and the page and sidecars it owned.
     *
     * The line goes first and the files second, so a crash between the two leaves an orphaned page
     * — which the reconciler reports — rather than a line pointing at nothing, which would render
     * as a task whose page opens empty.
     */
    suspend fun removeBlock(nodeId: String) {
        // A top-level node — a list, a smart list, a group — is not a line on anything. It *is* a
        // page, and its parent is null, which this used to read as "nothing to remove" and return.
        // The effect was that deleting a top-level list did nothing at all: no line to strike out
        // was taken to mean no node to delete. Only lists nested inside a group could be deleted,
        // which is why it looked like it worked at all.
        //
        // deletePage already walks the whole subtree and takes the sidecars with it, so the page is
        // the only thing that has to be named here.
        val home = homePageOf(nodeId)
        if (home == null) {
            mutex.withLock {
                store.deletePage(nodeId)
                refreshIndex(Change.STRUCTURAL)
                onChange(Change.STRUCTURAL)
            }
            return
        }

        // Which picture this block owned, resolved while the block still exists. An image block's own
        // id is positional — only tasks and ink blocks carry a generated one — so the file is named
        // by the `ImageRef`'s payload rather than by the node, and deleting `<nodeId>.jpg` would miss
        // it and leave a megabyte in the repo that nothing refers to.
        val picture = loadPage(home)?.blocks
            ?.withIndex()
            ?.firstOrNull { (i, b) -> blockIdOf(b, home, emptyList(), i) == nodeId }
            ?.value
            ?.let { (it as? ImageRef)?.uri }

        editPage(home) { page ->
            page.copy(blocks = page.blocks.filterIndexed { i, b ->
                blockIdOf(b, page.id, page.blocks, i) != nodeId
            })
        }
        mutex.withLock {
            store.deletePage(nodeId)
            // Both sidecars a block can own. An image left behind is worse than a stray stroke file:
            // it is a megabyte, it is committed, and nothing on any device refers to it again.
            store.inkFile(nodeId).delete()
            picture?.let { store.imageFile(it).delete() }
            refreshIndex(Change.STRUCTURAL)
            onChange(Change.STRUCTURAL)
        }
    }

    /** Re-homes a page: its own frontmatter moves, and so does the line that points at it. */
    suspend fun reparent(nodeId: String, newParent: String?) = mutex.withLock {
        val old = homePageOf(nodeId)
        val page = loadPage(nodeId)
        var line: Block? = null

        if (old != null) {
            loadPage(old)?.let { p ->
                line = p.blocks.firstOrNull { blockIdOf(it, p.id, p.blocks) == nodeId }
                store.writePage(
                    p.copy(
                        blocks = p.blocks.filterNot { blockIdOf(it, p.id, p.blocks) == nodeId },
                        modifiedAt = Instant.ofEpochMilli(now()), device = device,
                    )
                )
            }
        }
        if (newParent != null) {
            // The new home may be a task that has never held anything and so has no file yet.
            ensurePage(newParent)
            loadPage(newParent)?.let { p ->
                val moved = line ?: TaskRef(id = nodeId, title = "")
                store.writePage(
                    p.copy(blocks = p.blocks + moved, modifiedAt = Instant.ofEpochMilli(now()), device = device)
                )
            }
        }
        page?.let {
            store.writePage(it.copy(parent = newParent, modifiedAt = Instant.ofEpochMilli(now()), device = device))
        }
        refreshIndex(Change.STRUCTURAL)
        onChange(Change.STRUCTURAL)
    }

    // ---- structure ----

    /**
     * The one rule indentation obeys: the first line of a run sits flush left, and no line is more
     * than one step deeper than the line above it. Anything else cannot be read as structure.
     *
     * Indent is stored rather than derived, so it does not stay true on its own — deleting the line
     * above one, or dragging a block to the top, can leave an indent with nothing to be indented
     * under. Every operation that changes a run ends here.
     */
    suspend fun normalizeIndents(pageId: String) = editPage(pageId) { page ->
        var ceiling = 0
        page.copy(
            blocks = page.blocks.map { b ->
                val fixed = b.indent.coerceIn(0, ceiling)
                ceiling = fixed + 1
                if (fixed == b.indent) b else withIndent(b, fixed)
            }
        )
    }

    /**
     * Changes a block's kind, keeping its text and depth.
     *
     * Refuses to convert a task that owns a page: its id lives in the `^…` marker and nothing else
     * carries one, so becoming a paragraph would leave the page with no line pointing at it and
     * everything on it unreachable. Better to decline than to strand it.
     */
    suspend fun convertBlock(nodeId: String, type: String) {
        if (type != NodeType.TASK && store.pageFile(nodeId).exists()) {
            if (loadPage(nodeId)?.blocks?.isNotEmpty() == true) return
            store.deletePage(nodeId)
        }
        editBlock(nodeId) { b ->
            val text = when (b) {
                is TaskRef -> b.title
                is Heading -> b.text
                is Bullet -> b.text
                is Numbered -> b.text
                is Prose -> b.text
                is ImageRef -> b.uri
                is InkRef -> ""
            }
            val id = if (b is TaskRef) b.id else nodeId
            blockOf(type, id, text, b.indent)
        }
    }

    private fun withIndent(b: Block, indent: Int): Block = when (b) {
        is TaskRef -> b.copy(indent = indent)
        is Heading -> b.copy(indent = indent)
        is Bullet -> b.copy(indent = indent)
        is Numbered -> b.copy(indent = indent)
        is Prose -> b.copy(indent = indent)
        is InkRef -> b.copy(indent = indent)
        is ImageRef -> b.copy(indent = indent)
    }

    /**
     * Claims a stable identity for a page — `today`, `inbox`.
     *
     * Writes the file, not the row: the key is frontmatter, so stamping it only in the index would
     * hold until the next reindex and then quietly come undone, which is exactly the sort of bug
     * that looks like the app forgetting things at random.
     */
    suspend fun setSystemKey(pageId: String, key: String) =
        editPage(pageId) { it.copy(systemKey = key) }

    // ---- registries ----

    /** A smart list is a page with no blocks; its rule lives beside it in the workspace meta. */
    suspend fun createSmartList(def: SmartListDef, title: String, systemKey: String? = null): String =
        mutex.withLock {
            val id = def.nodeId.ifEmpty { newId() }
            store.writePage(
                PageDoc(
                    id = id, type = NodeType.SMART_LIST, parent = null, title = title,
                    systemKey = systemKey, modifiedAt = Instant.ofEpochMilli(now()),
                    device = device, blocks = emptyList(),
                )
            )
            store.writeSmartList(def.copy(nodeId = id))
            refreshIndex(Change.STRUCTURAL)
            onChange(Change.STRUCTURAL)
            id
        }

    suspend fun updateSmartList(def: SmartListDef) = mutex.withLock {
        store.writeSmartList(def)
        // Not deferred despite being an edit: changing a rule changes which tasks a list contains,
        // and that list is drawn from the index.
        refreshIndex(Change.STRUCTURAL)
        onChange(Change.EDIT)
    }

    /** The label registry is the workspace's, so a tag typed on one device is the same on another. */
    suspend fun upsertLabel(label: LabelDef) = mutex.withLock {
        val kept = store.readLabels().filterNot { it.id == label.id }
        store.writeLabels(kept + label)
        // A renamed or recoloured label is visible on every chip carrying it; deferring would leave
        // the old colour on screen for no reason anyone could see.
        refreshIndex(Change.STRUCTURAL)
        onChange(Change.EDIT)
    }

    /** One line, appended. Never rewritten — that is what keeps two offline devices from colliding. */
    suspend fun appendFocus(line: String, month: String) = mutex.withLock {
        store.appendFocus(line, month)
        refreshIndex(Change.STRUCTURAL)
        onChange(Change.EDIT)
    }

    // ---- ink ----

    /**
     * Replaces an ink block's strokes wholesale. Sidecars are never merged — see the plan.
     *
     * Whole-list only, and there is no append. The drawing screen holds the session and hands over
     * the complete set, so no caller ever reads the current strokes and writes them back — which is
     * what lost strokes when several finished at once, each having read before any had written.
     */
    suspend fun writeInk(nodeId: String, strokes: List<ByteArray>) = mutex.withLock {
        store.writeInk(nodeId, strokes)
        refreshIndex(Change.INK)
        onChange(Change.INK)
    }

    /**
     * Puts a picture in the workspace. Written before the block that names it, so the file is never
     * referenced by a page that has already been indexed.
     */
    suspend fun writeImage(id: String, bytes: ByteArray) = mutex.withLock {
        store.writeImage(id, bytes)
        // Committed like ink: a binary blob that arrives occasionally and is worth its own commit
        // rather than being batched behind a burst of typing.
        onChange(Change.INK)
    }

    // ---- archive ----

    /**
     * Moves finished work out of the working set.
     *
     * A task done longer ago than [before] leaves its page: the line is appended to
     * `archive/<pageId>.md` and its own page, if it had one, moves to `archive/pages/`. Both stay in
     * the repo and neither is indexed, which is the whole point — "a few hundred active tasks" has to
     * stay true while the total grows forever, and a list like Inbox otherwise carries every task it
     * has ever held.
     *
     * Nothing is deleted and nothing is irreversible; see [restoreArchived].
     *
     * A finished task whose subtasks are *not* finished stays put. Archiving it would take its
     * children out of the working set with it, and unfinished work must never leave by accident.
     */
    suspend fun archiveFinished(before: java.time.LocalDate): Int = mutex.withLock {
        var moved = 0
        store.readPages().forEach { page ->
            val leaving = page.blocks.filterIsInstance<TaskRef>().filter { t ->
                t.status == TaskStatus.DONE &&
                    t.doneAt != null && t.doneAt < before &&
                    !hasUnfinishedChildren(t.id)
            }
            if (leaving.isEmpty()) return@forEach

            val lines = leaving.map { PageCodec.encodeBlock(it) }
            store.writeArchivedLines(page.id, store.readArchivedLines(page.id) + lines)
            leaving.forEach { store.moveToArchive(it.id) }

            val ids = leaving.mapTo(HashSet()) { it.id }
            store.writePage(
                page.copy(
                    blocks = page.blocks.filterNot { it is TaskRef && it.id in ids },
                    modifiedAt = Instant.ofEpochMilli(now()),
                    device = device,
                )
            )
            moved += leaving.size
        }
        if (moved > 0) {
            refreshIndex(Change.STRUCTURAL)
            onChange(Change.STRUCTURAL)
        }
        moved
    }

    /**
     * Brings a page's archived tasks back.
     *
     * Finishing something is not the same as being finished with it, so this exists and is not a
     * convenience: an archive you cannot come back from is a delete with a longer name.
     */
    suspend fun restoreArchived(pageId: String, taskIds: Set<String>): Int = mutex.withLock {
        val archived = store.readArchivedLines(pageId)
        if (archived.isEmpty()) return@withLock 0

        val decoded = archived.map { it to PageCodec.decodeBlock(it) }
        val coming = decoded.filter { (_, b) -> b is TaskRef && b.id in taskIds }
        if (coming.isEmpty()) return@withLock 0

        val page = loadPage(pageId) ?: return@withLock 0
        store.writePage(
            page.copy(
                blocks = page.blocks + coming.map { it.second },
                modifiedAt = Instant.ofEpochMilli(now()),
                device = device,
            )
        )
        coming.forEach { (_, b) -> (b as? TaskRef)?.let { store.restoreFromArchive(it.id) } }
        store.writeArchivedLines(pageId, decoded.filterNot { it in coming }.map { it.first })

        refreshIndex(Change.STRUCTURAL)
        onChange(Change.STRUCTURAL)
        coming.size
    }

    /** How many finished tasks are currently out of the working set. */
    fun archivedCount(): Int = store.archivedPageIds().sumOf { store.readArchivedLines(it).size }

    /**
     * Brings everything back, from every page.
     *
     * The escape hatch, and the reason archiving can be offered at all before there is a screen for
     * browsing it: whatever happens, one action undoes the whole thing. A feature that moves your
     * work needs a way back that requires no understanding of where it went.
     */
    suspend fun restoreAllArchived(): Int {
        var restored = 0
        store.archivedPageIds().forEach { pageId ->
            val ids = store.readArchivedLines(pageId)
                .mapNotNull { (PageCodec.decodeBlock(it) as? TaskRef)?.id }
                .toSet()
            if (ids.isNotEmpty()) restored += restoreArchived(pageId, ids)
        }
        return restored
    }

    /** True when anything beneath [taskId] is still open. Read from the index, which is a fine map. */
    private suspend fun hasUnfinishedChildren(taskId: String): Boolean =
        db.nodeDao().childrenOnce(taskId).any { !it.done }

    // ---- helpers ----

    /** Which page holds this node's line. The index is a fine lookup even though files are truth. */
    private suspend fun homePageOf(nodeId: String): String? =
        db.nodeDao().byId(nodeId)?.parentId

    private fun blockOf(type: String, id: String, text: String, indent: Int): Block = when (type) {
        NodeType.TASK -> TaskRef(id = id, title = text, indent = indent)
        NodeType.HEADING -> Heading(text, indent)
        NodeType.BULLET -> Bullet(text, indent)
        NodeType.NUMBERED -> Numbered(text, indent)
        NodeType.INK -> InkRef(id, indent)
        NodeType.IMAGE -> ImageRef(text, indent)
        else -> Prose(text, indent)
    }

    private fun blockIdOf(b: Block, pageId: String, all: List<Block>, index: Int = -1): String =
        when (b) {
            is TaskRef -> b.id.ifEmpty { PageMapper.blockId(pageId, if (index >= 0) index else all.indexOf(b)) }
            is InkRef -> b.id
            else -> PageMapper.blockId(pageId, if (index >= 0) index else all.indexOf(b))
        }

    private companion object {
        /**
         * How long typing has to stop before the index catches up.
         *
         * Long enough to swallow a burst at any plausible typing speed, short enough that stepping
         * back to a list right after typing does not show the old title for a visible beat. This is
         * not the commit cadence — [ie.napkin.supertasks.data.sync.CommitPolicy] measures that in
         * seconds, because a commit is a much larger thing than refreshing a cache.
         */
        const val INDEX_QUIET_MS = 200L
    }

}
