package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.data.format.PageDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pinning survives the file, and a file that never mentions it still means what it used to.
 *
 * The pinned section on Home was "every ungrouped smart list" — not a choice, just a consequence of
 * the type, which meant it held exactly the two lists the app scaffolds and the one thing you could
 * not do to a pinned list was unpin it. Making it a choice means writing it down, and writing it
 * down means every file that predates the idea says nothing. **Absence has to mean the old rule**,
 * or the first launch after the upgrade empties the section for everybody.
 */
class PinnedCodecTest {

    private fun page(pinned: Boolean?, type: String = "smart_list", parent: String? = null) = PageDoc(
        id = "n1", type = type, parent = parent, title = "Today",
        modifiedAt = Instant.parse("2026-09-23T10:00:00Z"), device = "test",
        blocks = emptyList(), pinned = pinned,
    )

    private fun roundTrip(doc: PageDoc) = PageCodec.decode(PageCodec.encode(doc))

    @Test
    fun `a pinned list says so in its file`() {
        assertTrue(PageCodec.encode(page(true)).contains("pinned: true"))
        assertEquals(true, roundTrip(page(true)).pinned)
    }

    @Test
    fun `unpinning is written down, not left out`() {
        // The one that matters: leaving `false` out would read as "never asked", and the fallback
        // would pin Today straight back on the next rebuild.
        assertTrue(PageCodec.encode(page(false)).contains("pinned: false"))
        assertEquals(false, roundTrip(page(false)).pinned)
    }

    @Test
    fun `a file that never mentions pinning says nothing, rather than no`() {
        val encoded = PageCodec.encode(page(null))
        assertTrue("no key at all", !encoded.contains("pinned:"))
        assertNull(roundTrip(page(null)).pinned)
    }

    @Test
    fun `a page written before pinning existed decodes as unspecified`() {
        val old = """
            ---
            id: n1
            type: smart_list
            title: Today
            modified_at: 2026-09-23T10:00:00Z
            ---
        """.trimIndent()
        assertNull(PageCodec.decode(old).pinned)
    }

    @Test
    fun `a value this build does not understand is unspecified rather than a guess`() {
        val odd = """
            ---
            id: n1
            type: smart_list
            title: Today
            pinned: maybe
            modified_at: 2026-09-23T10:00:00Z
            ---
        """.trimIndent()
        assertNull(PageCodec.decode(odd).pinned)
    }

    @Test
    fun `the fallback is exactly the rule the section used to be`() {
        // Mirrors PageMapper: unspecified means "an ungrouped smart list", and nothing else.
        fun resolve(doc: PageDoc) = doc.pinned ?: (doc.type == "smart_list" && doc.parent == null)

        assertEquals("a top-level smart list stays on top", true, resolve(page(null)))
        assertEquals("one inside a group does not", false, resolve(page(null, parent = "g1")))
        assertEquals("an ordinary list does not", false, resolve(page(null, type = "list")))
        assertEquals("and a choice always wins", false, resolve(page(false)))
        assertEquals(true, resolve(page(true, type = "list")))
    }
}
