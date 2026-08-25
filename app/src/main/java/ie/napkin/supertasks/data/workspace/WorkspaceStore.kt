package ie.napkin.supertasks.data.workspace

import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.PropertyKind
import ie.napkin.supertasks.data.filter.FilterJson
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
class WorkspaceStore(val root: File) {

    companion object {
        const val FORMAT_VERSION = 1
        private const val META = ".yantra"
        private const val PAGES = "pages"

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
                PropertyDef(BuiltIns.PRIORITY_DEF_ID, BuiltIns.PRIORITY_NAME, PropertyKind.SELECT),
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

    fun readPages(): List<PageDoc> =
        pagesDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            .orEmpty()
            .sortedBy { it.name }               // deterministic, so two devices index in one order
            .map { PageCodec.decode(it.readText()) }

    fun writePage(doc: PageDoc) = pageFile(doc.id).write(PageCodec.encode(doc))

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
                is ie.napkin.supertasks.data.format.InkRef -> inkFile(b.id).delete()
                is ie.napkin.supertasks.data.format.TaskRef ->
                    if (pageFile(b.id).exists()) deletePage(b.id, seen)
                else -> Unit
            }
        }
        pageFile(id).delete()
        inkFile(id).delete()
        deleteSmartList(id)
    }

    // ---- ink ----

    fun inkFile(id: String): File = File(pagesDir, "$id.ink")

    fun readInk(id: String): List<ByteArray> =
        inkFile(id).takeIf { it.exists() }?.let { decodeInk(it.readBytes()) }.orEmpty()

    fun writeInk(id: String, strokes: List<ByteArray>) {
        if (strokes.isEmpty()) inkFile(id).delete() else inkFile(id).writeBytesAtomically(encodeInk(strokes))
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
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(this)) {
            tmp.copyTo(this, overwrite = true)
            tmp.delete()
        }
    }
}
