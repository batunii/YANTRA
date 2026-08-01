package ie.napkin.supertasks.domain

import ie.napkin.supertasks.data.repo.PomodoroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
