package ie.napkin.supertasks.domain

import ie.napkin.supertasks.data.db.FocusOutcome
import ie.napkin.supertasks.data.repo.PomodoroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped focus timer: survives navigation, one session at a time.
 *
 * **Two instruments, one record.** A *committed* session counts down to a target you set in advance —
 * a promise, whose value is that stopping early is visible. An *open* session counts up until you
 * stop it, promising nothing and simply telling the truth about where the time went. Both write the
 * same session row, which is the durable thing; this class is only one way of filling it in.
 *
 * A session is persisted the moment it starts, which is what lets it survive process death: the
 * in-flight row is on disk, so a reindex cannot forget it and [restoreIfNeeded] can find it again.
 */
class PomodoroTimer(
    private val repo: PomodoroRepository,
    private val scope: CoroutineScope,
) {
    data class State(
        val sessionId: String,
        val nodeId: String,
        val nodeTitle: String,
        /** Zero for an open session: nothing was promised, so there is nothing to count down to. */
        val plannedSecs: Int,
        /** Meaningless while [isOpen]; a countdown has somewhere to arrive and a stopwatch does not. */
        val remainingSecs: Int,
        val elapsedSecs: Int,
        val isRunning: Boolean,
        val isFinished: Boolean = false,
    ) {
        val isOpen: Boolean get() = plannedSecs <= 0
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private var ticker: Job? = null
    private val restoreMutex = Mutex()

    /**
     * Rebuilds a live session after process death, or closes it if its target passed while we were
     * gone. An open session has no target to have passed, so it simply resumes counting from when it
     * started — the elapsed time is real whether or not the app was alive to watch it.
     *
     * Pause state is process-bound: a paused session restores as though it never paused, which
     * overcounts rather than undercounts, and is the right way round for a ledger of effort.
     */
    suspend fun restoreIfNeeded() = restoreMutex.withLock {
        if (_state.value != null) return@withLock
        val open = repo.openSession() ?: return@withLock
        val now = System.currentTimeMillis()
        val elapsed = ((now - open.startedAt) / 1000).toInt()

        if (open.plannedSecs > 0) {
            val endAt = open.startedAt + open.plannedSecs * 1000L
            if (now >= endAt) {
                repo.endSession(open.id, open.plannedSecs, FocusOutcome.RAN_OUT)
                return@withLock
            }
        }
        _state.value = State(
            sessionId = open.id,
            nodeId = open.nodeId,
            nodeTitle = repo.nodeTitle(open.nodeId).orEmpty(),
            plannedSecs = open.plannedSecs,
            remainingSecs = if (open.plannedSecs > 0) open.plannedSecs - elapsed else 0,
            elapsedSecs = elapsed,
            isRunning = true,
        )
        startTicker()
    }

    /**
     * Starts a session on a task. [plannedSecs] of zero opens a stopwatch.
     *
     * Any session still running is closed as interrupted first — its time still counts, because you
     * were still at the desk for it.
     */
    fun start(nodeId: String, nodeTitle: String, plannedSecs: Int) {
        scope.launch {
            _state.value?.let { s ->
                if (!s.isFinished) repo.endSession(s.sessionId, s.elapsedSecs, FocusOutcome.INTERRUPTED)
            }
            ticker?.cancel()
            val sessionId = repo.startSession(nodeId, plannedSecs)
            _state.value = State(
                sessionId = sessionId, nodeId = nodeId, nodeTitle = nodeTitle,
                plannedSecs = plannedSecs,
                remainingSecs = plannedSecs.coerceAtLeast(0),
                elapsedSecs = 0,
                isRunning = true,
            )
            startTicker()
        }
    }

    /** A stopwatch: start now, stop when you stop. */
    fun startOpen(nodeId: String, nodeTitle: String) = start(nodeId, nodeTitle, plannedSecs = 0)

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(1000)
                val s = _state.value ?: break
                if (!s.isRunning || s.isFinished) continue
                val next = s.copy(
                    remainingSecs = if (s.isOpen) 0 else s.remainingSecs - 1,
                    elapsedSecs = s.elapsedSecs + 1,
                )
                // Only a promise can be kept. An open session runs until someone ends it.
                if (!s.isOpen && next.remainingSecs <= 0) {
                    _state.value = next.copy(remainingSecs = 0, isRunning = false, isFinished = true)
                    repo.endSession(s.sessionId, next.elapsedSecs, FocusOutcome.RAN_OUT)
                    break
                }
                _state.value = next
            }
        }
    }

    fun pause() {
        _state.value = _state.value?.copy(isRunning = false)
    }

    fun resume() {
        val s = _state.value ?: return
        if (s.isFinished) return
        _state.value = s.copy(isRunning = true)
    }

    /**
     * Ends the session deliberately.
     *
     * For an open session this is simply how it finishes. For a committed one it is stopping short —
     * recorded as such, and the time still counts, because the ledger measures what you gave rather
     * than whether you obeyed yourself.
     */
    fun finish() = end(FocusOutcome.STOPPED)

    /** Walked away from. Distinguished from [finish] only so the history reads honestly. */
    fun abandon() = end(FocusOutcome.INTERRUPTED)

    private fun end(outcome: String) {
        val s = _state.value ?: return
        ticker?.cancel()
        _state.value = null
        if (!s.isFinished) {
            scope.launch { repo.endSession(s.sessionId, s.elapsedSecs, outcome) }
        }
    }

    /** Clears a finished (already persisted) session from the UI. */
    fun dismissFinished() {
        if (_state.value?.isFinished == true) _state.value = null
    }
}
