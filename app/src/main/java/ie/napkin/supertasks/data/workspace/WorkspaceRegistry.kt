package ie.napkin.supertasks.data.workspace

import ie.napkin.supertasks.data.filter.FilterJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * One workspace the app knows about.
 *
 * [id] is generated, not derived from the repository, because a repository can be renamed or moved
 * to another owner and the id is written into every row of the index and into the label that names
 * the workspace. A slug as the primary key would mean a rename silently orphaning every task in it.
 * [slug] is kept beside it so the UI can say where a workspace points, and is allowed to go stale.
 */
@Serializable
data class WorkspaceEntry(
    val id: String,
    val name: String,
    val slug: String? = null,
)

/**
 * The list of workspaces, on disk.
 *
 * Everything else in this app rebuilds from files, so it would be tempting to discover workspaces by
 * scanning for directories that look like one. That fails in the direction that matters: a link that
 * got halfway — directory created, repository refused — would come back on every launch as a
 * workspace that cannot sync, and there would be nowhere to record that it is not real. An explicit
 * list is written *after* the link succeeds, so a failed attempt leaves nothing to resurrect.
 *
 * The local workspace is not in here. It has the empty id, it is always present, and it is the one
 * thing that must exist before any of this does.
 */
class WorkspaceRegistry(private val root: File) {

    private val file get() = File(root, "registry.json")

    fun entries(): List<WorkspaceEntry> =
        file.takeIf { it.exists() }
            ?.let {
                runCatching {
                    FilterJson.decodeFromString(ListSerializer(WorkspaceEntry.serializer()), it.readText())
                }.getOrNull()
            }
            ?: emptyList()

    /** Adds, or replaces an entry with the same id. */
    fun add(entry: WorkspaceEntry) {
        write(entries().filterNot { it.id == entry.id } + entry)
    }

    fun remove(id: String) {
        write(entries().filterNot { it.id == id })
    }

    /**
     * Where a workspace's files live. `local` is spelled out rather than being the empty id's empty
     * string, which would resolve to the parent directory and put one workspace's pages beside every
     * other workspace's directory.
     */
    fun dirFor(id: String): File = File(root, if (id.isEmpty()) "local" else id)

    private fun write(list: List<WorkspaceEntry>) {
        root.mkdirs()
        val text = FilterJson.encodeToString(ListSerializer(WorkspaceEntry.serializer()), list)
        // Same temp-and-rename as the workspace files: a half-written registry is the one file that
        // could lose track of a repository the user has already put work into.
        val tmp = File(root, "registry.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }
}
