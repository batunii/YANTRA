package ie.shoonya.yantra

import ie.shoonya.yantra.data.filter.FilterJson
import ie.shoonya.yantra.data.workspace.Manifest
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatVersionTest {

    @Test
    fun `this build reads its own version and older ones`() {
        assertTrue(Manifest(name = "x", createdAt = 0).readable)
        assertTrue(Manifest(formatVersion = 1, name = "x", createdAt = 0).readable)
        assertFalse(Manifest(formatVersion = WorkspaceStore.FORMAT_VERSION + 1, name = "x", createdAt = 0).readable)
    }

    @Test
    fun `the version is always written, even when it is the default`() {
        // encodeDefaults=false used to drop it, so every manifest read as whatever the reader assumed.
        val text = FilterJson.encodeToString(Manifest.serializer(), Manifest(name = "x", createdAt = 0))
        assertTrue(text, text.contains("\"formatVersion\":1"))
    }

    @Test
    fun `a manifest without the field reads as version 1`() {
        // Pre-0.4.0 manifests omitted it. They were format 1; the default must not promote them.
        val m = FilterJson.decodeFromString(Manifest.serializer(), """{"name":"x","createdAt":0}""")
        assertEquals(1, m.formatVersion)
    }
}
