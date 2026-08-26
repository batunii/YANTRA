package ie.napkin.supertasks.data.capture

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
     * Which list this should go to, if the line named one.
     *
     * Unlike everything else here, this is **not** written to the file. A task's list is the page it
     * sits on, so naming one is a routing instruction that is spent at the moment of capture — there
     * is nowhere on the line for it to live afterwards, and nothing would read it if there were.
     */
    val list: String? = null,
    val spans: List<Span> = emptyList(),
) {
    /** A recognised run of characters in the input, and what it turned into. */
    data class Span(val range: IntRange, val kind: Kind)

    enum class Kind { DATE, TIME, LABEL, PRIORITY, LIST }

    val hasAnything: Boolean
        get() = date != null || time != null || labels.isNotEmpty() || priority != null || list != null

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
     * `> Groceries` — where it goes.
     *
     * `>` rather than `@`, which the file format already spends on the assignee, and that is the
     * field shared workspaces were built for. A list is a place, and `>` reads as going to one.
     */
    private const val LIST_MARK = '>' 
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
     * [lists] are the names this workspace actually has. A list is matched against them rather than
     * taken as whatever follows the mark, for two reasons: names contain spaces, so there is no way
     * to know where one ends without knowing what exists; and a typo must leave the text alone rather
     * than conjure a new list, which is the one mistake here that would be tedious to undo.
     */
    fun parse(
        input: String,
        today: LocalDate = LocalDate.now(),
        lists: List<String> = emptyList(),
    ): Captured {
        val spans = ArrayList<Captured.Span>()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var priority: String? = null
        val labels = ArrayList<String>()

        var list: String? = null
        // Longest first, so "Work" cannot claim a line that named "Work trips".
        lists.sortedByDescending { it.length }.forEach { name ->
            if (list != null || name.isBlank()) return@forEach
            val at = indexOfList(input, name)
            if (at != null) {
                list = name
                spans += Captured.Span(at, Captured.Kind.LIST)
            }
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

        val title = strip(input, spans)
        // Everything was a modifier and nothing was a task. Then it was never a modifier.
        if (title.isBlank()) return Captured(title = input.trim())

        return Captured(title, date, time, labels, priority, list, spans.sortedBy { it.range.first })
    }

    /** Where `> name` sits in [input], mark included, or null if it is not there. */
    private fun indexOfList(input: String, name: String): IntRange? {
        var from = 0
        while (true) {
            val mark = input.indexOf(LIST_MARK, from)
            if (mark < 0) return null
            var i = mark + 1
            while (i < input.length && input[i] == ' ') i++
            val end = i + name.length
            if (end <= input.length &&
                input.regionMatches(i, name, 0, name.length, ignoreCase = true) &&
                (end == input.length || input[end].isWhitespace())
            ) {
                return mark..(end - 1)
            }
            from = mark + 1
        }
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

    /** The input with every recognised run removed, and the leftover whitespace tidied. */
    private fun strip(input: String, spans: List<Captured.Span>): String {
        if (spans.isEmpty()) return input.trim()
        val sb = StringBuilder(input)
        spans.sortedByDescending { it.range.first }.forEach { sb.delete(it.range.first, it.range.last + 1) }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
