package ie.napkin.supertasks.data.workspace

import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.PropertyKind
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.repo.SelectConfig
import ie.napkin.supertasks.data.repo.SelectOption
import ie.napkin.supertasks.data.format.PageCodec
import ie.napkin.supertasks.data.format.PageDoc
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
        const val FORMAT_VERSION = 1
        private const val META = ".yantra"
        private const val PAGES = "pages"
        private const val ARCHIVE = "archive"

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
        writeProperties(
            listOf(
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
            )
        )
        writeLabels(emptyList())
    }

    fun readManifest(): Manifest? =
        manifestFile.takeIf { it.exists() }
            ?.let { runCatching { FilterJson.decodeFromString(Manifest.serializer(), it.readText()) }.getOrNull() }

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
                is ie.napkin.supertasks.data.format.InkRef -> {
                    inkFile(b.id).delete()
                    inkCache.remove(inkFile(b.id).name)
                }
                // Named after the block, like a stroke sidecar: deleting only the page would leave
                // the picture on disk with nothing referring to it, invisible and committed.
                is ie.napkin.supertasks.data.format.ImageRef -> imageFile(b.uri).delete()
                is ie.napkin.supertasks.data.format.TaskRef ->
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
     * A block's strokes, cached the same way and for a sharper reason.
     *
     * Stroke blobs are the heaviest thing in a workspace and the least likely to change: a page of
     * drawings is hundreds of kilobytes that a rebuild used to re-read and re-decode because someone
     * renamed a task. The returned lists are the *same instances* while the file is unchanged, which
     * is what lets [Indexer] notice that the ink table does not need rewriting at all.
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

    // ---- pomodoro ----

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
    private val pomodoroDir get() = File(root, "pomodoro")

    fun appendPomodoro(line: String, month: String) {
        val f = File(pomodoroDir, "$month.log")
        f.parentFile?.mkdirs()
        f.appendText(line.trimEnd('\n') + "\n")
    }

    fun readPomodoro(): List<String> =
        pomodoroDir.listFiles { f -> f.name.endsWith(".log") }.orEmpty().sortedBy { it.name }
            .flatMap { it.readLines() }
            .filter { it.isNotBlank() }

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
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(this)) {
            tmp.copyTo(this, overwrite = true)
            tmp.delete()
        }
    }
}
