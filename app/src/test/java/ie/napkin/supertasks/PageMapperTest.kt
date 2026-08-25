package ie.napkin.supertasks

import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.format.PageCodec
import ie.napkin.supertasks.data.workspace.PageMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The index is derived from files and thrown away, so the property that matters is not "does it
 * parse" but **does a page survive the trip through the index unchanged**. If it does, deleting the
 * database is safe; if it does not, the index has quietly become a second source of truth.
 */
class PageMapperTest {

    private val zone: ZoneId = ZoneId.of("Europe/Dublin")

    private val page = """
        ---
        id: 7c3f
        type: task
        parent: 1a2b
        modified_at: 2026-08-25T14:22:31.402Z
        device: sm-s921b
        ---
        # Welcome

        A list holds tasks.

        - [ ] Tasks can nest ^9f1e due:2026-08-26 !high #sync @batunii
        » - [x] Finished subtask ^4d2c deadline:2026-09-01
        - [~] Started one ^7a10

        ![[ink:5b8a]]
    """.trimIndent() + "\n"

    /** Rows back to a page, the way the reconciler will do it. */
    private fun roundTrip(source: String): String {
        val doc = PageCodec.decode(source)
        val m = PageMapper.toRows(doc, "", zone)
        return PageCodec.encode(
            PageMapper.toPage(
                node = m.page,
                children = m.children,
                valuesByNode = m.values.groupBy { it.nodeId },
                labelNamesByNode = m.labels.groupBy({ it.nodeId }, { it.name }),
                device = doc.device,
                titleIsOwn = doc.parent == null,
                zone = zone,
            )
        )
    }

    @Test
    fun `a page survives the trip through the index`() {
        assertEquals(page, roundTrip(page))
    }

    @Test
    fun `a top-level page keeps its own title`() {
        val list = "---\nid: L1\ntype: list\ntitle: Getting started\nsystem_key: inbox\n" +
            "modified_at: 2026-01-01T00:00:00Z\n---\n- [ ] one ^t1\n"
        assertEquals(list, roundTrip(list))
    }

    @Test
    fun `a nested page does not write a title of its own`() {
        // The line on the parent owns it. Writing it here too is the duplication the format avoids.
        val doc = PageCodec.decode(page)
        val m = PageMapper.toRows(doc, "", zone)
        val out = PageCodec.encode(
            PageMapper.toPage(m.page, m.children, emptyMap(), emptyMap(), null, titleIsOwn = false)
        )
        assertTrue("a nested page wrote a title", !out.contains("title:"))
    }

    // ---- rows ----

    @Test
    fun `blocks become rows of the right type, in order`() {
        val kinds = PageMapper.toRows(PageCodec.decode(page), "", zone).children.map { it.type }
        assertEquals(
            listOf(
                NodeType.HEADING, NodeType.PARAGRAPH,
                NodeType.TASK, NodeType.TASK, NodeType.TASK,
                NodeType.INK,
            ),
            kinds,
        )
    }

    @Test
    fun `ranks follow line order`() {
        val ranks = PageMapper.toRows(PageCodec.decode(page), "", zone).children.map { it.rank }
        assertEquals("ranks are not ascending", ranks.sorted(), ranks)
        assertEquals("ranks are not distinct", ranks.size, ranks.toSet().size)
    }

    @Test
    fun `status maps onto the done and in-progress pair`() {
        val tasks = PageMapper.toRows(PageCodec.decode(page), "", zone)
            .children.filter { it.type == NodeType.TASK }
        assertEquals(listOf(false, true, false), tasks.map { it.done })
        assertEquals(listOf(false, false, true), tasks.map { it.inProgress })
        // Never both — completion supersedes, and the schema relies on it.
        assertTrue(tasks.none { it.done && it.inProgress })
    }

    @Test
    fun `ink and task ids come from the file, other blocks from their position`() {
        val c = PageMapper.toRows(PageCodec.decode(page), "", zone).children
        assertEquals("9f1e", c[2].id)
        assertEquals("5b8a", c[5].id)
        assertEquals(PageMapper.blockId("7c3f", 0), c[0].id)
    }

    @Test
    fun `indent survives as indent and not as parentage`() {
        val c = PageMapper.toRows(PageCodec.decode(page), "", zone).children
        assertEquals(1, c[3].indent)
        // Every block on a page is a direct child of it, whatever it looks like.
        assertTrue(c.all { it.parentId == "7c3f" })
    }

    // ---- properties ----

    @Test
    fun `an all-day due becomes local midnight and is marked as such`() {
        val v = PageMapper.toRows(PageCodec.decode(page), "", zone).values
            .single { it.nodeId == "9f1e" && it.defId == BuiltIns.DUE_DEF_ID }
        assertEquals(false, v.vBool)
        assertEquals(
            java.time.LocalDate.of(2026, 8, 26).atStartOfDay(zone).toInstant().toEpochMilli(),
            v.vDate,
        )
    }

    @Test
    fun `a timed due keeps the exact instant`() {
        val src = "---\nid: p\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n" +
            "- [ ] call ^t1 due:2026-08-26T09:00:00Z\n"
        val v = PageMapper.toRows(PageCodec.decode(src), "", zone).values.single()
        assertEquals(true, v.vBool)
        assertEquals(java.time.Instant.parse("2026-08-26T09:00:00Z").toEpochMilli(), v.vDate)
        assertEquals(src, roundTrip(src))
    }

    @Test
    fun `a reminder offset survives the index, negative and all`() {
        val src = "---\nid: p\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n" +
            "- [ ] wake ^t1 due:2026-08-26+r-540\n"
        assertEquals(-540.0, PageMapper.toRows(PageCodec.decode(src), "", zone).values.single().vNumber)
        assertEquals(src, roundTrip(src))
    }

    @Test
    fun `properties use the stable built-in ids`() {
        // The per-install UUID is what made a shared smart list match nothing on a second device.
        val ids = PageMapper.toRows(PageCodec.decode(page), "", zone).values.map { it.defId }.toSet()
        assertTrue(ids.all { it in BuiltIns.ALL_DEF_IDS })
    }

    @Test
    fun `labels are collected per node by name`() {
        val l = PageMapper.toRows(PageCodec.decode(page), "", zone).labels
        assertEquals(1, l.size)
        assertEquals("9f1e", l[0].nodeId)
        assertEquals("sync", l[0].name)
    }

    @Test
    fun `ink blocks report the sidecars a page needs`() {
        assertEquals(listOf("5b8a"), PageMapper.toRows(PageCodec.decode(page), "", zone).inkNodeIds)
    }

    // ---- what the index is not allowed to remember ----

    @Test
    fun `fields the format drops come back at their defaults`() {
        // collapsed is device-local, deleted_at is git's job, canvas is unused. If any of these
        // survived a round trip the index would have become a second source of truth.
        val m = PageMapper.toRows(PageCodec.decode(page), "", zone)
        assertTrue(m.nodes.none { it.collapsed })
        assertTrue(m.nodes.all { it.deletedAt == null })
        assertTrue(m.nodes.all { it.canvasX == null && it.canvasY == null })
    }

    @Test
    fun `an empty page maps to a bare node and nothing else`() {
        val m = PageMapper.toRows(
            PageCodec.decode("---\nid: e\ntype: list\ntitle: Empty\nmodified_at: 2026-01-01T00:00:00Z\n---\n"),
            "", zone,
        )
        assertTrue(m.children.isEmpty())
        assertTrue(m.values.isEmpty())
        assertEquals("Empty", m.page.title)
        assertNull(m.page.parentId)
    }
}
