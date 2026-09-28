package ie.shoonya.yantra

import ie.shoonya.yantra.data.workspace.LabelDef
import ie.shoonya.yantra.data.workspace.Manifest
import ie.shoonya.yantra.data.workspace.WorkspaceReconciler
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A workspace's rows belong to that workspace, whatever its registry file happens to say.
 *
 * **The bug.** `LabelRepository.setColor` wrote every recolour through `primary()`, so picking a
 * colour for a tag on a task in another repo dropped *that repo's* label id into Personal's
 * registry. `label`'s primary key is the id and its insert is REPLACE, so rebuilding Personal
 * deleted the real row to make room — and `node_label` has a cascading key to `label`, so every
 * attachment of that tag went with it. The repo that actually owned the tag had not changed, so
 * nothing was going to rebuild it and put them back.
 *
 * On the phone: long-press a chip, tap a swatch, and the tag is gone from the task. It reads as
 * data loss and is not — the tag is a word on a line in the page and the page was never touched —
 * but nothing on screen says so, and a cold start is the only thing that brings it back.
 *
 * [LabelChipSurvivesTest] covers the single-workspace half of the same cascading key. This covers
 * the half where the two ends are in different repos, which is the half `linksChanged` cannot
 * reach: a workspace only rebuilds its own tables.
 */
class LabelStaysInItsWorkspaceTest {

    @get:Rule val tmp = TemporaryFolder()

    private val personal = ""
    private val other = "41e6bcd3-cb88-4382-a958-3ddc389346a7"

    private fun store(id: String, name: String): WorkspaceStore {
        val root = tmp.newFolder(if (id.isEmpty()) "personal" else id)
        val store = WorkspaceStore(root, id)
        store.writeManifest(Manifest(name = name, createdAt = 1_788_000_000_000L))
        root.resolve("pages").mkdirs()
        return store
    }

    private fun page(store: WorkspaceStore, id: String, body: String) {
        store.root.resolve("pages/$id.md").writeText(
            """
            |---
            |id: $id
            |type: list
            |title: Napkin
            |modified_at: 2026-09-24T09:00:00.000Z
            |device: sm-s921b
            |---
            |$body
            |
            """.trimMargin()
        )
    }

    /**
     * The shape the phone was left in: Personal's registry holding an id scoped to another repo.
     * Reading Personal must not produce a row that names it.
     */
    @Test fun `a foreign label id in a registry never becomes a foreign row`() {
        val mine = store(personal, "Personal")
        mine.writeLabels(
            listOf(
                LabelDef(id = ":label:question", name = "question", color = 4289095577L),
                // What setColor used to write when the tag being recoloured lived elsewhere.
                LabelDef(id = "$other:label:question", name = "question", color = 4282222776L),
            )
        )
        page(mine, "aaaaaaaa-0000-0000-0000-000000000001", "- [ ] Mine ^bbbbbbbb-0000-0000-0000-000000000002 #question")

        val index = WorkspaceReconciler.read(mine, now = 1_788_000_000_000L)

        assertTrue(
            "a row escaped into another workspace: ${index.labels.map { it.id }}",
            index.labels.none { it.id.startsWith(other) },
        )
        assertTrue(index.labels.all { it.workspaceId == personal })
        // One name is one row — the unique index on (workspace_id, name) allows nothing else.
        assertEquals(1, index.labels.size)
        assertEquals(":label:question", index.labels.single().id)
    }

    /**
     * And the other repo keeps its own. Together these are the property that matters: rebuilding
     * one workspace cannot touch another's rows, so it cannot cascade away another's attachments.
     */
    @Test fun `each workspace mints its own id for the same tag`() {
        val mine = store(personal, "Personal")
        page(mine, "aaaaaaaa-0000-0000-0000-000000000001", "- [ ] Mine ^bbbbbbbb-0000-0000-0000-000000000002 #question")
        val theirs = store(other, "v2-tasks")
        page(theirs, "cccccccc-0000-0000-0000-000000000003", "- [ ] Theirs ^dddddddd-0000-0000-0000-000000000004 #question")

        val here = WorkspaceReconciler.read(mine, now = 1_788_000_000_000L)
        val there = WorkspaceReconciler.read(theirs, now = 1_788_000_000_000L)

        assertEquals(":label:question", here.labels.single().id)
        assertEquals("$other:label:question", there.labels.single().id)
        assertTrue(
            "two repos sharing one label id is how one rebuild deletes the other's attachments",
            here.labels.single().id != there.labels.single().id,
        )
        // Each attachment points at its own workspace's row.
        assertEquals(here.labels.single().id, here.nodeLabels.single().labelId)
        assertEquals(there.labels.single().id, there.nodeLabels.single().labelId)
    }

    /** A colour in the registry is still honoured — it is the id that is not taken on trust. */
    @Test fun `the registry still decides the colour`() {
        val theirs = store(other, "v2-tasks")
        theirs.writeLabels(listOf(LabelDef(id = "$other:label:question", name = "question", color = 4282222776L)))
        page(theirs, "cccccccc-0000-0000-0000-000000000003", "- [ ] Theirs ^dddddddd-0000-0000-0000-000000000004 #question")

        val index = WorkspaceReconciler.read(theirs, now = 1_788_000_000_000L)

        assertEquals(4282222776L, index.labels.single().color)
        assertEquals("$other:label:question", index.labels.single().id)
    }

    /** [WorkspaceReconciler.ownerOf] is the inverse of [WorkspaceReconciler.idFor], including for Personal's empty id. */
    @Test fun `an id names the workspace that minted it`() {
        assertEquals(personal, WorkspaceReconciler.ownerOf(WorkspaceReconciler.idFor(personal, "Question")))
        assertEquals(other, WorkspaceReconciler.ownerOf(WorkspaceReconciler.idFor(other, "Question")))
        assertEquals(null, WorkspaceReconciler.ownerOf("not-a-label-id"))
    }
}
