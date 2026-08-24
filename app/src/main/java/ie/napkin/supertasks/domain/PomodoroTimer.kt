package ie.napkin.supertasks.domain

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
 * App-scoped focus timer: survives navigation, one active session at a time.
 * Every started session is persisted immediately; ending it (complete or abandon)
 * writes ended_at / actual_secs / completed onto the same row.
 */
class PomodoroTimer(
    private val repo: PomodoroRepository,
    private val scope: CoroutineScope,
) {
    data class State(
        val sessionId: String,
        val nodeId: String,
        val nodeTitle: String,
        val plannedSecs: Int,
        val remainingSecs: Int,
        val elapsedSecs: Int,
        val isRunning: Boolean,
        val isFinished: Boolean = false,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private var ticker: Job? = null
    private val restoreMutex = Mutex()

    /**
     * Rebuild a live session after process death from its persisted row (started_at +
     * planned_secs), or finalize it as completed if its end passed while we were dead.
     * Pause state is process-bound — a paused session restores as if it never paused.
     * Called concurrently from AppContainer init, widget renders, actions and the finalize
     * worker — the mutex keeps that from double-finalizing or racing the ticker.
     */
    suspend fun restoreIfNeeded() = restoreMutex.withLock {
        if (_state.value != null) return@withLock
        val open = repo.openSession() ?: return@withLock
        val endAt = open.startedAt + open.plannedSecs * 1000L
        val now = System.currentTimeMillis()
        if (now >= endAt) {
            repo.endSession(open.id, open.plannedSecs, completed = true)
            return@withLock
        }
        _state.value = State(
            sessionId = open.id,
            nodeId = open.nodeId,
            nodeTitle = repo.nodeTitle(open.nodeId).orEmpty(),
            plannedSecs = open.plannedSecs,
            remainingSecs = ((endAt - now) / 1000).toInt(),
            elapsedSecs = ((now - open.startedAt) / 1000).toInt(),
            isRunning = true,
        )
        startTicker()
    }

    fun start(nodeId: String, nodeTitle: String, plannedSecs: Int) {
        scope.launch {
            // abandon any session left running
            _state.value?.let { s -> if (!s.isFinished) repo.endSession(s.sessionId, s.elapsedSecs, completed = false) }
            ticker?.cancel()
            val sessionId = repo.startSession(nodeId, plannedSecs)
            _state.value = State(
                sessionId = sessionId, nodeId = nodeId, nodeTitle = nodeTitle,
                plannedSecs = plannedSecs, remainingSecs = plannedSecs, elapsedSecs = 0,
                isRunning = true,
            )
            startTicker()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(1000)
                val s = _state.value ?: break
                if (!s.isRunning || s.isFinished) continue
                val remaining = s.remainingSecs - 1
                val next = s.copy(remainingSecs = remaining, elapsedSecs = s.elapsedSecs + 1)
                if (remaining <= 0) {
                    _state.value = next.copy(remainingSecs = 0, isRunning = false, isFinished = true)
                    repo.endSession(s.sessionId, next.elapsedSecs, completed = true)
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

    /** Finish early, still counted as a completed pomodoro. */
    fun completeEarly() = end(completed = true)

    /** Abandon: recorded, but not counted as completed. */
    fun abandon() = end(completed = false)

    private fun end(completed: Boolean) {
        val s = _state.value ?: return
        ticker?.cancel()
        _state.value = null
        if (!s.isFinished) {
            scope.launch { repo.endSession(s.sessionId, s.elapsedSecs, completed) }
        }
    }

    /** Clears a finished (already persisted) session from the UI. */
    fun dismissFinished() {
        if (_state.value?.isFinished == true) _state.value = null
    }
}
