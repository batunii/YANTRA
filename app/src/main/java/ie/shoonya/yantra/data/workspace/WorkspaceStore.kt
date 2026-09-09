package ie.shoonya.yantra.data.workspace

import ie.shoonya.yantra.data.db.BuiltIns
import ie.shoonya.yantra.data.db.PropertyKind
import ie.shoonya.yantra.data.filter.FilterJson
import ie.shoonya.yantra.data.repo.SelectConfig
import ie.shoonya.yantra.data.repo.SelectOption
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.data.format.PageDoc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** `.yantra/manifest.json`. [formatVersion] is what makes an older app go read-only on a newer repo. */
@Serializable
data class Manifest(
    val formatVersion: Int = WorkspaceStore.FORMAT_VERSION,
    val name: String,
    val createdAt: Long,
    /** Bumped when history is rewritten, so other devices know to reclone rather than merge. */
    val epoch: Int = 1,
    /**
     * Days a task stays after it is finished before it leaves the working set. **Zero is never.**
     *
     * A property of the workspace rather than the device, because archiving moves files that sync:
     * a phone set to thirty days and a tablet set to ninety would disagree about what the repo
     * contains, and the shorter one would silently win. It is also hand-editable here, like
     * everything else in the format.
     *
     * Defaults to never. Archiving moves someone's data, and the first run of a new version is the
     * worst possible moment to do that unasked.
     */
    @SerialName("archive_after_days") val archiveAfterDays: Int = 0,
)

@Serializable
data class LabelDef(val id: String, val name: String, val color: Long? = null)

@Serializable
data class PropertyDef(val id: String, val name: String, val kind: String, val config: String? = null)

@Serializable
data class SmartListDef(
    val nodeId: String,
    val scopeRootId: String? = null,
    val filterJson: String,
    val sortJson: String? = null,
    val homeParentId: String? = null,
    val applyOnCreateJson: String? = null,
)

/**
 * The on-disk shape of a workspace — GIT_WORKSPACES_PLAN.md §2.
 *
 * Everything here is a plain file in a plain directory, because that is the point: the repo is the
 * product, and this class is only the part of the app that knows where things sit. It deliberately
 * knows nothing about git or Room. A workspace is valid without either.
 */
class WorkspaceStore(
    val root: File,
    /**
     * Stable id for this workspace, used to scope its rows in the shared index. The empty string is
     * the local pre-git workspace, which is what rows migrated from before workspaces belong to.
     */
    val id: String = "",
) {

    companion object {
        /**
         * 2 since ink coordinates became document units. See [ie.shoonya.yantra.data.ink.PAGE_WIDTH_DU].
         *
         * The bump is deliberately coarse — it makes an older build read-only for the whole
         * workspace, not just for its ink — because the format version is the only per-workspace
         * marker there is, and a build that would write pixel ink cannot be trusted with the rest of
         * a workspace it does not fully understand either.
         */
        const val FORMAT_VERSION = 2
        private const val META = ".yantra"
        private const val PAGES = "pages"
        private const val ARCHIVE = "archive"
        const val FOCUS_DIR = "focus"

        /** The name the focus log had while the feature was called Pomodoro. Read, never written. */
        const val LEGACY_FOCUS_DIR = "pomodoro"

        /** Strokes for one ink block: `[count][len][bytes]…`, each blob exactly what StrokeCodec wrote. */
        fun encodeInk(strokes: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeInt(strokes.size)
                strokes.forEach { d.writeInt(it.size); d.write(it) }
            }
            return out.toByteArray()
        }

        fun decodeInk(bytes: ByteArray): List<ByteArray> =
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                List(d.readInt()) { ByteArray(d.readInt()).also { b -> d.readFully(b) } }
            }
    }

    /**
     * What a cached parse was taken from. Two writes in the same millisecond could share a timestamp,
     * so length is carried too — and our own writes evict outright rather than trusting either.
     */
    private data class Stamp(val modified: Long, val length: Long)

    private class Cached<T>(val stamp: Stamp, val value: T)

    // Concurrent: the writer's mutex serialises mutations, but a sync reindex and a UI-driven read
    // can arrive on different threads.
    private val pageCache = java.util.concurrent.ConcurrentHashMap<String, Cached<PageDoc>>()
    private val inkCache = java.util.concurrent.ConcurrentHashMap<String, Cached<List<ByteArray>>>()

    private val metaDir get() = File(root, META)
    private val pagesDir get() = File(root, PAGES)
    private val manifestFile get() = File(metaDir, "manifest.json")
    private val labelsFile get() = File(metaDir, "meta/labels.json")
    private val propsFile get() = File(metaDir, "meta/properties.json")
    private val smartDir get() = File(metaDir, "meta/smartlists")

    val exists: Boolean get() = manifestFile.exists()

    // ---- lifecycle ----

    /**
     * Lays out an empty workspace.
     *
     * Note what this does *not* do: seed any content. Seeding is gated on scaffolding rather than on
     * an empty index, because the index is empty on every device that clones an existing workspace —
     * gate it the other way and every machine that joins scaffolds itself a second Inbox and a
     * second Today. See GIT_WORKSPACES_PLAN.md §3.
     */
    fun scaffold(name: String, now: Long) {
        pagesDir.mkdirs()
        smartDir.mkdirs()
        writeManifest(Manifest(name = name, createdAt = now))
        writeProperties(builtInProperties())
        writeLabels(emptyList())
    }

    /**
     * The fixed fields every workspace has, and the only defs the app ever writes.
     *
     * One list rather than one per caller: [scaffold] lays them down for a new directory and
     * [ensureBuiltInProperties] catches up an old one, and the two disagreeing would mean a field
     * that works on a workspace made this week and not on the one you have had since June.
     */
    fun builtInProperties(): List<PropertyDef> = listOf(
        // Priority carries its option colours in config. Scaffolding without them left
        // every High chip drawing as an unnamed neutral, because the chip looks its colour
        // up by option name and found no options at all.
        PropertyDef(
            BuiltIns.PRIORITY_DEF_ID, BuiltIns.PRIORITY_NAME, PropertyKind.SELECT,
            config = FilterJson.encodeToString(
                SelectConfig.serializer(),
                SelectConfig(
                    listOf(
                        SelectOption("High", 0xFFFF4A1F),
                        SelectOption("Medium", 0xFFFFB020),
                        SelectOption("Low", 0xFF4A90D9),
                    )
                ),
            ),
        ),
        PropertyDef(BuiltIns.DUE_DEF_ID, BuiltIns.DUE_NAME, PropertyKind.DATE),
        PropertyDef(BuiltIns.DEADLINE_DEF_ID, BuiltIns.DEADLINE_NAME, PropertyKind.DATE),
        // Text rather than select: the options are people, they are discovered rather than
        // declared, and writing a roster into a file every collaborator pulls would make a
        // merge conflict out of somebody joining. The picker is a property of the screen; the
        // file only needs to say the field exists and holds a login.
        PropertyDef(BuiltIns.ASSIGNEE_DEF_ID, BuiltIns.ASSIGNEE_NAME, PropertyKind.TEXT),
    )

    /**
     * Adds any built-in this workspace's `properties.json` is missing. True if it wrote.
     *
     * Every workspace scaffolded before a field existed has a file without it, and the reconciler
     * builds the def rows straight from that file — so a new built-in that is only added to
     * [scaffold] appears on workspaces created afterwards and nowhere else. That is the shape of
     * the Assignee bug: the format, the mapper and the repository all understood `@login` for
     * months while no existing workspace could show one.
     *
     * Existing entries are left exactly as they are, including any a newer build wrote and this one
     * does not recognise. Nothing here overwrites; it only fills gaps.
     */
    fun ensureBuiltInProperties(): Boolean {
        val have = readProperties()
        val known = have.mapTo(HashSet()) { it.id }
        val missing = builtInProperties().filterNot { it.id in known }
        if (missing.isEmpty()) return false
        writeProperties(have + missing)
        return true
    }

    fun readManifest(): Manifest? =
        manifestFile.takeIf { it.exists() }
            ?.let { runCatching { FilterJson.decodeFromString(Manifest.serializer(), it.readText()) }.getOrNull() }

    /**
     * What version of the format this workspace on disk is written in.
     *
     * An unreadable or absent manifest reads as the current version rather than as version zero: a
     * workspace we cannot identify is not a workspace from the future, and treating it as one would
     * make an unrelated parse failure look like "your app is too old".
     */
    val formatVersion: Int get() = readManifest()?.formatVersion ?: FORMAT_VERSION

    /**
     * True when the files were written by a newer build than this one, which makes them read-only.
     *
     * This is the gate [Manifest.formatVersion] was declared for and never given: the field existed,
     * carried a comment saying it was "what makes an older app go read-only on a newer repo", and
     * nothing read it. So nothing did.
     *
     * It matters most for ink. Coordinates changed meaning at format 2 — pixels became document
     * units — and a build that still thinks they are pixels would not fail loudly on a du workspace,
     * it would draw into it in the old unit, mixing both inside one drawing with nothing marking
     * which stroke was which. That is the original bug, re-entered through the back door and worse
     * for being invisible. A version number nobody checks does not prevent it; this does.
     */
    val isAhead: Boolean get() = formatVersion > FORMAT_VERSION

    /**
     * Stamps an older workspace up to the current format, and says whether it did.
     *
     * Called on open, beside [ensureBuiltInProperties], and for the same reason: the file is the
     * truth and the truth has one more thing recorded in it now. This is also what arms the gate —
     * an older build meeting a workspace this has touched sees a version above its own and declines
     * to write, rather than writing the previous meaning of the format into it.
     */
    fun upgradeFormat(): Boolean {
        val m = readManifest() ?: return false
        if (m.formatVersion >= FORMAT_VERSION) return false
        writeManifest(m.copy(formatVersion = FORMAT_VERSION))
        return true
    }

    fun writeManifest(m: Manifest) =
        manifestFile.write(FilterJson.encodeToString(Manifest.serializer(), m))

    // ---- pages ----

    fun pageFile(id: String): File = File(pagesDir, "$id.md")

    /**
     * Every page, parsed.
     *
     * Reparsing is cached on the file's own identity — last-modified plus length — because the index
     * is rebuilt after *every* write and a keystroke changes exactly one file. Without this, typing
     * one character in a thirty-page workspace re-read and re-parsed the other twenty-nine, which
     * measured at 68ms per keystroke and grew linearly from there.
     *
     * Safe against anything that changes a file behind our back, which on this app means git: a
     * rebase or a hard reset rewrites the file and moves its timestamp, so the entry is discarded.
     * Our own writes evict explicitly rather than relying on that, since two writes inside the same
     * millisecond could otherwise agree on both stamp and length.
     */
    fun readPages(): List<PageDoc> {
        val files = pagesDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            .orEmpty()
            .sortedBy { it.name }               // deterministic, so two devices index in one order

        // Anything no longer on disk is gone for good; keeping it would leak a parse per deleted
        // page for the lifetime of the process.
        val live = files.mapTo(HashSet()) { it.name }
        pageCache.keys.retainAll(live)

        return files.map { f ->
            val stamp = Stamp(f.lastModified(), f.length())
            pageCache[f.name]?.takeIf { it.stamp == stamp }?.value
                ?: PageCodec.decode(f.readText()).also { pageCache[f.name] = Cached(stamp, it) }
        }
    }

    /** One page, parsed, through the same cache [readPages] uses. */
    fun readPage(id: String): PageDoc? {
        val f = pageFile(id)
        if (!f.exists()) return null
        val stamp = Stamp(f.lastModified(), f.length())
        return pageCache[f.name]?.takeIf { it.stamp == stamp }?.value
            ?: PageCodec.decode(f.readText()).also { pageCache[f.name] = Cached(stamp, it) }
    }

    fun writePage(doc: PageDoc) {
        pageFile(doc.id).write(PageCodec.encode(doc))
        pageCache.remove("${doc.id}.md")
    }

    /**
     * Removes a page, the sidecars its blocks own, and every page beneath it.
     *
     * Sidecars are named after the *ink block*, not after the page holding it, so deleting only
     * `<page>.ink` leaves every stroke on disk with nothing referring to it — invisible, committed,
     * and growing. Child task pages go the same way: git records the whole removal as one deletion
     * and there is no tombstone to carry.
     */
    fun deletePage(id: String, seen: MutableSet<String> = HashSet()) {
        if (!seen.add(id)) return    // a malformed workspace can name a cycle; do not follow it twice
        runCatching { PageCodec.decode(pageFile(id).readText()) }.getOrNull()?.blocks?.forEach { b ->
            when (b) {
                is ie.shoonya.yantra.data.format.InkRef -> {
                    inkFile(b.id).delete()
                    inkCache.remove(inkFile(b.id).name)
                }
                // Named after the block, like a stroke sidecar: deleting only the page would leave
                // the picture on disk with nothing referring to it, invisible and committed.
                is ie.shoonya.yantra.data.format.ImageRef -> imageFile(b.uri).delete()
                is ie.shoonya.yantra.data.format.TaskRef ->
                    if (pageFile(b.id).exists()) deletePage(b.id, seen)
                else -> Unit
            }
        }
        pageFile(id).delete()
        inkFile(id).delete()
        pageCache.remove(pageFile(id).name)
        inkCache.remove(inkFile(id).name)
        deleteSmartList(id)
    }

    // ---- ink ----

    fun inkFile(id: String): File = File(pagesDir, "$id.ink")

    /**
     * A block's strokes, exactly as the file holds them.
     *
     * **Faithful on purpose.** Unplaceable strokes — v1 pixel coordinates, or anything from a format
     * this build does not know — are dropped where they are *decoded*, by
     * [ie.shoonya.yantra.data.ink.StrokeCodec.decodeOrNull], not here. Filtering at the read would
     * make read-then-write lossy, and every write is a whole-file rewrite: a stroke this build
     * cannot parse would be deleted from the repo by the next edit to a neighbouring stroke. The
     * format-version gate is supposed to stop a newer file being written at all, and a second lock
     * on the same door is worth having.
     *
     * Cached the same way as a page, and for a sharper reason. Stroke blobs are the heaviest thing
     * in a workspace and the least likely to change: a page of drawings is hundreds of kilobytes
     * that a rebuild used to re-read and re-decode because someone renamed a task. The returned
     * lists are the *same instances* while the file is unchanged, which is what lets [Indexer]
     * notice that the ink table does not need rewriting at all.
     */
    fun readInk(id: String): List<ByteArray> {
        val f = inkFile(id)
        if (!f.exists()) {
            inkCache.remove(f.name)
            return emptyList()
        }
        val stamp = Stamp(f.lastModified(), f.length())
        return inkCache[f.name]?.takeIf { it.stamp == stamp }?.value
            ?: decodeInk(f.readBytes()).also { inkCache[f.name] = Cached(stamp, it) }
    }

    fun writeInk(id: String, strokes: List<ByteArray>) {
        if (isAhead) return     // deleting is a write too, and the gate is about all of them
        if (strokes.isEmpty()) inkFile(id).delete() else inkFile(id).writeBytesAtomically(encodeInk(strokes))
        inkCache.remove(inkFile(id).name)
    }

    // ---- archive ----

    /**
     * Where finished work goes to stop costing anything.
     *
     * A sibling of `pages/`, not a subdirectory of it, because [readPages] globs every markdown file
     * directly inside `pages`, and an archive file living there would be read back into the index — which is the one thing archiving
     * exists to prevent. It stays in the repo, in the same format, greppable and diffable: archived
     * is a place, not a deletion, and you can open the file and read what you did last year.
     */
    private val archiveDir get() = File(root, ARCHIVE)

    fun archiveFile(pageId: String): File = File(archiveDir, "$pageId.md")

    /** Pages that have anything archived beneath them. */
    fun archivedPageIds(): List<String> =
        archiveDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            .orEmpty()
            .map { it.name.removeSuffix(".md") }
            .sorted()

    /** An archived task's own page, if it had one. Kept apart so it is not indexed either. */
    fun archivedPageFile(pageId: String): File = File(File(archiveDir, PAGES), "$pageId.md")

    fun readArchivedLines(pageId: String): List<String> =
        archiveFile(pageId).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    fun writeArchivedLines(pageId: String, lines: List<String>) {
        if (lines.isEmpty()) {
            archiveFile(pageId).delete()
            return
        }
        archiveDir.mkdirs()
        archiveFile(pageId).write(lines.joinToString("\n") + "\n")
    }

    /** Moves a page out of the working set, or back into it. */
    fun moveToArchive(pageId: String) {
        val from = pageFile(pageId)
        if (!from.exists()) return
        File(archiveDir, PAGES).mkdirs()
        from.copyTo(archivedPageFile(pageId), overwrite = true)
        from.delete()
        pageCache.remove(from.name)
    }

    fun restoreFromArchive(pageId: String) {
        val from = archivedPageFile(pageId)
        if (!from.exists()) return
        from.copyTo(pageFile(pageId), overwrite = true)
        from.delete()
        pageCache.remove(pageFile(pageId).name)
    }

    // ---- images ----

    /**
     * A picture, beside the page that shows it.
     *
     * Same shape as an ink sidecar and for the same reason: it is part of the workspace, so it is in
     * the repo, so it reaches every device. What lands here is a downscaled copy — see
     * `ARCHITECTURE.md` §5 — because git keeps every version of a binary forever and a phone photo is
     * several megabytes. The device that picked it may also hold the original; that is device-local
     * and never named in a file.
     */
    fun imageFile(id: String): File = File(pagesDir, "$id.jpg")

    fun writeImage(id: String, bytes: ByteArray) = imageFile(id).writeBytesAtomically(bytes)

    fun hasImage(id: String): Boolean = imageFile(id).exists()

    // ---- focus ----

    /**
     * Focus sessions, one line per session, appended and never rewritten.
     *
     * Append-only is the merge-friendly shape: two devices that both focus offline produce two
     * different tails, and git takes both. Rewriting a shared file would put them in conflict over
     * work neither of them disagrees about. Split by month so the file a busy week appends to stays
     * small and the diff stays readable.
     *
     * Tab-separated with a trailing field count that never shrinks, so an older app reading a newer
     * log can ignore what it does not recognise instead of failing on the line.
     */
    private val focusDir get() = File(root, FOCUS_DIR)

    /** Where these lived when the feature was called Pomodoro. See [migrateLegacyFocusDir]. */
    private val legacyFocusDir get() = File(root, LEGACY_FOCUS_DIR)

    fun appendFocus(line: String, month: String) {
        migrateLegacyFocusDir()
        val f = File(focusDir, "$month.log")
        f.parentFile?.mkdirs()
        f.appendText(line.trimEnd('\n') + "\n")
    }

    /**
     * Both directories, because a repository is shared and not every device renames at once.
     *
     * A phone still running the older app keeps appending to `pomodoro/`, and its lines are as real
     * as any other. Reading only the new name would make someone's afternoon vanish from the ledger
     * the moment a second device fell behind — the one thing a history must never do.
     */
    fun readFocus(): List<String> =
        listOf(focusDir, legacyFocusDir)
            .flatMap { it.listFiles { f -> f.name.endsWith(".log") }.orEmpty().asIterable() }
            .sortedBy { it.name }
            .flatMap { it.readLines() }
            .filter { it.isNotBlank() }

    /**
     * Moves `pomodoro/` to `focus/` once, in place.
     *
     * The directory is in the user's repository, so this is a visible rename in their history rather
     * than an internal detail — which is the argument for doing it rather than leaving the old name
     * to sit there contradicting every screen that says Focus.
     *
     * A month's log can exist under both names at once, if an older device appended after this ran
     * somewhere else. That is why the contents are appended rather than the file moved: the union of
     * two append-only tails is the same merge git would have performed, and losing either half is
     * losing recorded time.
     */
    fun migrateLegacyFocusDir() {
        val legacy = legacyFocusDir
        if (!legacy.isDirectory) return
        legacy.listFiles { f -> f.name.endsWith(".log") }.orEmpty().forEach { old ->
            val dest = File(focusDir, old.name)
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.appendText(old.readText()) else old.copyTo(dest, overwrite = true)
            old.delete()
        }
        // Only if it emptied: anything unexpected in there is left alone rather than discarded.
        legacy.listFiles()?.takeIf { it.isEmpty() }?.let { legacy.delete() }
    }

    // ---- registries ----

    fun readLabels(): List<LabelDef> = labelsFile.readList(LabelDef.serializer())

    fun writeLabels(labels: List<LabelDef>) =
        labelsFile.write(FilterJson.encodeToString(ListSerializer(LabelDef.serializer()), labels))

    fun readProperties(): List<PropertyDef> = propsFile.readList(PropertyDef.serializer())

    fun writeProperties(defs: List<PropertyDef>) =
        propsFile.write(FilterJson.encodeToString(ListSerializer(PropertyDef.serializer()), defs))

    fun readSmartLists(): List<SmartListDef> =
        smartDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            .mapNotNull {
                runCatching { FilterJson.decodeFromString(SmartListDef.serializer(), it.readText()) }.getOrNull()
            }

    fun writeSmartList(def: SmartListDef) =
        File(smartDir, "${def.nodeId}.json")
            .write(FilterJson.encodeToString(SmartListDef.serializer(), def))

    fun deleteSmartList(nodeId: String) { File(smartDir, "$nodeId.json").delete() }

    // ---- io ----

    private fun <T> File.readList(ser: kotlinx.serialization.KSerializer<T>): List<T> =
        takeIf { it.exists() }
            ?.let { runCatching { FilterJson.decodeFromString(ListSerializer(ser), it.readText()) }.getOrNull() }
            .orEmpty()

    /**
     * Write to a sibling and rename over the target.
     *
     * A half-written page is worse than a missing one: the parser is forgiving enough that a
     * truncated file would come back as a page with most of its blocks gone, and be committed
     * looking deliberate. Rename is atomic, so a file is either the old one or the new one.
     */
    private fun File.write(text: String) = writeBytesAtomically(text.toByteArray())

    private fun File.writeBytesAtomically(bytes: ByteArray) {
        // The read-only gate, at the one place every write to this workspace passes through —
        // pages, ink, images, the manifest and the metadata alike. Refusing here rather than at
        // each of the dozen callers is what makes it impossible to add a thirteenth that forgets.
        //
        // A refusal is logged and dropped rather than thrown: this runs inside coroutines launched
        // from view models, where an exception is a crash on the first keystroke, and crashing is a
        // worse answer than declining. It is not a *good* answer — an edit disappears with only
        // logcat to say why — and the fix is to stop the screen accepting the edit at all, which is
        // a design question about what to show someone, exactly like the one [App.report] declines
        // to answer on the spot. What is not tolerable is writing the wrong thing, and that this
        // does prevent.
        if (isAhead) {
            android.util.Log.w(
                "Yantra.workspace",
                "refusing to write $name: workspace '$id' is format $formatVersion, this build reads $FORMAT_VERSION",
            )
            return
        }
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(this)) {
            tmp.copyTo(this, overwrite = true)
            tmp.delete()
        }
    }
}
