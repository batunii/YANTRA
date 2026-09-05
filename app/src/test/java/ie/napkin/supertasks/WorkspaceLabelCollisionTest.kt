package ie.napkin.supertasks

import ie.napkin.supertasks.data.workspace.Manifest
import ie.napkin.supertasks.data.workspace.WorkspaceReconciler
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A workspace whose name is also used as a tag.
 *
 * This once crashed the app on launch, every launch. The reader derived a label standing for the
 * workspace and attached it to every task, so a repo called `v2-tasks` and a `#v2-tasks` tag minted
 * two ids for one name — and `label` is unique on (workspace_id, name), so the insert replaced one
 * and the attachment pointing at the loser failed its foreign key inside the launch job.
 *
 * Provenance is [Filter.InWorkspace] now and not a label at all, which is what makes the collision
 * unrepresentable rather than merely handled. These pin that: one name is one row, and no row is
 * invented for the workspace itself.
 */
class WorkspaceLabelCollisionTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a tag spelled like the workspace is one ordinary tag`() {
        val ws = "93c907a5-4c1a-481f-9016-1a57e44b5ad4"
        val root = tmp.newFolder(ws)
        val store = WorkspaceStore(root, ws)
        store.writeManifest(Manifest(name = "v2-tasks", createdAt = 1_788_177_735_338L))
        root.resolve("pages").mkdirs()
        root.resolve("pages/26153143-0797-4732-b064-6957096dc4a2.md").writeText(
            """
            ---
            id: 26153143-0797-4732-b064-6957096dc4a2
            type: list
            title: v2-tasks
            modified_at: 2026-08-31T12:02:50.058Z
            device: sm-s921b
            ---
            - [ ] Meals ^51f451a3-de22-4d3b-b18d-3cd3415e2765 #v2-tasks

            """.trimIndent()
        )
        root.resolve("pages/51f451a3-de22-4d3b-b18d-3cd3415e2765.md").writeText(
            """
            ---
            id: 51f451a3-de22-4d3b-b18d-3cd3415e2765
            type: task
            parent: 26153143-0797-4732-b064-6957096dc4a2
            modified_at: 2026-08-31T12:02:46.510Z
            device: sm-s921b
            ---

            """.trimIndent()
        )

        val index = WorkspaceReconciler.read(store, now = 1_788_000_000_000L)

        // One name, one row: what the unique index will allow to exist. The id is the tag's own —
        // nothing derives a second label from the manifest to collide with it.
        assertEquals(1, index.labels.size)
        assertEquals("$ws:label:v2-tasks", index.labels.single().id)

        // The one tag on the one task, attached once, to a row that is really there.
        assertEquals(1, index.nodeLabels.size)
        assertEquals(index.labels.single().id, index.nodeLabels.single().labelId)
        assertEquals("51f451a3-de22-4d3b-b18d-3cd3415e2765", index.nodeLabels.single().nodeId)
        assertTrue("unexpected problems: ${index.problems}", index.problems.isEmpty())
    }

    /** Untagged, a task carries no labels at all — provenance is the column, not a row. */
    @Test fun `a workspace invents no label of its own`() {
        val ws = "no-tags"
        val root = tmp.newFolder(ws)
        val store = WorkspaceStore(root, ws)
        store.writeManifest(Manifest(name = "v2-tasks", createdAt = 1_788_177_735_338L))
        root.resolve("pages").mkdirs()
        root.resolve("pages/bbbbbbbb-0000-0000-0000-000000000001.md").writeText(
            """
            ---
            id: bbbbbbbb-0000-0000-0000-000000000001
            type: list
            title: v2-tasks
            modified_at: 2026-08-31T12:02:50.058Z
            device: sm-s921b
            ---
            - [ ] Meals

            """.trimIndent()
        )

        val index = WorkspaceReconciler.read(store, now = 1_788_000_000_000L)

        assertEquals(emptyList<Any>(), index.labels)
        assertEquals(emptyList<Any>(), index.nodeLabels)
        // The tasks are still the workspace's — that is what the column is for.
        assertTrue(index.nodes.isNotEmpty())
        assertTrue(index.nodes.all { it.workspaceId == ws })
    }

    /**
     * The floor under all of it: an attachment naming a row that is not there is dropped and said
     * out loud, rather than reaching a foreign key and taking the launch down.
     */
    @Test fun `an attachment to a task the workspace lacks is dropped, not inserted`() {
        val root = tmp.newFolder("orphan")
        val store = WorkspaceStore(root, "orphan")
        store.writeManifest(Manifest(name = "Orphans", createdAt = 1_788_000_000_000L))
        root.resolve("pages").mkdirs()
        // A line pointing at a page that is not in this workspace: the link it produces names a
        // node no other file supplies.
        root.resolve("pages/aaaaaaaa-0000-0000-0000-000000000001.md").writeText(
            """
            ---
            id: aaaaaaaa-0000-0000-0000-000000000001
            type: list
            title: Orphans
            modified_at: 2026-08-31T12:02:50.058Z
            device: sm-s921b
            ---
            - [ ] Real #kept

            """.trimIndent()
        )

        val index = WorkspaceReconciler.read(store, now = 1_788_000_000_000L)

        val known = index.labels.mapTo(HashSet()) { it.id }
        val nodes = index.nodes.mapTo(HashSet()) { it.id }
        assertTrue(
            "every attachment must name rows the index has",
            index.nodeLabels.all { it.nodeId in nodes && it.labelId in known },
        )
    }
}
