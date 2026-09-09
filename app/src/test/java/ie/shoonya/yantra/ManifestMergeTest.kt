package ie.shoonya.yantra

import ie.shoonya.yantra.data.sync.ConflictResolver
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest is the one file that coordinates the others, so it must not be resolved by
 * "keep local": a format bump on one phone and an archive setting on the other are not a
 * disagreement, and neither may erase the other.
 */
class ManifestMergeTest {

    private val path = WorkspaceStore.MANIFEST_PATH
    private fun m(s: String) = s.trimIndent().toByteArray()

    private val base = m("""{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0}""")

    @Test
    fun `independent edits both survive`() {
        val bumped = m("""{"formatVersion":2,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0}""")
        val archived = m("""{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":30}""")
        val r = ConflictResolver.resolve(path, local = archived, remote = bumped, device = "a", otherDevice = "b", base = base)
        val text = r.bytes!!.decodeToString()
        assertTrue(text, text.contains("\"formatVersion\":2"))
        assertTrue(text, text.contains("\"archive_after_days\":30"))
        assertEquals("merged the manifest field by field", r.reason)
    }

    @Test
    fun `both devices reach identical bytes whichever way round they rebase`() {
        val a = m("""{"formatVersion":2,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":7,"inkFormat":"ynk1"}""")
        val b = m("""{"formatVersion":1,"name":"renamed","createdAt":1,"epoch":2,"archive_after_days":30}""")
        val onA = ConflictResolver.resolve(path, local = a, remote = b, device = "a", otherDevice = "b", base = base).bytes!!
        val onB = ConflictResolver.resolve(path, local = b, remote = a, device = "b", otherDevice = "a", base = base).bytes!!
        assertArrayEquals(onA, onB)
        val text = onA.decodeToString()
        // Monotonic fields take the higher value; both sides' new fields are present.
        assertTrue(text, text.contains("\"formatVersion\":2"))
        assertTrue(text, text.contains("\"epoch\":2"))
        assertTrue(text, text.contains("\"name\":\"renamed\""))
        assertTrue(text, text.contains("\"inkFormat\":\"ynk1\""))
        // Both changed archive_after_days; the device that sorts higher ("b") wins on both machines.
        assertTrue(text, text.contains("\"archive_after_days\":30"))
    }

    @Test
    fun `without a base the old rule still applies`() {
        val a = m("""{"formatVersion":2,"name":"tasks","createdAt":1}""")
        val b = m("""{"formatVersion":1,"name":"tasks","createdAt":1}""")
        val r = ConflictResolver.resolve(path, local = a, remote = b, device = "a", otherDevice = "b", base = null)
        assertArrayEquals(a, r.bytes)
        assertEquals("kept the local side of an unmergeable file", r.reason)
    }
}
