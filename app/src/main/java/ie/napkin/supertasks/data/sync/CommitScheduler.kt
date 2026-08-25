package ie.napkin.supertasks.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns a stream of edits into a sensible number of commits.
 *
 * Every write already reaches the file immediately — that is the whole design, and none of this
 * changes it. What this decides is when those files become a *commit*, which is a different
 * question with a different answer: the file has to be right the instant you stop typing, but the
 * history does not have to record every keystroke that got it there.
 *
 * A structural change goes straight out because someone else may be waiting to see it. Everything
 * else waits for you to stop. See [CommitPolicy] for the reasoning; this owns only the timers.
 */
class CommitScheduler(
    private val scope: CoroutineScope,
    private val engine: SyncEngine,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var pending = CommitPolicy.Pending()
    private var timer: Job? = null

    private val _state = MutableStateFlow<SyncResult?>(null)

    /** The last pass, for a UI that wants to say something true about sync. */
    val lastResult: StateFlow<SyncResult?> = _state

    /**
     * Records a change and decides what to do about it.
     *
     * Cheap to call from anywhere, including from inside a write — it never does the commit on the
     * caller's coroutine, so a keystroke is not waiting on git.
     */
    fun record(change: Change) {
        scope.launch {
            mutex.withLock { pending = pending.plus(change, now()) }
            if (change == Change.STRUCTURAL) flush("a task was created, finished or removed")
            else arm()
        }
    }

    /** Commits and syncs whatever is pending, now. The "sync now" button, and app shutdown. */
    fun requestFlush(reason: String) {
        scope.launch { flush(reason) }
    }

    /**
     * Waits out the batch and flushes when the policy says so.
     *
     * Re-armed on every edit rather than left running, so a burst of typing keeps pushing the
     * deadline out instead of committing halfway through a sentence.
     */
    private suspend fun arm() {
        timer?.cancel()
        timer = scope.launch {
            while (true) {
                val (wait, reason) = mutex.withLock {
                    val t = now()
                    CommitPolicy.nextCheckDelay(pending, t) to CommitPolicy.reasonToFlush(pending, t)
                }
                if (reason != null) { flush(reason); return@launch }
                if (wait == null) return@launch
                delay(wait.coerceAtLeast(50))
            }
        }
    }

    private suspend fun flush(reason: String) {
        val had = mutex.withLock {
            val p = pending
            pending = CommitPolicy.Pending()
            timer?.cancel()
            p
        }
        // A structural change with nothing batched behind it still commits: the change itself is
        // already on disk and is exactly what we are here to record.
        _state.value = engine.sync(commitMessage(reason, had))
    }

    /**
     * A commit message that says what it is.
     *
     * The history of a task repo is something a person reads — in a diff on github.com, or working
     * out when a task went missing. "sync" tells them nothing; "12 edits · quiet after 12 edits"
     * tells them what happened and roughly when.
     */
    private fun commitMessage(reason: String, p: CommitPolicy.Pending): String {
        val parts = buildList {
            if (p.edits > 0) add("${p.edits} edit${if (p.edits == 1) "" else "s"}")
            if (p.ink > 0) add("${p.ink} stroke${if (p.ink == 1) "" else "s"}")
        }
        return if (parts.isEmpty()) reason else "${parts.joinToString(" · ")} · $reason"
    }
}
