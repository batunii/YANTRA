package ie.shoonya.yantra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The log keeps what was asked of it, and never costs more than it is worth.
 *
 * Written because the whole point of [Diagnostics] is being there *later* — a report that arrives
 * hours after the fact, about a build nobody had attached. A log that silently dropped the run-up
 * to a crash, or grew without limit on somebody's phone, or threw on a full disk and took the app
 * with it, would each be worse than not having one.
 */
class DiagnosticsSinkTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sink(maxBytes: Long = 1_000L) = DiagnosticsSink(File(tmp.root, "diagnostics"), maxBytes)

    @Test
    fun `it writes the first line to a directory that does not exist yet`() {
        val s = sink()
        s.write("hello\n")
        assertTrue("the directory is made on demand", s.current.exists())
        assertEquals("hello\n", s.current.readText())
    }

    @Test
    fun `lines accumulate in the order they arrive`() {
        val s = sink()
        s.write("one\n"); s.write("two\n"); s.write("three\n")
        assertEquals(listOf("one", "two", "three"), s.current.readLines())
    }

    @Test
    fun `it rotates once the cap is passed`() {
        val s = sink(maxBytes = 100)
        s.write("x".repeat(150) + "\n")
        assertFalse("nothing rotates until a write finds the file over the cap", s.previous.exists())
        s.write("after\n")
        assertTrue(s.previous.exists())
        assertEquals("the new file starts with what came next", "after\n", s.current.readText())
    }

    @Test
    fun `the run-up to a crash is still readable after a rotation`() {
        // The case this exists for: rotate, then crash. The lines just before it are in the
        // previous file rather than lost, which is why two are kept rather than one.
        val s = sink(maxBytes = 50)
        s.write("the interesting part\n" + "x".repeat(60) + "\n")
        s.write("=== CRASH ===\n")
        assertTrue(s.previous.readText().contains("the interesting part"))
        assertTrue(s.current.readText().contains("CRASH"))
    }

    @Test
    fun `only one generation is kept`() {
        val s = sink(maxBytes = 10)
        repeat(5) { s.write("line $it ${"x".repeat(20)}\n") }
        val files = s.dir.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("previous.log", "session.log"), files)
    }

    @Test
    fun `a directory it cannot write to is survived rather than thrown`() {
        // A log that can take the app down is worse than no log.
        val blocked = File(tmp.newFile("not-a-directory"), "diagnostics")
        val s = DiagnosticsSink(blocked, 1_000)
        s.write("this cannot possibly land\n")
        assertFalse(s.current.exists())
    }

    @Test
    fun `an empty write does not rotate or fail`() {
        val s = sink(maxBytes = 10)
        s.write("")
        assertTrue(s.current.exists())
        assertEquals("", s.current.readText())
    }
}
