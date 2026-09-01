package ie.napkin.supertasks.data.capture

import ie.napkin.supertasks.data.format.Links
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * What a line of typing turns out to have meant.
 *
 * [title] is the text with every recognised token removed; [spans] are where those tokens were in
 * the *original* string, so the field can tint them while they are still on screen. Nothing is
 * consumed silently — that is the whole safety story here, and the reason this returns positions and
 * not just values.
 */
data class Captured(
    val title: String,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val labels: List<String> = emptyList(),
    val priority: String? = null,
    /**
     * The GitHub login this should be assigned to, if the line named one the workspace allows.
     *
     * Matched against who can actually be assigned rather than taken as whatever followed the `@`,
     * for the same reason the assignee sheet is a closed list: a task handed to somebody outside
     * the repository is a task nobody will ever be shown. A name that matches nobody is left in the
     * title, where it reads as the ordinary English it probably was.
     */
    val assignee: String? = null,
    /**
     * Which list this should go to, if the line named one.
     *
     * Unlike everything else here, this is **not** written to the file. A task's list is the page it
     * sits on, so naming one is a routing instruction that is spent at the moment of capture — there
     * is nowhere on the line for it to live afterwards, and nothing would read it if there were.
     */
    val list: String? = null,
    /**
     * True when [list] names something this workspace does not have yet, so capture has to make it.
     *
     * Kept apart from [list] rather than folded into it because the two are answered by different
     * people: the parser knows what was typed, and only the caller can decide whether creating a
     * list is a thing it is allowed to do.
     */
    val listIsNew: Boolean = false,
    val spans: List<Span> = emptyList(),
) {
    /** A recognised run of characters in the input, and what it turned into. */
    data class Span(val range: IntRange, val kind: Kind)

    enum class Kind { DATE, TIME, LABEL, PRIORITY, LIST, ASSIGNEE, LINK }

    val hasAnything: Boolean
        get() = date != null || time != null || labels.isNotEmpty() || priority != null ||
            list != null || assignee != null || spans.any { it.kind == Kind.LINK }

    /** Due as a single moment when a time was given, otherwise just the day. */
    fun dueAt(): LocalDateTime? = date?.let { LocalDateTime.of(it, time ?: LocalTime.MIDNIGHT) }
}

/**
 * Reading dates, times, labels and priority out of ordinary typing.
 *
 * "buy milk tomorrow 6pm #home !high" is one line and four decisions, and the alternative is a task
 * plus four taps. Capture is the loop the app exists for, so the fastest path into it should be the
 * one that needs no screens at all.
 *
 * **Deterministic on purpose.** No model, no service, no guessing: a fixed grammar that either
 * matches or does not. It works offline and identically forever, it cannot invent a date, and when
 * it does nothing the text is simply the title — which is the correct answer far more often than
 * any clever reading would be.
 *
 * Two rules keep it from being annoying:
 *
 * **Nothing is consumed silently.** Every match is reported with its position so the field can tint
 * it as you type. A word that quietly vanished from a task title would be far worse than one that
 * was never recognised.
 *
 * **A token is never the whole title.** "Today" as a task is a task called Today, not an empty task
 * due today. If stripping everything would leave nothing, nothing is stripped.
 *
 * `#label` and `!priority` are deliberately the same syntax the file format already uses for those
 * fields, so what you type is what the file says.
 */
object CaptureParse {

    /** Priorities the app knows. Matching is case-insensitive; the stored value is canonical. */
    private val PRIORITIES = listOf("High", "Medium", "Low")

    private val LABEL = Regex("""(?<=^|\s)#([\p{L}\p{N}_-]{1,40})""")

    /**
     * `~ Groceries` — where it goes.
     *
     * Not `@`, which the file format already spends on the assignee, and that is the field shared
     * workspaces were built for. `~` because a task title is plain text — it renders no markdown,
     * deliberately — so the character is not spoken for by anything else a title can mean.
     */
    const val LIST_MARK = '~'
    private val PRIORITY = Regex("""(?<=^|\s)!([\p{L}]{1,10})""", RegexOption.IGNORE_CASE)

    /** `6pm`, `6:30pm`, `18:30`, `9 am`. Bare `18` is not a time — it is far more often a number. */
    private val TIME = Regex(
        """(?<=^|\s)(\d{1,2})(?::(\d{2}))?\s?(am|pm)(?=$|\s)|(?<=^|\s)(\d{1,2}):(\d{2})(?=$|\s)""",
        RegexOption.IGNORE_CASE,
    )

    private val ISO_DATE = Regex("""(?<=^|\s)(\d{4})-(\d{2})-(\d{2})(?=$|\s)""")

    /** `26/8`, `26/08/2026`, `26.8`. Day first, which is what most of the world writes. */
    private val NUMERIC_DATE = Regex("""(?<=^|\s)(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2,4}))?(?=$|\s)""")

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    /** `26 aug`, `aug 26`, `26 august`. */
    /** `26 aug`, `1st sept` — the ordinal suffix is optional and ignored. */
    private val DAY_MONTH =
        Regex("""(?<=^|\s)(\d{1,2})(?:st|nd|rd|th)?\s+([a-z]{3,9})(?=$|\s)""", RegexOption.IGNORE_CASE)
    private val MONTH_DAY =
        Regex("""(?<=^|\s)([a-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?(?=$|\s)""", RegexOption.IGNORE_CASE)

    private val NEXT_WEEKDAY = Regex("""(?<=^|\s)next\s+([a-z]{3,9})(?=$|\s)""", RegexOption.IGNORE_CASE)
    private val WEEKDAY = Regex("""(?<=^|\s)([a-z]{3,9})(?=$|\s)""", RegexOption.IGNORE_CASE)

    /**
     * `@batunii` — who it is for. The same character the file format writes an assignee with, so
     * what you type is what the line says, exactly as `#label` and `!priority` already are.
     *
     * Bounded by GitHub's own rule for logins: letters, digits and hyphens, up to 39 characters.
     * That is what stops `email me @ 5` and `meet @ the office` from being read as names.
     */
    private val ASSIGNEE = Regex("""(?<=^|\s)@([A-Za-z0-9][A-Za-z0-9-]{0,38})(?=$|\s)""")

    /**
     * `[[Call Bob]]` — a link to another task, written by name.
     *
     * Deliberately does not match a `|`, which is what a *finished* link contains: `[[Bob|^9f1e…]]`
     * is already resolved and must pass through untouched rather than being resolved a second time
     * against whatever it now reads as.
     */
    private val LINK_DRAFT = Regex("""\[\[([^\[\]|\n]+)]]""")

    /**
     * The names inside every unresolved `[[…]]` on this line.
     *
     * Separate from [parse] because resolving a name means a database query and this file is pure:
     * the caller looks the names up and hands the answers back through [parse]'s `links`. Keeping
     * the grammar and the lookup apart is also what lets the grammar be tested without a database.
     */
    fun linkNames(input: String): List<String> =
        LINK_DRAFT.findAll(input).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /**
     * [lists] are the names this workspace actually has. A list is matched against them rather than
     * taken as whatever follows the mark, for two reasons: names contain spaces, so there is no way
     * to know where one ends without knowing what exists; and a typo must leave the text alone rather
     * than conjure a new list, which is the one mistake here that would be tedious to undo.
     */
    fun parse(
        input: String,
        today: LocalDate = LocalDate.now(),
        lists: List<String> = emptyList(),
        /**
         * The logins that may be assigned here — see [Captured.assignee]. A name outside this set
         * is not an assignee and is left in the title.
         */
        people: List<String> = emptyList(),
        /**
         * Typed name (lowercased) to node id, for turning `[[Call Bob]]` into a real link. Supplied
         * by the caller because the lookup is a query; see [linkNames].
         */
        links: Map<String, String> = emptyMap(),
    ): Captured {
        val spans = ArrayList<Captured.Span>()
        // Only links rewrite rather than vanish, and only they need an entry here. Everything else
        // is a modifier that moves off the line into a property, so its span is simply removed.
        val rewrites = HashMap<IntRange, String>()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var priority: String? = null
        var assignee: String? = null
        val labels = ArrayList<String>()

        // Before anything else, because a resolved link puts brackets and an id into the title and
        // every other pattern here would then be reading characters nobody typed.
        LINK_DRAFT.findAll(input).forEach { m ->
            val id = links[m.groupValues[1].trim().lowercase()] ?: return@forEach
            spans += Captured.Span(m.range, Captured.Kind.LINK)
            rewrites[m.range] = Links.encode(m.groupValues[1].trim(), id)
        }

        ASSIGNEE.findAll(input).forEach { m ->
            if (assignee != null) return@forEach
            val login = people.firstOrNull { it.equals(m.groupValues[1], ignoreCase = true) }
                ?: return@forEach
            assignee = login
            spans += Captured.Span(m.range, Captured.Kind.ASSIGNEE)
        }

        var list: String? = null
        var listIsNew = false
        matchKnownList(input, lists)?.let { (name, at) ->
            list = name
            spans += Captured.Span(at, Captured.Kind.LIST)
        }

        LABEL.findAll(input).forEach { m ->
            labels += m.groupValues[1]
            spans += Captured.Span(m.range, Captured.Kind.LABEL)
        }

        PRIORITY.firstMeaning(input, { true }) { m ->
            PRIORITIES.firstOrNull { it.equals(m.groupValues[1], ignoreCase = true) }
                ?: shorthandPriority(m.groupValues[1])
        }?.let { (named, range) ->
            priority = named
            spans += Captured.Span(range, Captured.Kind.PRIORITY)
        }

        TIME.firstMeaning(input, { true }) { parseTime(it) }?.let { (t, range) ->
            time = t
            spans += Captured.Span(range, Captured.Kind.TIME)
        }

        val dateMatch = findDate(input, today, spans.map { it.range })
        if (dateMatch != null) {
            date = dateMatch.first
            spans += Captured.Span(dateMatch.second, Captured.Kind.DATE)
        }

        // A time with no day means the next time it is that o'clock.
        if (date == null && time != null) {
            date = if (time!! > LocalTime.now()) today else today.plusDays(1)
        }

        // Only now, once the other tokens have claimed their characters. A list that does not exist
        // yet has no known name to match, so its extent has to be read off the line — and the one
        // honest way to bound it is "the rest, minus whatever else was understood". Doing this
        // before the date pass would have made `~ Errands tomorrow` a list called "Errands
        // tomorrow"; doing it after leaves the date a date.
        if (list == null) {
            newListAt(input, spans.map { it.range })?.let { (name, at) ->
                list = name
                listIsNew = true
                spans += Captured.Span(at, Captured.Kind.LIST)
            }
        }

        val title = strip(input, spans, rewrites)
        // Everything was a modifier and nothing was a task. Then it was never a modifier.
        if (title.isBlank()) return Captured(title = input.trim())

        return Captured(
            title, date, time, labels, priority, assignee, list, listIsNew,
            spans.sortedBy { it.range.first },
        )
    }

    /**
     * A name with its spacing and case thrown away, which is how two names are compared here.
     *
     * "Work trips", "work trips" and "worktrips" are one list as far as anyone typing is concerned.
     * Requiring the spaces back would make the shortcut useless exactly where it is most wanted —
     * mid-sentence, one-handed, on a phone keyboard that capitalises whatever it likes.
     */
    private fun normalise(name: String): String =
        name.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }

    /**
     * The longest known list this line names, and where it sits — mark included.
     *
     * Longest wins so "Work" cannot claim a line that named "Work trips". Beyond the match the next
     * character has to be a space or the end of the line, or "Work" would claim "Workshop".
     */
    private fun matchKnownList(input: String, lists: List<String>): Pair<String, IntRange>? {
        val known = lists.filter { it.isNotBlank() }
            .map { it to normalise(it) }
            .sortedByDescending { it.second.length }
        if (known.isEmpty()) return null

        eachMark(input) { mark, from ->
            known.forEach { (name, norm) ->
                val end = matchFrom(input, from, norm)
                // A closing mark belongs to the token, not to the title after it.
                if (end != null) {
                    val closed = end < input.length && input[end] == LIST_MARK
                    return name to mark..(if (closed) end else end - 1)
                }
            }
        }
        return null
    }

    /**
     * How far [norm] reaches into [input] from [from], or null if it does not match.
     *
     * Whitespace in the input is skipped rather than compared, which is what makes `~worktrips` and
     * `~Work trips` the same instruction.
     */
    private fun matchFrom(input: String, from: Int, norm: String): Int? {
        var p = from
        var k = 0
        while (p < input.length && k < norm.length) {
            val ch = input[p]
            if (ch.isWhitespace()) { p++; continue }
            if (ch.lowercaseChar() != norm[k]) return null
            p++; k++
        }
        if (k != norm.length) return null
        // End of line, a space, or the closing mark — anything else means the name was longer than
        // this one and matching it here would claim half a word.
        return if (p == input.length || input[p].isWhitespace() || input[p] == LIST_MARK) p else null
    }

    /**
     * A list this workspace does not have yet: the mark, and everything after it that nothing else
     * claimed.
     *
     * This reverses an earlier rule. An unrecognised name used to be left in the title on the
     * grounds that a typo must not conjure a list — which was right about the risk and wrong about
     * the remedy, because it also meant the only way to file something into a new list was to go and
     * make the list first, which is precisely the interruption capture exists to avoid. The remedy
     * is that nothing here is silent: the name is tinted while you type it, and the field offers the
     * lists you already have before you commit to a new one.
     *
     * Two things are deliberately not lists. A mark has to follow a space, so `foo~bar` is a word.
     * And the name has to begin with a letter, so `~5 mins` stays the ordinary way of writing
     * "about five minutes" rather than becoming a list called "5 mins".
     */
    private fun newListAt(input: String, claimed: List<IntRange>): Pair<String, IntRange>? {
        eachMark(input) { mark, from ->
            // The rest of the line, stopping where another token has already been understood.
            var end = input.length
            claimed.forEach { r -> if (r.first >= from && r.first < end) end = r.first }

            // Or, before any of that, at a closing mark. Without one the name has no end but the
            // end of the line, so a new list can only ever be the last thing said — which is fine
            // until you want to say anything after it.
            val close = input.indexOf(LIST_MARK, from).takeIf { it in from until end }
            val name = input.substring(from, close ?: end).trimEnd()
            if (name.isEmpty() || !name[0].isLetter()) return@eachMark

            // No leading whitespace to worry about — eachMark already stepped past it — so the span
            // is the mark plus exactly the name, plus the closing mark when there is one.
            val last = if (close != null) close else from + name.length - 1
            return name to mark..last
        }
        return null
    }

    /**
     * Every list mark in [input], as (mark, first character after it).
     *
     * A mark counts only at the start of the line or after a space — inside a word a tilde is a
     * tilde. Any run of spaces after it is skipped, so `~Groceries` and `~ Groceries` are one thing.
     */
    private inline fun eachMark(input: String, body: (mark: Int, from: Int) -> Unit) {
        var at = 0
        while (true) {
            val mark = input.indexOf(LIST_MARK, at)
            if (mark < 0) return
            at = mark + 1
            if (mark > 0 && !input[mark - 1].isWhitespace()) continue
            var from = mark + 1
            while (from < input.length && input[from].isWhitespace()) from++
            if (from < input.length) body(mark, from)
        }
    }

    /**
     * The `~…` currently being typed, for a field that wants to offer the lists that match it.
     *
     * The last mark on the line rather than the one under the caret: a plain text field does not
     * report a caret, and the token being typed is the last one in every case that matters. Returns
     * the span to replace and the partial name, which may be empty when the mark was just typed —
     * that is when showing every list is most useful.
     */
    fun listDraft(input: String): Pair<IntRange, String>? {
        var found: Pair<IntRange, String>? = null
        var at = 0
        while (true) {
            val mark = input.indexOf(LIST_MARK, at)
            if (mark < 0) return found
            at = mark + 1
            if (mark > 0 && !input[mark - 1].isWhitespace()) continue
            val rest = input.substring(mark + 1)
            // A closed name is settled. Going on offering alternatives for it would be offering to
            // undo a decision the user has already made and moved past.
            found = if (rest.contains(LIST_MARK)) null
            else (mark..input.lastIndex) to rest.trimStart()
        }
    }

    /**
     * The `@…` currently being typed, for a field that wants to offer the people it could mean.
     *
     * Unlike [listDraft] this needs the caret, and can use it: a login has no spaces, so the token
     * ends at the next one and there is no ambiguity about where the name stops. The draft is only
     * live while the caret is inside it — once you have typed past the name, the decision is made.
     */
    fun assigneeDraft(input: String, caret: Int): Pair<IntRange, String>? {
        val at = caret.coerceIn(0, input.length)
        val mark = input.lastIndexOf('@', (at - 1).coerceAtLeast(0))
        if (mark < 0 || mark >= at) return null
        if (mark > 0 && !input[mark - 1].isWhitespace()) return null
        val typed = input.substring(mark + 1, at)
        if (typed.any { it.isWhitespace() }) return null
        return (mark until at) to typed
    }

    /**
     * The people worth offering for a partial login, best first — the same shape as
     * [listSuggestions] and for the same reason: what the strip offers and what the line resolves
     * to must be decided by one rule.
     */
    fun peopleSuggestions(draft: String, people: List<String>): List<String> {
        val d = draft.lowercase(Locale.ROOT)
        if (d.isEmpty()) return people
        val starts = people.filter { it.lowercase(Locale.ROOT).startsWith(d) }
        return starts + people.filter { it.lowercase(Locale.ROOT).contains(d) && it !in starts }
    }

    /**
     * The lists worth offering for a partial name, best first, or every list when nothing is typed.
     *
     * Matched on the same [normalise] the parser uses, so what the field offers and what the line
     * resolves to cannot disagree. A name that starts with what was typed comes before one that
     * merely contains it — "Work" before "Homework" for "wor".
     */
    fun listSuggestions(draft: String, lists: List<String>): List<String> {
        val d = normalise(draft)
        if (d.isEmpty()) return lists
        val starts = lists.filter { normalise(it).startsWith(d) }
        val contains = lists.filter { normalise(it).contains(d) && it !in starts }
        return starts + contains
    }

    private fun shorthandPriority(word: String): String? = when (word.lowercase()) {
        "h", "hi", "urgent" -> "High"
        "m", "med" -> "Medium"
        "l", "lo" -> "Low"
        else -> null
    }

    private fun parseTime(m: MatchResult): LocalTime? {
        val g = m.groupValues
        return runCatching {
            if (g[3].isNotEmpty()) {
                // 12-hour. 12am is midnight and 12pm is noon, which the obvious arithmetic gets wrong.
                val raw = g[1].toInt()
                if (raw !in 1..12) return null
                val hour = when {
                    g[3].equals("am", true) -> if (raw == 12) 0 else raw
                    else -> if (raw == 12) 12 else raw + 12
                }
                LocalTime.of(hour, g[2].ifEmpty { "0" }.toInt())
            } else {
                LocalTime.of(g[4].toInt(), g[5].toInt())
            }
        }.getOrNull()
    }

    /**
     * Every match of [this], not merely the first, until one of them means something.
     *
     * The bug this exists to prevent was subtle and common: each pattern used `find`, which returns
     * the first *textual* match whether or not it parses. "buy 6 eggs 1 sept" offered "6 eggs" as its
     * first day-month pair, which is not a date, and the attempt was then abandoned — so the real
     * date two words later was never seen. Any number beside a word, anywhere earlier in the title,
     * silently disabled date parsing for the whole line.
     */
    private fun <T : Any> Regex.firstMeaning(
        input: String,
        free: (IntRange) -> Boolean,
        read: (MatchResult) -> T?,
    ): Pair<T, IntRange>? = findAll(input).firstNotNullOfOrNull { m ->
        if (!free(m.range)) null else read(m)?.let { it to m.range }
    }

    /** The date, and where it was written. Skips anything already claimed by another token. */
    private fun findDate(
        input: String,
        today: LocalDate,
        taken: List<IntRange>,
    ): Pair<LocalDate, IntRange>? {
        val free = { r: IntRange -> taken.none { it.first <= r.last && r.first <= it.last } }

        // Most specific first: an explicit date beats a weekday beats "tomorrow".
        ISO_DATE.firstMeaning(input, free) { m ->
            runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull()
        }?.let { return it }

        NUMERIC_DATE.firstMeaning(input, free) { m ->
            val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            runCatching {
                LocalDate.of(year ?: today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
            }.getOrNull()?.let {
                // No year written means the next time this date comes round, not one in the past.
                if (year == null && it < today) it.plusYears(1) else it
            }
        }?.let { return it }

        DAY_MONTH.firstMeaning(input, free) { m ->
            monthOf(m.groupValues[2])?.let { month ->
                runCatching { LocalDate.of(today.year, month, m.groupValues[1].toInt()) }.getOrNull()
                    ?.let { if (it < today) it.plusYears(1) else it }
            }
        }?.let { return it }

        MONTH_DAY.firstMeaning(input, free) { m ->
            monthOf(m.groupValues[1])?.let { month ->
                runCatching { LocalDate.of(today.year, month, m.groupValues[2].toInt()) }.getOrNull()
                    ?.let { if (it < today) it.plusYears(1) else it }
            }
        }?.let { return it }

        NEXT_WEEKDAY.firstMeaning(input, free) { m ->
            // "next monday" is the one after this coming one, a week further out than "monday" alone.
            weekdayOf(m.groupValues[1])?.let { nextOccurrence(today, it).plusWeeks(1) }
        }?.let { return it }

        WEEKDAY.firstMeaning(input, free) { m ->
            when (m.groupValues[1].lowercase()) {
                "today", "tonight" -> today
                "tomorrow", "tmrw" -> today.plusDays(1)
                else -> weekdayOf(m.groupValues[1])?.let { nextOccurrence(today, it) }
            }
        }?.let { return it }

        return null
    }

    private fun monthOf(word: String): Int? {
        val w = word.lowercase()
        val i = MONTHS.indexOfFirst { it == w || (w.length >= 3 && it.startsWith(w)) }
        return if (i >= 0) i + 1 else null
    }

    private fun weekdayOf(word: String): DayOfWeek? {
        val w = word.lowercase()
        return DayOfWeek.entries.firstOrNull { d ->
            val full = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()
            full == w || (w.length >= 3 && full.startsWith(w))
        }
    }

    /** The next time it is that day, never today — "monday" on a Monday means the one coming. */
    private fun nextOccurrence(today: LocalDate, day: DayOfWeek): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek != day) d = d.plusDays(1)
        return d
    }

    /**
     * The input with every recognised run removed — or, for a link, replaced by what it resolved to
     * — and the leftover whitespace tidied.
     *
     * A link is the one token that stays on the line, because it *is* part of what the task says:
     * "follow up on [[Call Bob]]" is a sentence about Bob, not a sentence with a property attached.
     * Everything else names a field and moves off the title into it.
     */
    private fun strip(
        input: String,
        spans: List<Captured.Span>,
        rewrites: Map<IntRange, String> = emptyMap(),
    ): String {
        if (spans.isEmpty()) return input.trim()
        val sb = StringBuilder(input)
        spans.sortedByDescending { it.range.first }.forEach { span ->
            sb.replace(span.range.first, span.range.last + 1, rewrites[span.range].orEmpty())
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
