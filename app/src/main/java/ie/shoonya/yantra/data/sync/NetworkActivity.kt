package ie.shoonya.yantra.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the app is currently saying to the network, so the screen can say it too.
 *
 * **Why this exists.** Everything this app does over the network it does on its own initiative: a
 * structural change commits and pushes at once, a burst of edits waits for you to stop typing, a
 * token renews itself eight hours in, a background pass runs when Android allows it. All of it was
 * invisible. The only network the user could see was the one they pulled down for themselves, which
 * left the honest question "is it doing anything?" with no answer anywhere on screen — and made a
 * slow push indistinguishable from a broken one, and from nothing happening at all.
 *
 * **A count, not a flag.** Two workspaces can push at once and a token can renew in the middle of a
 * sync; a boolean would be cleared by whichever finished first while the other was still running.
 * The label is whatever started most recently, because the alternative — a queue of labels nobody
 * reads — is more honest and less useful.
 *
 * **Nothing here decides anything.** It is a description of work already happening, so a failure to
 * report is a missing line on screen rather than a sync that does not run. That is deliberate: this
 * is the kind of bookkeeping that must never be able to break the thing it is describing.
 */
class NetworkActivity {

    private val lock = Mutex()
    private var depth = 0

    private val _current = MutableStateFlow<String?>(null)

    /** What is happening now, in words for a person, or null when nothing is. */
    val current: StateFlow<String?> = _current.asStateFlow()

    /**
     * Says [what] is happening, until a matching [leave].
     *
     * The paired form exists for one caller: a sync only knows it has reached the network partway
     * through, after it has opened the repository and found a remote, and the body it would have to
     * wrap returns from a dozen places. Everything else should use [during], which cannot be left
     * unbalanced.
     */
    suspend fun enter(what: String) {
        lock.withLock {
            depth++
            _current.value = what
        }
    }

    /** Ends one [enter]. Safe to call more often than entered; the count never goes below zero. */
    suspend fun leave() {
        lock.withLock {
            depth = (depth - 1).coerceAtLeast(0)
            if (depth == 0) _current.value = null
        }
    }

    /**
     * Runs [block], saying [what] while it does.
     *
     * The label is restored rather than cleared when an inner piece of work finishes, so a renewal
     * that happens inside a sync leaves the sync's own description behind it instead of silence.
     *
     * `finally`, always: a throw from [block] is the case where a stuck "Syncing…" would be most
     * misleading, because it is exactly when nothing is happening any more.
     */
    suspend fun <T> during(what: String, block: suspend () -> T): T {
        val previous = lock.withLock {
            val p = _current.value
            depth++
            _current.value = what
            p
        }
        return try {
            block()
        } finally {
            lock.withLock {
                depth--
                _current.value = if (depth <= 0) null else previous ?: what
            }
        }
    }
}

/** The words the screen shows. Kept together so they read like one voice rather than five. */
object NetworkWords {
    const val SYNCING = "Syncing with GitHub"
    const val LINKING = "Connecting to GitHub"
    const val RENEWING = "Renewing access"
    const val CHECKING = "Checking GitHub"
}
