package ie.shoonya.yantra

import ie.shoonya.yantra.data.format.Block
import ie.shoonya.yantra.data.format.Bullet
import ie.shoonya.yantra.data.format.DueSpec
import ie.shoonya.yantra.data.format.DueValue
import ie.shoonya.yantra.data.format.Heading
import ie.shoonya.yantra.data.format.ImageRef
import ie.shoonya.yantra.data.format.InkRef
import ie.shoonya.yantra.data.format.Numbered
import ie.shoonya.yantra.data.format.PageCodec
import ie.shoonya.yantra.data.format.PageDoc
import ie.shoonya.yantra.data.format.Prose
import ie.shoonya.yantra.data.format.TaskRef
import ie.shoonya.yantra.data.format.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The format is the contract everything else layers on, so these tests are about *preservation*
 * more than parsing: what survives a round trip, what survives an edit to a neighbouring line, and
 * what happens to text this version does not understand.
 */
class PageCodecTest {

    private val canonical = """
        ---
        id: 7c3f
        type: task
        parent: 1a2b
        title: Wire up the sync worker
        modified_at: 2026-08-25T14:22:31.402Z
        device: sm-s921b
        ---
        # Welcome

        A list holds tasks.

        - [ ] Tasks can nest ^9f1e due:2026-08-26 !high #sync @batunii
        » - [x] An indented, finished subtask ^4d2c

        ![[ink:5b8a]]
    """.trimIndent() + "\n"

    // ---- the round trip ----

    @Test
    fun `canonical text round-trips byte for byte`() {
        assertEquals(canonical, PageCodec.encode(PageCodec.decode(canonical)))
    }

    @Test
    fun `decoding is stable across a round trip`() {
        val once = PageCodec.decode(canonical)
        val twice = PageCodec.decode(PageCodec.encode(once))
        assertEquals(once, twice)
    }

    @Test
    fun `frontmatter survives`() {
        val p = PageCodec.decode(canonical)
        assertEquals("7c3f", p.id)
        assertEquals("task", p.type)
        assertEquals("1a2b", p.parent)
        assertEquals("Wire up the sync worker", p.title)
        assertEquals(Instant.parse("2026-08-25T14:22:31.402Z"), p.modifiedAt)
        assertEquals("sm-s921b", p.device)
    }

    @Test
    fun `every block type is recognised`() {
        val b = PageCodec.decode(canonical).blocks
        assertEquals(5, b.size)
        assertTrue(b[0] is Heading)
        assertTrue(b[1] is Prose)
        assertTrue(b[2] is TaskRef)
        assertTrue(b[3] is TaskRef)
        assertTrue(b[4] is InkRef)
        assertEquals(1, b[3].indent)
        assertEquals(TaskStatus.DONE, (b[3] as TaskRef).status)
    }

    // ---- what an edit is allowed to disturb ----

    @Test
    fun `a modified block is re-rendered even though its raw line is stale`() {
        // No call to any "I changed this" helper. The emitter notices on its own, which is the
        // difference between a format that is safe to use and one that is safe if you are careful.
        val page = PageCodec.decode(canonical)
        val out = PageCodec.encode(
            page.copy(
                blocks = page.blocks.toMutableList().also {
                    it[1] = (it[1] as Prose).copy(text = "Rewritten prose.")
                }
            )
        )
        assertTrue(out.contains("Rewritten prose."))
        assertTrue("stale line survived", !out.contains("A list holds tasks."))
    }

    @Test
    fun `a moved numbered item is renumbered rather than kept at its old number`() {
        val src = "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n" +
            "1. first\n2. second\n3. third\n"
        val p = PageCodec.decode(src)
        val reordered = p.copy(blocks = listOf(p.blocks[2], p.blocks[0], p.blocks[1]))
        val out = PageCodec.encode(reordered)
        assertEquals(listOf("1. third", "2. first", "3. second"), out.lines().filter { it.contains(". ") })
    }

    @Test
    fun `editing one block leaves its neighbours byte-identical`() {
        // The property that lets someone keep the file open in Emacs while the app runs.
        val page = PageCodec.decode(canonical)
        val task = page.blocks[2] as TaskRef
        val flipped = page.copy(
            blocks = page.blocks.toMutableList().also {
                it[2] = task.copy(status = TaskStatus.DONE)
            }
        )
        val out = PageCodec.encode(flipped)

        assertTrue("the edited line did not change", out.contains("- [x] Tasks can nest ^9f1e"))
        // Everything else arrives exactly as it left.
        canonical.lines().filter { it.isNotBlank() && !it.contains("Tasks can nest") }
            .forEach { assertTrue("lost or reformatted: $it", out.contains(it)) }
    }

    @Test
    fun `an untouched hand-edited line keeps its own spacing`() {
        // Two spaces after the dash, and a trailing double space. Nobody's formatter, but theirs.
        val odd = "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n" +
            "-  [ ] loosely typed ^z1\n" +
            "- [ ] normal ^z2\n"
        val page = PageCodec.decode(odd)
        val out = PageCodec.encode(
            page.copy(
                blocks = page.blocks.toMutableList().also {
                    it[1] = (it[1] as TaskRef).copy(title = "renamed")
                }
            )
        )
        assertTrue("reformatted a line it did not touch", out.contains("-  [ ] loosely typed ^z1"))
        assertTrue(out.contains("- [ ] renamed ^z2"))
    }

    // ---- the right-to-left token scan ----

    @Test
    fun `trailing tokens are parsed and a hash inside a title is not`() {
        val t = one("- [ ] Buy milk ^a1 due:2026-08-26 !high #shop @batunii")
        assertEquals("Buy milk", t.title)
        assertEquals("a1", t.id)
        assertEquals("high", t.priority)
        assertEquals(listOf("shop"), t.labels)
        assertEquals("batunii", t.assignee)
        assertEquals(DueValue.AllDay(LocalDate.of(2026, 8, 26)), t.due?.value)
    }

    @Test
    fun `a token-looking word mid-title stays in the title`() {
        // Right-to-left stops at `pencils`, so `#2` never gets read as a label.
        val t = one("- [ ] Buy #2 pencils ^a1")
        assertEquals("Buy #2 pencils", t.title)
        assertEquals("a1", t.id)
        assertTrue(t.labels.isEmpty())
    }

    @Test
    fun `labels keep their written order`() {
        assertEquals(listOf("one", "two", "three"), one("- [ ] x ^i #one #two #three").labels)
    }

    @Test
    fun `a task with no tokens at all is just a title`() {
        val t = one("- [ ] Just a task")
        assertEquals("Just a task", t.title)
        assertEquals("", t.id)
        assertNull(t.due)
    }

    // ---- due encoding ----

    @Test
    fun `all-day and timed are distinguished by the date-time itself`() {
        // hasTime comes free from whether the value has a T in it.
        assertEquals(
            DueValue.AllDay(LocalDate.of(2026, 8, 26)),
            one("- [ ] x ^i due:2026-08-26").due?.value,
        )
        assertEquals(
            DueValue.At(Instant.parse("2026-08-26T09:00:00Z")),
            one("- [ ] x ^i due:2026-08-26T09:00:00Z").due?.value,
        )
    }

    @Test
    fun `a negative reminder offset survives`() {
        // -540 is 09:00 on the day of an all-day task, which the DB encoding also allows.
        val due = one("- [ ] x ^i due:2026-08-26+r-540").due
        assertEquals(DueValue.AllDay(LocalDate.of(2026, 8, 26)), due?.value)
        assertEquals(listOf(-540), due?.reminders)
        assertTrue(render(TaskRef("i", "x", due = due!!)).endsWith("due:2026-08-26+r-540"))
    }

    @Test
    fun `a malformed due is left in the title rather than swallowed`() {
        val t = one("- [ ] pay rent due:not-a-date ^a1")
        assertEquals("pay rent due:not-a-date", t.title)
        assertNull(t.due)
    }

    // ---- forgiveness ----

    @Test
    fun `an unclassifiable line becomes prose holding its original text`() {
        val t = "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n" +
            "| a | markdown | table |\n"
        val b = PageCodec.decode(t).blocks.single()
        assertTrue(b is Prose)
        assertEquals("| a | markdown | table |", (b as Prose).text)
    }

    @Test
    fun `frontmatter from a newer app survives a round trip`() {
        // Dropping a key we do not know is how "it lost a field on my laptop" happens.
        val t = "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n" +
            "future_field: keep me\n---\nhello\n"
        val p = PageCodec.decode(t)
        assertEquals(mapOf("future_field" to "keep me"), p.unknownKeys)
        assertTrue(PageCodec.encode(p).contains("future_field: keep me"))
    }

    @Test
    fun `a file with no frontmatter still parses`() {
        val p = PageCodec.decode("# just a heading\n")
        assertEquals("", p.id)
        assertEquals(1, p.blocks.size)
    }

    @Test
    fun `windows line endings are accepted`() {
        val p = PageCodec.decode(canonical.replace("\n", "\r\n"))
        assertEquals(5, p.blocks.size)
        assertEquals("7c3f", p.id)
    }

    // ---- rendering rules ----

    @Test
    fun `numbered items are renumbered from their own run`() {
        val page = PageDoc(
            id = "a", type = "task", parent = null, title = null,
            modifiedAt = Instant.EPOCH, device = null,
            blocks = listOf(
                Numbered("first"), Numbered("second"), Numbered("third"),
                Prose("a break"),
                Numbered("restarts"),
            ),
        )
        val out = PageCodec.encode(page)
        assertTrue(out.contains("1. first"))
        assertTrue(out.contains("2. second"))
        assertTrue(out.contains("3. third"))
        assertTrue("the run should restart after prose", out.contains("1. restarts"))
    }

    @Test
    fun `a rendered task always produces the same bytes`() {
        // Two devices holding identical data must not produce a diff, or every sync looks like a
        // change and the history fills with noise.
        val t = TaskRef(
            id = "a1", title = "x", status = TaskStatus.OPEN,
            due = DueSpec(DueValue.AllDay(LocalDate.of(2026, 1, 1))),
            deadline = LocalDate.of(2026, 2, 2),
            priority = "high", labels = listOf("b", "a"), assignee = "me",
        )
        assertEquals(render(t), render(t.copy()))
        assertEquals(
            "- [ ] x ^a1 due:2026-01-01 deadline:2026-02-02 !high #b #a @me",
            render(t),
        )
    }

    @Test
    fun `indent is written back at the depth it was read`() {
        val b = PageCodec.decode(
            "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n» » deep prose\n"
        ).blocks.single()
        assertEquals(2, b.indent)
        assertTrue(PageCodec.encode(page(listOf(Prose("deep prose", 2)))).contains("» » deep prose"))
    }

    @Test
    fun `markdown blockquotes and callouts stay prose at depth zero`() {
        // What the guillemet buys: `>>` is a nested blockquote in markdown, and with `>>` as the
        // indent marker it was silently read as an indented line instead.
        listOf("> a quote", ">> a nested quote", "> [!NOTE] a callout").forEach { line ->
            val b = PageCodec.decode(
                "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n$line\n"
            ).blocks.single()
            assertTrue("$line was not prose", b is Prose)
            assertEquals(line, (b as Prose).text)
            assertEquals("$line was indented", 0, b.indent)
        }
    }

    @Test
    fun `ink and image blocks keep their ids`() {
        val blocks = PageCodec.decode(
            "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n" +
                "![[ink:abc]]\n![[image:content://x/y]]\n"
        ).blocks
        assertEquals("abc", (blocks[0] as InkRef).id)
        assertEquals("content://x/y", (blocks[1] as ImageRef).uri)
    }

    @Test
    fun `bullets are not mistaken for tasks`() {
        val b = PageCodec.decode(
            "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n- plain bullet\n"
        ).blocks.single()
        assertTrue(b is Bullet)
        assertEquals("plain bullet", (b as Bullet).text)
    }

    // ---- an empty block keeps its kind (P0-2) ----

    /**
     * The bug this pins: a task with nothing written in it renders as "- [ ] ", and the only thing
     * telling it apart from a bullet was the space on the end of the line. Anything that trims
     * trailing whitespace — an editor, a linter, a git hook — turned a checkbox into a bullet, and
     * the id after it into stray text on the line. Kind must not be inferred from content.
     */
    @Test
    fun `an empty task is still a task, with or without its trailing space`() {
        listOf("- [ ]", "- [ ] ", "- [x]", "- [~] ").forEach { line ->
            val b = PageCodec.decodeBlock(line)
            assertTrue("$line parsed as ${b::class.simpleName}", b is TaskRef)
            assertEquals("", (b as TaskRef).title)
        }
    }

    @Test
    fun `an empty task round-trips, id and all`() {
        listOf(
            TaskRef(id = "", title = ""),
            TaskRef(id = "abc-123", title = ""),
            TaskRef(id = "abc-123", title = "", status = TaskStatus.IN_PROGRESS),
        ).forEach { task ->
            val line = PageCodec.encodeBlock(task)
            assertEquals(line, line.trimEnd())
            assertEquals(task, PageCodec.decodeBlock(line).let { (it as TaskRef).copy(raw = null) })
        }
    }

    @Test
    fun `every block kind survives a full page round trip`() {
        val blocks = listOf(
            Heading(""), Bullet(""), Numbered(""), Prose(""),
            TaskRef(id = "t1", title = ""),
            TaskRef(id = "t2", title = "named"),
            InkRef("ink-1"), ImageRef("pic-1"),
        )
        val back = PageCodec.decode(PageCodec.encode(page(blocks))).blocks
        assertEquals(blocks.map { it::class }, back.map { it::class })
    }

    /**
     * The emitter has to put *something* on the line to keep an empty block, and what it puts must
     * not come back as content. It did: a blank note round-tripped into a note containing a space,
     * so the first thing typed into it was pushed one character right — visible on the first line
     * of a paragraph and nowhere else, because only the first line had it.
     */
    @Test
    fun `an empty block does not come back holding whitespace`() {
        val blank = Prose("")
        val line = PageCodec.encodeBlock(blank)
        assertTrue("an empty block still needs a line", line.isNotEmpty())
        assertEquals("", (PageCodec.decodeBlock(line) as Prose).text)

        val back = PageCodec.decode(PageCodec.encode(page(listOf(blank, Prose("after"))))).blocks
        assertEquals(listOf("", "after"), back.map { (it as Prose).text })
    }

    // ---- helpers ----

    private fun page(blocks: List<Block>) = PageDoc(
        id = "a", type = "task", parent = null, title = null,
        modifiedAt = Instant.EPOCH, device = null, blocks = blocks,
    )

    private fun one(line: String): TaskRef =
        PageCodec.decode(
            "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n$line\n"
        ).blocks.single() as TaskRef

    private fun render(t: TaskRef): String =
        PageCodec.encode(page(listOf(t))).lines().first { it.startsWith("- [") }

    // ---- links on a task line ----

    /** The smallest well-formed page, so a one-line test can be one line. */
    private val header = "---\nid: p\ntype: list\nmodified_at: 2026-09-01T00:00:00Z\n---\n"

    /**
     * The right-to-left token scan and `[[…|^id]]` have to coexist, and by default they do not.
     *
     * `[[Buy milk #2|^abc]]` ends in a word beginning with `#`, so the scan filed the task under a
     * label called `2|^abc]]` and took half the link out of the title on the way. The guard is that
     * nothing containing a link's closing brackets is a trailing token.
     */
    @Test
    fun `a link in a task title is not read as trailing tokens`() {
        val page = PageCodec.decode(
            """
            ---
            id: p1
            type: list
            modified_at: 2026-09-01T00:00:00Z
            ---
            - [ ] See [[Buy milk #2|^abc]] first ^t1 #real !High
            """.trimIndent()
        )
        val t = page.blocks.filterIsInstance<TaskRef>().single()
        assertEquals("See [[Buy milk #2|^abc]] first", t.title)
        assertEquals("t1", t.id)
        assertEquals(listOf("real"), t.labels)
        assertEquals("High", t.priority)
    }

    @Test
    fun `a link at the very end of a title survives the round trip`() {
        val line = "- [ ] Ask [[Bob|^9f1e]] ^t2 @batunii"
        val page = PageCodec.decode(header + line)
        val t = page.blocks.filterIsInstance<TaskRef>().single()
        assertEquals("Ask [[Bob|^9f1e]]", t.title)
        assertEquals("batunii", t.assignee)
        // Re-rendered from the model rather than replayed from raw, so the emitter is tested too.
        assertEquals(line, PageCodec.encodeBlock(t.copy(raw = null)))
    }

    /** An at-sign inside a link label is part of the label, not an assignee. */
    @Test
    fun `an at-sign inside a link is not an assignee`() {
        val page = PageCodec.decode(header + "- [ ] Ping [[ask @bob|^x]]")
        val t = page.blocks.filterIsInstance<TaskRef>().single()
        assertEquals("Ping [[ask @bob|^x]]", t.title)
        assertNull(t.assignee)
    }

    // ---- a list's appearance ----

    /**
     * The icon and the colour survive a round trip, and survive each other.
     *
     * Both are choices somebody made, so they live in the file rather than only in the index — a
     * list given an emoji on the phone has it on the laptop, and dropping the database costs
     * nothing. Which makes the codec the place they can be silently lost: a key left out of the
     * emitter writes a page that reads back plain, and nothing above here would notice, because a
     * list with no icon is a perfectly ordinary list.
     */
    @Test
    fun `a list keeps its icon and its colour`() {
        val original = PageDoc(
            id = "L1", type = "list", parent = null, title = "Shopping",
            modifiedAt = Instant.EPOCH, device = null, blocks = emptyList(),
            icon = "🛒", color = "Teal",
        )

        val text = PageCodec.encode(original)
        assertTrue("the icon is not in the file: $text", text.contains("icon: 🛒"))
        assertTrue("the colour is not in the file: $text", text.contains("color: Teal"))

        val back = PageCodec.decode(text)
        assertEquals("🛒", back.icon)
        assertEquals("Teal", back.color)
    }

    /** Independent: an emoji brings its own colours, so either may be set without the other. */
    @Test
    fun `an icon without a colour and a colour without an icon both round trip`() {
        val iconOnly = PageDoc(
            id = "L1", type = "list", parent = null, title = null,
            modifiedAt = Instant.EPOCH, device = null, blocks = emptyList(), icon = "🎯",
        )
        PageCodec.decode(PageCodec.encode(iconOnly)).let {
            assertEquals("🎯", it.icon)
            assertNull(it.color)
        }

        val colourOnly = iconOnly.copy(icon = null, color = "Plum")
        PageCodec.decode(PageCodec.encode(colourOnly)).let {
            assertNull(it.icon)
            assertEquals("Plum", it.color)
        }
    }

    /**
     * `icon` is a key this version understands, so it must not also be carried as an unknown one.
     *
     * Adding a key means adding it in two places — the parser and the `known` set — and forgetting
     * the second writes it out twice: once from the field and once from `unknownKeys`, which is a
     * file that no longer parses the way it was written.
     */
    @Test
    fun `icon is not mistaken for a key this version does not understand`() {
        val text = "---\nid: L1\ntype: list\nicon: 📚\nmodified_at: 2026-01-01T00:00:00Z\n---\n"
        val page = PageCodec.decode(text)

        assertEquals("📚", page.icon)
        assertTrue("icon leaked into unknownKeys: ${page.unknownKeys}", "icon" !in page.unknownKeys)
        assertEquals(1, PageCodec.encode(page).lines().count { it.startsWith("icon: ") })
    }

    // ---- several reminders on one task ----

    /**
     * `+r30,15` is two reminders, and `+r30` is still one.
     *
     * The older spelling had to keep parsing unchanged and keep *rendering* unchanged, or the first
     * sync after updating would rewrite every task that has a reminder — a diff of hundreds of
     * lines saying nothing, and a merge conflict on every device that had not updated yet.
     */
    @Test
    fun `a task can carry several reminders`() {
        val due = one("- [ ] x ^i due:2026-08-26T09:00:00Z+r1440,30").due
        assertEquals(listOf(1440, 30), due?.reminders)
        assertTrue(render(TaskRef("i", "x", due = due!!)).endsWith("+r1440,30"))
    }

    @Test
    fun `one reminder is written exactly as it always was`() {
        val due = one("- [ ] x ^i due:2026-08-26T09:00:00Z+r30").due
        assertEquals(listOf(30), due?.reminders)
        assertTrue(render(TaskRef("i", "x", due = due!!)).endsWith("+r30"))
    }

    /**
     * Largest first, whatever order they were written in, and no repeats.
     *
     * Two devices holding the same task have to produce the same bytes or every sync is a diff
     * about nothing — and the order is also the order they fire in, which is the order somebody
     * reads them back in.
     */
    @Test
    fun `reminders come back in a canonical order`() {
        val due = one("- [ ] x ^i due:2026-08-26T09:00:00Z+r30,1440,30").due
        assertEquals(listOf(1440, 30), due?.reminders)
    }

    /**
     * A tail that is partly unreadable is refused, not half-kept.
     *
     * Keeping `+r30` out of `+r30,x` would silently drop a reminder somebody set, which is the
     * exact failure this feature exists to avoid. Refusing the token leaves the line as prose,
     * where it is visible rather than quietly wrong.
     */
    @Test
    fun `a broken reminder tail does not half-parse`() {
        val task = one("- [ ] x ^i due:2026-08-26T09:00:00Z+r30,x")
        assertNull(task.due)
    }

    @Test
    fun `no reminder writes no tail`() {
        val due = one("- [ ] x ^i due:2026-08-26T09:00:00Z").due
        assertTrue(due!!.reminders.isEmpty())
        assertTrue(!render(TaskRef("i", "x", due = due)).contains("+r"))
    }
}
