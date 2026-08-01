package ie.napkin.supertasks.data.rank

/**
 * Fractional (LexoRank-style) sibling ordering. Ranks are base-36 strings compared
 * lexicographically; [between] returns a rank strictly between its two bounds without
 * renumbering neighbors, which keeps concurrent reorders conflict-free under sync.
 *
 * Invariant: generated ranks never end in '0', so a valid midpoint always exists.
 */
object Rank {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    private val BASE = ALPHABET.length

    /** Rank for the first item in an empty sibling set. */
    val FIRST: String = between(null, null)

    /**
     * A rank r with a < r < b (lexicographically). null bounds mean -inf / +inf.
     */
    fun between(a: String?, b: String?): String {
        require(a == null || b == null || a < b) { "invalid rank bounds: '$a' !< '$b'" }
        var hi: String? = b
        val sb = StringBuilder()
        var i = 0
        while (sb.length < 128) {
            val dLo = if (a != null && i < a.length) digitOf(a[i]) else 0
            val dHi = when {
                hi == null -> BASE
                i < hi.length -> digitOf(hi[i])
                else -> 0 // hi exhausted: cannot go below; only reachable if hi ends in '0'
            }
            when {
                dHi - dLo > 1 -> {
                    sb.append(ALPHABET[(dLo + dHi) / 2])
                    return sb.toString()
                }
                dHi == dLo -> {
                    sb.append(ALPHABET[dLo])
                    i++
                }
                else -> {
                    // gap of exactly 1: take the low digit; the upper bound is then
                    // satisfied by this position alone, so it becomes +inf below here
                    sb.append(ALPHABET[dLo])
                    hi = null
                    i++
                }
            }
        }
        throw IllegalStateException("rank generation did not converge between '$a' and '$b'")
    }

    /** Rank after all existing siblings ([last] = current max rank, or null if none). */
    fun after(last: String?): String = between(last, null)

    /** Rank before all existing siblings ([first] = current min rank, or null if none). */
    fun before(first: String?): String = between(null, first)

    private fun digitOf(c: Char): Int {
        val d = ALPHABET.indexOf(c)
        require(d >= 0) { "invalid rank char: $c" }
        return d
    }
}
