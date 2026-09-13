package ie.shoonya.yantra.data.format

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
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
        // Deliberately not trimmed. An empty heading is written "# " and an empty bullet "- ", and
        // trimming turns those into "#" and "-", which match nothing and come back as prose. The
        // marker's trailing space is part of the marker, not whitespace to tidy away.
        val rest = body

        inkOrImage(rest, indent, raw)?.let { return it }

        // The box, with or without anything after it.
        //
        // Requiring the trailing space made a block's *kind* depend on its content: an empty task
        // renders as "- [ ] " and the only thing distinguishing it from a bullet is a space at the
        // end of the line — which any editor, any linter and most git hooks will strip. The line
        // then came back as a bullet, so a task you made and did not type into changed into
        // something else behind your back. A checkbox is a checkbox whether or not it says anything.
        // `@ ` cannot collide with the checkbox or with a bullet, so order here is only for reading.
        if (rest.startsWith(EVENT_MARKER)) {
            parseEvent(rest.drop(EVENT_MARKER.length), indent, raw)?.let { return it }
            // A `@ ` line whose time makes no sense is not an event and must not be silently
            // dropped; it falls through to prose holding exactly what was written, which is what
            // this parser does with everything it cannot classify.
        }

        val marker = TASK_MARKER.find(rest)
        if (marker != null) {
            val status = when (marker.groupValues[1]) {
                "x", "X" -> TaskStatus.DONE
                "~" -> TaskStatus.IN_PROGRESS
                else -> TaskStatus.OPEN
            }
            return parseTask(rest.drop(marker.value.length), status, indent, raw)
        }

        return when {
            rest.startsWith("# ") -> Heading(rest.drop(2), indent, raw)
            rest.startsWith("- ") -> Bullet(rest.drop(2), indent, raw)
            NUMBERED.matches(rest) -> Numbered(NUMBERED.find(rest)!!.groupValues[1], indent, raw)
            // A line with nothing but whitespace on it is an empty block, and empty is what it has
            // to come back as. The emitter writes a lone space for an empty block because a block
            // has to occupy a line — and this read that space back as the block's *text*. So every
            // note started from the write line was born holding a space, and the first thing anyone
            // typed landed after it: one character of indent on the first line of a paragraph, on
            // its own line only, that nobody put there and nothing else in the app could explain.
            rest.isBlank() -> Prose("", indent, raw)
            else -> Prose(rest, indent, raw)
        }
    }

    /** `- [ ]`, `- [x]`, `- [~]`, each with or without a following space. See [parseBlock]. */
    private val TASK_MARKER = Regex("""^- \[([ xX~])] ?""")

    /** An event line. See [EventRef] for why it is not `* `. */
    const val EVENT_MARKER = "@ "

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
        var doneAt: LocalDate? = null
        var priority: String? = null
        var assignee: String? = null
        val labels = ArrayList<String>()

        while (words.isNotEmpty()) {
            val w = words.last()
            val consumed = when {
                // A word carrying a link's closing brackets is part of the title, whatever it
                // starts with. Without this the scan reads `[[Buy milk #2|^abc]]` from the right,
                // sees a word beginning `#`, and files the task under a label called
                // `2|^abc]]` — taking half the link out of the title on the way. The right-to-left
                // rule is about trailing *tokens*, and nothing that closes a link is one.
                w.contains(Links.CLOSE) -> false
                w.startsWith("^") && id.isEmpty() -> { id = w.drop(1); true }
                w.startsWith("due:") -> parseDue(w.removePrefix("due:"))?.also { due = it } != null
                w.startsWith("deadline:") ->
                    parseDate(w.removePrefix("deadline:"))?.also { deadline = it } != null
                w.startsWith("done:") ->
                    parseDate(w.removePrefix("done:"))?.also { doneAt = it } != null
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
            doneAt = doneAt,
            raw = raw,
        )
    }

    /**
     * `@ <when> <title> <tokens…>`.
     *
     * The when comes **first**, unlike a task's `due:`, for two reasons: a line that opens with its
     * time reads like a calendar, and a file of events sorts and greps by time without a parser. It
     * is always exactly one whitespace-free word, so the split is unambiguous.
     *
     * Everything after it is scanned right to left for tokens, the same way and for the same reason
     * as [parseTask]: "Coffee with #2" keeps its title, because the scan stops at the first word
     * that is not a token rather than guessing from the left.
     *
     * Returns null for a when-slot that will not parse, so the caller can fall back to prose. A `@ `
     * line this build cannot read is somebody's text, not an error to swallow.
     */
    private fun parseEvent(body: String, indent: Int, raw: String): EventRef? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val whenWord = trimmed.substringBefore(' ')
        val time = parseWhen(whenWord) ?: return null

        val words = trimmed.removePrefix(whenWord).trim().split(" ").filter { it.isNotEmpty() }.toMutableList()
        var id = ""
        var rrule: String? = null
        var forTask: String? = null
        var series: SeriesRef? = null
        var cancelled = false
        var location: String? = null
        var color: String? = null
        var reminder: Int? = null
        var priority: String? = null
        val labels = ArrayList<String>()
        val attendees = ArrayList<String>()

        while (words.isNotEmpty()) {
            val w = words.last()
            val consumed = when {
                w.contains(Links.CLOSE) -> false
                w.startsWith("^") && id.isEmpty() -> { id = w.drop(1); true }
                w.startsWith("rrule:") -> { rrule = w.removePrefix("rrule:").takeIf { it.isNotEmpty() }; rrule != null }
                w.startsWith("for:") -> { forTask = w.removePrefix("for:").takeIf { it.isNotEmpty() }; forTask != null }
                w.startsWith("series:") -> parseSeries(w.removePrefix("series:"))?.also { series = it } != null
                w == "cancelled" -> { cancelled = true; true }
                w.startsWith("loc:") -> { location = w.removePrefix("loc:").takeIf { it.isNotEmpty() }; location != null }
                w.startsWith("col:") -> { color = w.removePrefix("col:").takeIf { it.isNotEmpty() }; color != null }
                w.startsWith("remind:") -> { reminder = w.removePrefix("remind:").toIntOrNull(); reminder != null }
                w.startsWith("!") && w.length > 1 -> { priority = w.drop(1); true }
                w.startsWith("@") && w.length > 1 -> { attendees += w.drop(1); true }
                w.startsWith("#") && w.length > 1 -> { labels += w.drop(1); true }
                else -> false
            }
            if (!consumed) break
            words.removeAt(words.size - 1)
        }

        return EventRef(
            id = id,
            title = words.joinToString(" "),
            time = time,
            rrule = rrule,
            forTaskId = forTask,
            series = series,
            cancelled = cancelled,
            location = location,
            color = color,
            reminderMin = reminder,
            labels = labels.reversed(),      // scanned right to left
            attendees = attendees.reversed(),
            priority = priority,
            indent = indent,
            raw = raw,
        )
    }

    /** `s1` or `s1@2026-10-28T09:00`. The bare form means "the occurrence at this line's own start". */
    private fun parseSeries(token: String): SeriesRef? {
        if (token.isEmpty()) return null
        val at = token.indexOf('@')
        if (at < 0) return SeriesRef(token)
        val id = token.take(at)
        if (id.isEmpty()) return null
        val original = parseLocal(token.drop(at + 1)) ?: return null
        return SeriesRef(id, original)
    }

    /**
     * The when-slot: an ISO-8601 instant-or-interval, in local time.
     *
     * | Written | Means |
     * |---|---|
     * | `2026-09-11` | all day, that day |
     * | `2026-09-11/2026-09-13` | all day, the 11th to the 13th **inclusive** |
     * | `2026-09-11T14:00` | a moment |
     * | `2026-09-11T14:00/PT1H` | an hour from then |
     * | `2026-09-11T14:00/2026-09-11T15:30` | until then |
     * | `2026-09-11T14:00[Europe/Dublin]/PT1H` | the same, pinned to a zone |
     *
     * All-day spans are inclusive in the text and exclusive in [EventTime], because the inclusive
     * reading is what a person writing "the 11th to the 13th" means and the exclusive one is what
     * arithmetic wants. This function is the seam.
     *
     * The split on `/` has to happen *outside* the zone brackets: `Europe/Dublin` contains one.
     */
    private fun parseWhen(token: String): EventTime? {
        val (head, zone) = splitZone(token) ?: return null
        val slash = head.indexOf('/')
        val startText = if (slash < 0) head else head.take(slash)
        val tailText = if (slash < 0) null else head.drop(slash + 1)
        val allDay = !startText.contains('T')

        val start = parseLocal(startText) ?: return null
        val end: LocalDateTime = when {
            tailText == null ->
                if (allDay) start.plusDays(1) else start        // a day, or a moment
            tailText.startsWith("P") ->
                runCatching { start.plus(Duration.parse(tailText)) }.getOrNull() ?: return null
            else -> {
                val parsed = parseLocal(tailText) ?: return null
                // Inclusive last day in the text, exclusive end in the model.
                if (allDay) parsed.plusDays(1) else parsed
            }
        }
        if (end.isBefore(start)) return null
        return EventTime(start = start, end = end, zone = zone, allDay = allDay)
    }

    /** Peels a trailing `[Zone/Id]`, returning the rest and the zone. Null zone when absent. */
    private fun splitZone(token: String): Pair<String, ZoneId?>? {
        val open = token.indexOf('[')
        if (open < 0) return token to null
        val close = token.indexOf(']', open)
        if (close < 0) return null
        val zone = runCatching { ZoneId.of(token.substring(open + 1, close)) }.getOrNull() ?: return null
        return (token.take(open) + token.drop(close + 1)) to zone
    }

    /** `2026-09-11` (midnight) or `2026-09-11T14:00`. */
    private fun parseLocal(s: String): LocalDateTime? =
        if (s.contains('T')) runCatching { LocalDateTime.parse(s) }.getOrNull()
        else parseDate(s)?.atStartOfDay()

    /**
     * `2026-08-26`, `2026-08-26T09:00:00Z`, either optionally carrying a length and a reminder:
     * `due:2026-08-26T09:00:00Z/PT1H+r15`.
     *
     * The `/PT1H` tail is the same ISO interval the event when-slot uses, deliberately — a task
     * blocked out from nine to ten and a meeting from nine to ten are the same shape on a timeline,
     * and two spellings for one idea is one more than anybody should have to learn.
     *
     * A length on an all-day task is refused rather than kept: "all of Tuesday, for one hour" does
     * not mean anything, and storing it would leave the timeline to decide what it meant.
     */
    private fun parseDue(token: String): DueSpec? {
        val at = token.indexOf("+r")
        val head = if (at >= 0) token.take(at) else token
        val reminder = if (at >= 0) token.drop(at + 2).toIntOrNull() else null
        if (at >= 0 && reminder == null) return null

        val slash = head.indexOf('/')
        val body = if (slash < 0) head else head.take(slash)
        val duration = if (slash < 0) null else
            runCatching { Duration.parse(head.drop(slash + 1)) }.getOrNull() ?: return null

        val value = if (body.contains('T')) {
            runCatching { Instant.parse(body) }.getOrNull()?.let { DueValue.At(it) }
        } else {
            parseDate(body)?.let { DueValue.AllDay(it) }
        }
        if (value is DueValue.AllDay && duration != null) return null
        return value?.let { DueSpec(it, reminder, duration) }
    }

    private fun parseDate(s: String): LocalDate? =
        try { LocalDate.parse(s) } catch (_: DateTimeParseException) { null }

    // ---- encode ----

    /**
     * One block as the line it would occupy in a page — for the archive, which is a list of lines
     * rather than a document. Round-trips through [decodeBlock].
     */
    fun encodeBlock(block: Block): String =
        (if (rawStillDescribes(block, 0)) block.raw!! else render(block, 0)).ifEmpty { " " }

    /** The inverse. An archive line is an ordinary block line and is parsed as one. */
    fun decodeBlock(line: String): Block = parseBlock(line)

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
        is EventRef -> b.copy(raw = null)
    }

    /** Prose and headings breathe; consecutive list items do not. */
    private fun needsBlankBefore(prev: Block, next: Block): Boolean {
        val listish = { b: Block -> b is TaskRef || b is Bullet || b is Numbered || b is EventRef }
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
            is EventRef -> renderEvent(block)
        }
    }

    private fun renderTask(t: TaskRef): String = buildString {
        // No trailing space after the box, and none between an empty title and the first token.
        // A line that ends in whitespace is a line something else will eventually trim, and the
        // parser must not be the only thing standing between that and a block changing kind.
        append(
            when (t.status) {
                TaskStatus.OPEN -> "- [ ]"
                TaskStatus.DONE -> "- [x]"
                TaskStatus.IN_PROGRESS -> "- [~]"
            }
        )
        if (t.title.isNotEmpty()) append(' ').append(t.title)
        // Order is fixed so the same task always renders the same bytes — otherwise two devices
        // holding identical data would produce a diff, and every sync would look like a change.
        if (t.id.isNotEmpty()) append(" ^").append(t.id)
        t.due?.let { append(" due:").append(renderDue(it)) }
        t.deadline?.let { append(" deadline:").append(it) }
        // Only ever written on a finished task, so an open one carries no dead token — and
        // un-finishing clears it, so a task cannot claim to have been completed on a day it was not.
        t.doneAt?.takeIf { t.status == TaskStatus.DONE }?.let { append(" done:").append(it) }
        t.priority?.let { append(" !").append(it) }
        t.labels.forEach { append(" #").append(it) }
        t.assignee?.let { append(" @").append(it) }
    }

    private fun renderEvent(e: EventRef): String = buildString {
        append(EVENT_MARKER.trimEnd())
        append(' ').append(renderWhen(e.time))
        if (e.title.isNotEmpty()) append(' ').append(e.title)
        // Fixed order, for the reason renderTask gives: two devices holding the same event must
        // produce the same bytes, or every sync looks like a change.
        if (e.id.isNotEmpty()) append(" ^").append(e.id)
        e.rrule?.let { append(" rrule:").append(it) }
        e.forTaskId?.let { append(" for:").append(it) }
        e.series?.let { s ->
            append(" series:").append(s.id)
            // The bare form means "the occurrence at this line's own start", so an override that
            // moved somewhere else has to say which occurrence it replaces, and a cancellation
            // sitting on its original start does not.
            s.originalStart?.takeIf { it != e.time.start }?.let { append('@').append(renderLocal(it)) }
        }
        if (e.cancelled) append(" cancelled")
        e.location?.let { append(" loc:").append(it) }
        e.color?.let { append(" col:").append(it) }
        e.reminderMin?.let { append(" remind:").append(it) }
        e.priority?.let { append(" !").append(it) }
        e.labels.forEach { append(" #").append(it) }
        e.attendees.forEach { append(" @").append(it) }
    }

    /**
     * The inverse of [parseWhen], preferring a duration over an explicit end.
     *
     * A meeting that moves keeps its length, and a diff shows one changed field instead of two.
     * Somebody who wrote an explicit end by hand keeps it regardless: [rawStillDescribes] re-parses
     * their line, gets the same event back, and writes their bytes rather than these.
     */
    private fun renderWhen(t: EventTime): String {
        val zoneSuffix = t.zone?.let { "[$it]" }.orEmpty()
        if (t.allDay) {
            val lastDay = t.end.toLocalDate().minusDays(1)      // exclusive in the model, inclusive in the text
            val head = t.start.toLocalDate().toString()
            return if (lastDay <= t.start.toLocalDate()) head + zoneSuffix
            else "$head$zoneSuffix/$lastDay"
        }
        val head = renderLocal(t.start) + zoneSuffix
        if (t.isInstantaneous) return head
        return "$head/${t.duration.toIsoString()}"
    }

    /** `2026-09-11T14:00`, dropping seconds when they are zero — the common case and less to read. */
    private fun renderLocal(d: LocalDateTime): String =
        if (d.second == 0 && d.nano == 0) d.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString()
        else d.toString()

    /** `PT1H30M`, and `PT0S` never appears because an instantaneous event renders without a tail. */
    private fun Duration.toIsoString(): String = this.toString()

    private fun renderDue(d: DueSpec): String {
        val body = when (val v = d.value) {
            is DueValue.AllDay -> v.date.toString()
            is DueValue.At -> v.instant.toString()
        }
        // Length before reminder, always: the reminder's `+r` is a suffix on the whole thing, and
        // two devices holding the same task must produce the same bytes or every sync is a diff.
        val withLength = if (d.duration == null) body else "$body/${d.duration}"
        return if (d.reminderMin == null) withLength else "$withLength+r${d.reminderMin}"
    }
}
