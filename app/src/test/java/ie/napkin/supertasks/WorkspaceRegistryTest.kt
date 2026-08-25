package ie.napkin.supertasks

import ie.napkin.supertasks.data.workspace.WorkspaceEntry
import ie.napkin.supertasks.data.workspace.WorkspaceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The list of workspaces, which is the one thing here that is not rebuildable.
 *
 * Everything else in this app can be thrown away and reconstructed from files. This cannot: it is
 * the only record that a repository was ever attached, and losing it means a workspace whose files
 * are still on disk and which the app will never open again.
 */
class WorkspaceRegistryTest {

    private lateinit var root: File
    private lateinit var registry: WorkspaceRegistry

    @Before
    fun setUp() {
        root = Files.createTempDirectory("registry").toFile()
        registry = WorkspaceRegistry(root)
    }

    @Test
    fun `nothing registered yet is empty rather than an error`() {
        assertTrue(registry.entries().isEmpty())
    }

    @Test
    fun `an entry survives a round trip`() {
        registry.add(WorkspaceEntry("ws-1", "Project", "batunii/project"))
        assertEquals(
            listOf(WorkspaceEntry("ws-1", "Project", "batunii/project")),
            WorkspaceRegistry(root).entries(),
        )
    }

    @Test
    fun `adding the same id twice replaces rather than duplicates`() {
        registry.add(WorkspaceEntry("ws-1", "Project", "batunii/project"))
        registry.add(WorkspaceEntry("ws-1", "Renamed", "batunii/renamed"))

        val entries = registry.entries()
        assertEquals(1, entries.size)
        assertEquals("Renamed", entries.single().name)
    }

    @Test
    fun `a workspace can be forgotten`() {
        registry.add(WorkspaceEntry("ws-1", "One"))
        registry.add(WorkspaceEntry("ws-2", "Two"))
        registry.remove("ws-1")

        assertEquals(listOf("ws-2"), registry.entries().map { it.id })
    }

    @Test
    fun `the local workspace gets its own directory and not the parent`() {
        // The empty id is the local workspace. Resolving it as an empty path component would put its
        // pages directly in the workspaces root, beside every other workspace's directory.
        assertEquals(File(root, "local"), registry.dirFor(""))
        assertEquals(File(root, "ws-1"), registry.dirFor("ws-1"))
    }

    @Test
    fun `a corrupt registry reads as empty instead of throwing`() {
        registry.add(WorkspaceEntry("ws-1", "One"))
        File(root, "registry.json").writeText("{ this is not json")
        // A crash on launch would be unrecoverable without clearing app data. Losing the list is
        // bad; being unable to start is worse.
        assertTrue(registry.entries().isEmpty())
    }

    @Test
    fun `writing leaves no temp file behind`() {
        registry.add(WorkspaceEntry("ws-1", "One"))
        registry.add(WorkspaceEntry("ws-2", "Two"))
        assertFalse(File(root, "registry.json.tmp").exists())
    }

    @Test
    fun `a slug is optional`() {
        // The local workspace has no remote until someone gives it one, and it is still a workspace.
        registry.add(WorkspaceEntry("", "Personal"))
        assertEquals(null, WorkspaceRegistry(root).entries().single().slug)
    }
}
