package ie.shoonya.yantra

import ie.shoonya.yantra.data.db.LabelEntity
import ie.shoonya.yantra.data.filter.Field
import ie.shoonya.yantra.data.label.LabelCanon
import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.data.workspace.WorkspaceReconciler.idFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tag is its name. Typed in two workspaces, it is still one entry in a picker and one colour on
 * every chip.
 *
 * The index keeps a row per workspace, `<ws>:label:<name>`, and every surface used to show those
 * rows as they came: `#sync` listed twice in the label picker, drawn teal on a Personal task and
 * violet on a task from another repo in the same Today list. [LabelCanon] is the map every surface
 * reads through now, keyed by the lowercased name.
 */
class OneTagOneColourTest {

    private val personal = ""
    private val work = "41e6bcd3-cb88-4382-a958-3ddc389346a7"
    private val violet = 0xFF8075BA

    private fun row(ws: String, name: String, color: Long? = LabelPalette.defaultFor(name), created: Long = 1L) =
        LabelEntity(idFor(ws, name), ws, name, color, created, created)

    @Test fun `the same name in two workspaces is listed once`() {
        val rows = listOf(row(personal, "sync"), row(work, "Sync"), row(personal, "urgent"))

        val tags = LabelCanon.distinct(rows)

        assertEquals(listOf("sync", "urgent"), tags.map { LabelCanon.key(it.name) })
    }

    @Test fun `every workspace's attachment resolves to the same label`() {
        val rows = listOf(row(personal, "sync", created = 1L), row(work, "sync", created = 2L))

        val byId = LabelCanon.byId(rows)

        assertEquals(byId.getValue(idFor(personal, "sync")), byId.getValue(idFor(work, "sync")))
    }

    /**
     * The colour somebody picked wins over the one the palette seeded. A tag first typed in a second
     * repo gets the seed there, and that must not undo a recolour made in the repo it started in.
     */
    @Test fun `a chosen colour outvotes a seeded one`() {
        val rows = listOf(
            row(personal, "sync", created = 1L),
            row(work, "sync", color = violet, created = 2L),
        )

        assertEquals(violet, LabelCanon.byId(rows).getValue(idFor(personal, "sync")).color)
        assertEquals(violet, LabelCanon.distinct(rows).single().color)
    }

    @Test fun `with no colour chosen, the oldest workspace speaks for the tag`() {
        val rows = listOf(row(work, "sync", created = 2L), row(personal, "sync", created = 1L))

        assertEquals(idFor(personal, "sync"), LabelCanon.distinct(rows).single().id)
    }

    @Test fun `ids from two workspaces name the same tag`() {
        assertTrue(LabelCanon.sameTag(idFor(personal, "Sync"), idFor(work, "sync")))
        assertFalse(LabelCanon.sameTag(idFor(personal, "sync"), idFor(work, "synced")))
    }

    /** A rule pinning `#sync` in one repo silences the chip on a row from another. */
    @Test fun `a pinned tag is recognised from any workspace`() {
        val pinned = setOf<Field>(Field.Label(idFor(work, "sync")))

        assertTrue(LabelCanon.namesTag(pinned, idFor(personal, "sync")))
        assertFalse(LabelCanon.namesTag(pinned, idFor(personal, "urgent")))
    }
}
