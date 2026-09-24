package ie.shoonya.yantra.data.workspace

import kotlinx.coroutines.delay

/**
 * Asks again before concluding a row is not there.
 *
 * **Why a retry is the right shape here.** The index is rebuilt inside one transaction, and Room is
 * configured with a pass-through connection — so a read landing in that window can see the tables
 * mid-rebuild rather than either side of it. A lookup that misses therefore means one of two
 * things: the row does not exist, or you asked during the half-second the index was being rewritten
 * underneath you. Those are not the same answer, and treating the second as the first is what made
 * "move to a list" do nothing the first time and work the second.
 *
 * Bounded deliberately. If the row is genuinely gone, the caller has to be told in a tenth of a
 * second rather than hang; the point is to survive a rebuild, not to wait indefinitely for
 * something that is never coming.
 */
internal suspend fun <T : Any> awaitPresent(
    attempts: Int = ATTEMPTS,
    gapMs: Long = GAP_MS,
    pause: suspend (Long) -> Unit = { delay(it) },
    lookup: suspend () -> T?,
): T? {
    repeat(attempts) { i ->
        lookup()?.let { return it }
        if (i < attempts - 1) pause(gapMs)
    }
    return null
}

/** Five tries over ~100ms: longer than a rebuild, shorter than a person notices. */
internal const val ATTEMPTS = 5
internal const val GAP_MS = 25L
