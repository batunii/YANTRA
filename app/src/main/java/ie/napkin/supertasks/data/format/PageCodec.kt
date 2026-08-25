package ie.napkin.supertasks.data.format

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads and writes a page file — GIT_WORKSPACES_PLAN.md §2.
 *
 * ## The round-trip contract
 *
 * **A block's own text survives byte-exact unless the app edits that block.** Each block keeps the
 * line it was parsed from and the emitter prefers it, so touching one task cannot reformat the
 * paragraph above it. That is the property that lets someone keep a page open in Emacs.
 *
 * **Blank-line layout between blocks is canonical, not preserved.** Blank lines belong to no block,
 * so there is nowhere honest to hang them; the emitter puts one after prose and headings and none
 * between consecutive list items, which is what markdown looks like anyway.
 *
 * ## Forgiving on the way in
 *
 * A line this parser cannot classify becomes [Prose] holding the original text, and a frontmatter
 * key it does not know is kept in [PageDoc.unknownKeys]. Nothing is ever dropped for being
 * unrecognised — a silently vanished task is indistinguishable from data loss, and the file may
 * well have been written by a newer version of the app.
 */
object PageCodec {

    /**
     * One level of visual indent. Deliberately not nesting — see [PageDoc].
     *
     * A guillemet rather than `>>` because `>>` is a nested blockquote in markdown, and rather than
     * leading whitespace because markdown reads indentation as list *nesting* — which is precisely
     * the conflation this format exists to avoid. This character means nothing to markdown, so
     * blockquotes and callouts pass through as the prose they are.
     */
    const val INDENT = "\u00BB"

    private const val FENCE = "---"

    // ---- decode ----

    fun decode(text: String): PageDoc {
        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        val front = LinkedHashMap<String, String>()

        if (lines.getOrNull(0)?.trim() == FENCE) {
            i = 1
            while (i < lines.size && lines[i].trim() != FENCE) {
                val line = lines[i]
                val colon = line.indexOf(':')
                if (colon > 0) front[line.take(colon).trim()] = line.drop(colon + 1).trim()
                i++
            }
            i++ // closing fence
        }

        val known = setOf("id", "type", "parent", "title", "system_key", "modified_at", "device")
        val blocks = ArrayList<Block>()
        while (i < lines.size) {
            val line = lines[i]
            // Empty, not blank. A truly empty line is the separator the emitter puts between
            // blocks; a line holding only whitespace is an empty *block*, which the editor needs to
            // exist because it makes one and then types into it. Skipping both, as this did, meant
            // every new note and every ink block was written and then read back as nothing.
            if (line.isNotEmpty()) blocks += parseBlock(line)
            i++
        }

        return PageDoc(
            id = front["id"].orEmpty(),
            type = front["type"].orEmpty(),
            parent = front["parent"]?.takeIf { it.isNotBlank() },
            title = front["title"]?.takeIf { it.isNotBlank() },
            systemKey = front["system_key"]?.takeIf { it.isNotBlank() },
            modifiedAt = front["modified_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.EPOCH,
            device = front["device"]?.takeIf { it.isNotBlank() },
            blocks = blocks,
            unknownKeys = front.filterKeys { it !in known },
        )
    }

    /** Peels indent markers off the front, returning the depth and what is left. */
    private fun splitIndent(line: String): Pair<Int, String> {
        var rest = line
        var depth = 0
        while (rest.startsWith(INDENT)) {
            depth++
            rest = rest.removePrefix(INDENT).removePrefix(" ")
        }
        return depth to rest
    }

    private fun parseBlock(raw: String): Block {
        val (indent, body) = splitIndent(raw)
        val rest = body.trimEnd()

        inkOrImage(rest, indent, raw)?.let { return it }

        val status = when {
            rest.startsWith("- [ ] ") -> TaskStatus.OPEN
            rest.startsWith("- [x] ") -> TaskStatus.DONE
            rest.startsWith("- [~] ") -> TaskStatus.IN_PROGRESS
            else -> null
        }
        if (status != null) return parseTask(rest.drop(6), status, indent, raw)

        return when {
            rest.startsWith("# ") -> Heading(rest.drop(2), indent, raw)
            rest.startsWith("- ") -> Bullet(rest.drop(2), indent, raw)
            NUMBERED.matches(rest) -> Numbered(NUMBERED.find(rest)!!.groupValues[1], indent, raw)
            else -> Prose(rest, indent, raw)
        }
    }

    private val NUMBERED = Regex("""^\d+\.\s+(.*)$""")
    private val INK = Regex("""^!\[\[ink:([^\]]+)]]$""")
    private val IMAGE = Regex("""^!\[\[image:([^\]]+)]]$""")

    private fun inkOrImage(rest: String, indent: Int, raw: String): Block? {
        INK.find(rest)?.let { return InkRef(it.groupValues[1], indent, raw) }
        IMAGE.find(rest)?.let { return ImageRef(it.groupValues[1], indent, raw) }
        return null
    }

    /**
     * Splits a task line into title and trailing tokens, scanning **right to left** and stopping at
     * the first word that is not a token.
     *
     * That direction is the whole trick. "Buy milk #groceries" tags the task; "Buy #2 pencils" does
     * not, because the scan hits `pencils` and stops, leaving `#2` where it belongs — in the title.
     * A left-to-right scan would have to guess, and would guess wrong on anything containing a hash
     * or an at-sign, which is most of how people write.
     */
    private fun parseTask(body: String, status: TaskStatus, indent: Int, raw: String): TaskRef {
        val words = body.trim().split(" ").toMutableList()
        var id = ""
        var due: DueSpec? = null
        var deadline: LocalDate? = null
        var priority: String? = null
        var assignee: String? = null
        val labels = ArrayList<String>()

        while (words.isNotEmpty()) {
            val w = words.last()
            val consumed = when {
                w.startsWith("^") && id.isEmpty() -> { id = w.drop(1); true }
                w.startsWith("due:") -> parseDue(w.removePrefix("due:"))?.also { due = it } != null
                w.startsWith("deadline:") ->
                    parseDate(w.removePrefix("deadline:"))?.also { deadline = it } != null
                w.startsWith("!") && w.length > 1 -> { priority = w.drop(1); true }
                w.startsWith("@") && w.length > 1 -> { assignee = w.drop(1); true }
                w.startsWith("#") && w.length > 1 -> { labels += w.drop(1); true }
                else -> false
            }
            if (!consumed) break
            words.removeAt(words.size - 1)
        }

        return TaskRef(
            id = id,
            title = words.joinToString(" "),
            status = status,
            indent = indent,
            due = due,
            deadline = deadline,
            priority = priority,
            labels = labels.reversed(),   // scanned right to left
            assignee = assignee,
            raw = raw,
        )
    }

    /** `2026-08-26`, `2026-08-26T09:00:00Z`, either optionally suffixed `+r<minutes>`. */
    private fun parseDue(token: String): DueSpec? {
        val at = token.indexOf("+r")
        val body = if (at >= 0) token.take(at) else token
        val reminder = if (at >= 0) token.drop(at + 2).toIntOrNull() else null
        if (at >= 0 && reminder == null) return null
        val value = if (body.contains('T')) {
            runCatching { Instant.parse(body) }.getOrNull()?.let { DueValue.At(it) }
        } else {
            parseDate(body)?.let { DueValue.AllDay(it) }
        }
        return value?.let { DueSpec(it, reminder) }
    }

    private fun parseDate(s: String): LocalDate? =
        try { LocalDate.parse(s) } catch (_: DateTimeParseException) { null }

    // ---- encode ----

    fun encode(page: PageDoc): String = buildString {
        append(FENCE).append('\n')
        append("id: ").append(page.id).append('\n')
        append("type: ").append(page.type).append('\n')
        page.parent?.let { append("parent: ").append(it).append('\n') }
        page.title?.let { append("title: ").append(it).append('\n') }
        page.systemKey?.let { append("system_key: ").append(it).append('\n') }
        append("modified_at: ").append(page.modifiedAt).append('\n')
        page.device?.let { append("device: ").append(it).append('\n') }
        page.unknownKeys.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
        append(FENCE).append('\n')

        page.blocks.forEachIndexed { i, block ->
            if (i > 0 && needsBlankBefore(page.blocks[i - 1], block)) append('\n')
            val ordinal = ordinalOf(page.blocks, i)
            val text = if (rawStillDescribes(block, ordinal)) block.raw!! else render(block, ordinal)
            // An empty block still has to occupy a line, or reading the file back would lose it.
            append(text.ifEmpty { " " })
            append('\n')
        }
    }

    /**
     * Whether a block's original line still says exactly what the block now says.
     *
     * Re-parsing the source and comparing is what makes preservation safe. The obvious design —
     * have callers null out [Block.raw] whenever they change something — works right up until
     * somebody forgets, and then the stale line is written back and the edit vanishes with no error
     * anywhere. Verifying costs a parse per block and cannot be forgotten.
     *
     * Numbered items need the extra check: their ordinal is positional and therefore not part of
     * the model, so a moved item compares equal to its own stale text.
     */
    private fun rawStillDescribes(block: Block, ordinal: Int): Boolean {
        val raw = block.raw ?: return false
        if (stripRaw(parseBlock(raw)) != stripRaw(block)) return false
        if (block !is Numbered) return true
        return NUMBERED_PREFIX.find(splitIndent(raw).second)?.groupValues?.get(1)?.toIntOrNull() == ordinal
    }

    private val NUMBERED_PREFIX = Regex("""^(\d+)\.""")

    private fun stripRaw(b: Block): Block = when (b) {
        is Prose -> b.copy(raw = null)
        is Heading -> b.copy(raw = null)
        is Bullet -> b.copy(raw = null)
        is Numbered -> b.copy(raw = null)
        is TaskRef -> b.copy(raw = null)
        is InkRef -> b.copy(raw = null)
        is ImageRef -> b.copy(raw = null)
    }

    /** Prose and headings breathe; consecutive list items do not. */
    private fun needsBlankBefore(prev: Block, next: Block): Boolean {
        val listish = { b: Block -> b is TaskRef || b is Bullet || b is Numbered }
        return !(listish(prev) && listish(next))
    }

    /** Numbered items count from the start of their own unbroken, same-indent run. */
    private fun ordinalOf(blocks: List<Block>, index: Int): Int {
        val here = blocks[index]
        if (here !is Numbered) return 0
        var n = 1
        var i = index - 1
        while (i >= 0) {
            val b = blocks[i]
            if (b !is Numbered || b.indent != here.indent) break
            n++
            i--
        }
        return n
    }

    private fun render(block: Block, ordinal: Int): String {
        // Markers are space-separated, matching what the parser accepts. Writing ">>>>" for depth
        // two would still parse, but the file would stop looking like the one the user typed.
        val pad = if (block.indent == 0) "" else List(block.indent) { INDENT }.joinToString(" ") + " "
        return pad + when (block) {
            is Heading -> "# ${block.text}"
            is Bullet -> "- ${block.text}"
            is Numbered -> "$ordinal. ${block.text}"
            is Prose -> block.text
            is InkRef -> "![[ink:${block.id}]]"
            is ImageRef -> "![[image:${block.uri}]]"
            is TaskRef -> renderTask(block)
        }
    }

    private fun renderTask(t: TaskRef): String = buildString {
        append(
            when (t.status) {
                TaskStatus.OPEN -> "- [ ] "
                TaskStatus.DONE -> "- [x] "
                TaskStatus.IN_PROGRESS -> "- [~] "
            }
        )
        append(t.title)
        // Order is fixed so the same task always renders the same bytes — otherwise two devices
        // holding identical data would produce a diff, and every sync would look like a change.
        if (t.id.isNotEmpty()) append(" ^").append(t.id)
        t.due?.let { append(" due:").append(renderDue(it)) }
        t.deadline?.let { append(" deadline:").append(it) }
        t.priority?.let { append(" !").append(it) }
        t.labels.forEach { append(" #").append(it) }
        t.assignee?.let { append(" @").append(it) }
    }

    private fun renderDue(d: DueSpec): String {
        val body = when (val v = d.value) {
            is DueValue.AllDay -> v.date.toString()
            is DueValue.At -> v.instant.toString()
        }
        return if (d.reminderMin == null) body else "$body+r${d.reminderMin}"
    }
}
