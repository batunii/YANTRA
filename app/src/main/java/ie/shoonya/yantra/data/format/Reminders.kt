package ie.shoonya.yantra.data.format

/**
 * The comma-separated reminder offsets, as they are written in a file and in the index.
 *
 * One place rather than a `split` at each call site, because the two ends have to agree exactly:
 * the index is rebuilt from the file, the file is rewritten from the index, and a round trip that
 * loses or reorders an offset is a task that silently ends up with fewer reminders than its owner
 * set. There are three readers and two writers of this string, which is three too many to leave to
 * a one-liner repeated five times.
 */
object Reminders {

    /**
     * Whatever is stored, as offsets — canonical order, no repeats, nothing unreadable.
     *
     * Forgiving on the way in on purpose. This parses a column that older builds never wrote, a
     * migration's backfill, and a file somebody edited by hand; refusing the lot because one
     * element is nonsense would drop reminders that are perfectly readable beside it. The *file*
     * parser is strict for the opposite reason — see PageCodec.parseDue.
     */
    fun parse(stored: String?): List<Int> =
        stored?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.let { DueSpec.reminders(it) }
            ?: emptyList()

    /** The inverse, or null when there are none — the column is nullable and empty is not a value. */
    fun store(offsets: List<Int>): String? =
        DueSpec.reminders(offsets).takeIf { it.isNotEmpty() }?.joinToString(",")
}
