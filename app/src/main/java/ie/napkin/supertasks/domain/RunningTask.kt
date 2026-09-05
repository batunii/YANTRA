package ie.napkin.supertasks.domain

import ie.napkin.supertasks.data.format.Links
import ie.napkin.supertasks.data.repo.NodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What you have on the go, and which one of them has a clock.
 *
 * Two things used to mean "in progress" and neither knew about the other: [FocusTimer], which holds
 * a single live session and the clock that goes with it, and the task's own `in_progress` flag,
 * which is written to its line in the workspace and synced. Opening the focus screen started a clock
 * and marked no row; the same task could be running in one sense and idle in the other.
 *
 * They are one fact with two halves, and the halves are **not** symmetrical:
 *
 * - **Starting a session marks the task.** You cannot be focusing on something you have not started.
 * - **Marking a task does not start a session.** Saying "I have picked this up" is a claim about
 *   what is on your plate; committing a block of time to it is a separate decision you may not have
 *   made yet. Taking the clock on a swipe would put a session in the ledger nobody asked for.
 *
 * And the two have different arities, which is the whole shape of this class. **Several tasks can be
 * in progress** — that is the ordinary state of a day, and an app that allows only one makes you lie
 * about the rest. **Only one can be timed**, because a clock measures attention and you only have
 * the one. So the bar is a stack: a card per started task, and at most one of them counting.
 */
class RunningTask(
    private val timer: FocusTimer,
    private val nodes: NodeRepository,
    scope: CoroutineScope,
) {
    /**
     * One started task, as the bar draws it.
     *
     * [elapsedSecs] is null for everything except the card whose focus is actually running — the
     * honest reading, and the one that keeps a card from inventing a number. It is null on every
     * card of a device that merely received the flags through sync: the claims travel, the stopwatch
     * does not.
     */
    data class Now(
        val nodeId: String,
        val title: String,
        val elapsedSecs: Int?,
    ) {
        val hasSession: Boolean get() = elapsedSecs != null
    }

    /**
     * The stack, timed card first.
     *
     * The one with the clock leads regardless of when it was started: it is the only card reporting
     * something that changes, and having to swipe to find out how long you have been at it defeats
     * the point of showing it at all. The rest keep the newest-first order the query gave them.
     */
    val now: StateFlow<List<Now>> =
        combine(nodes.inProgress(), timer.state) { started, session ->
            val live = session?.takeIf { !it.isFinished }
            started
                .map { node ->
                    Now(
                        nodeId = node.id,
                        title = Links.plain(node.title.orEmpty()),
                        elapsedSecs = live?.takeIf { it.nodeId == node.id }?.elapsedSecs,
                    )
                }
                .sortedByDescending { it.hasSession }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Which task holds the live clock. Null when things are started but nothing is being timed. */
    val timingId: String? get() = timer.state.value?.takeIf { !it.isFinished }?.nodeId

    /** Picks [nodeId] up. Nothing else is put down — several things can be on the go. */
    suspend fun start(nodeId: String) = nodes.setInProgress(nodeId, true)

    /** What a play attempt met. */
    sealed interface Play {
        /** The clock is now on this task. */
        data object Started : Play

        /**
         * Another task has it.
         *
         * The one exclusivity left in the app. Several tasks can be on the go — that is what the
         * player swipes through — but a session measures attention and there is one of that. Taking
         * the clock closes the other session as interrupted, in a ledger someone will read later, so
         * the person says when rather than the button.
         */
        data class Occupied(val byId: String, val byTitle: String) : Play
    }

    /**
     * Presses play: an open stopwatch on [nodeId].
     *
     * Open, not committed. The player's button is a control on a bar you were passing anyway — it
     * means "start counting", which promises nothing about how long. Committing to a length is a
     * decision with its own screen, and tapping the body of the player is how you get there.
     */
    suspend fun startTiming(nodeId: String, title: String): Play {
        val busy = timingId
        if (busy != null && busy != nodeId) {
            return Play.Occupied(busy, Links.plain(nodes.byId(busy)?.title.orEmpty()))
        }
        timer.startOpen(nodeId, Links.plain(title))
        return Play.Started
    }

    /** Takes the clock. The previous session closes as interrupted; its time still counts. */
    fun switchTimingTo(nodeId: String, title: String) = timer.startOpen(nodeId, Links.plain(title))

    /**
     * Ends the session but leaves the task started.
     *
     * Finishing a focus is not the same as putting the task down — you stopped timing, and you are
     * usually still on the thing. Clearing the mark here would make the card vanish the moment a
     * pomodoro ran out, which is the opposite of what just happened.
     */
    fun stopTiming() {
        if (timingId != null) timer.finish()
    }

    /**
     * Puts [nodeId] down: it leaves the stack, and any clock on it stops.
     *
     * Both, always. A session left running on a task you have said you are done with is the exact
     * disagreement this class exists to prevent.
     */
    suspend fun stop(nodeId: String) {
        if (timingId == nodeId) timer.finish()
        nodes.setInProgress(nodeId, false)
    }
}

/**
 * A play press, and the consent it sometimes needs. One per screen that shows the player.
 *
 * The button cannot ask a question while it is being pressed, so it reports what it met and this
 * holds that until the screen has drawn the offer. Shared rather than reinvented per screen, because
 * taking the clock off something should look and cost the same wherever you did it from.
 */
class TimingRequest(private val running: RunningTask) {
    private val _occupied = MutableStateFlow<RunningTask.Play.Occupied?>(null)

    /** Non-null while the screen owes the person a `SWITCH HERE`. */
    val occupied: StateFlow<RunningTask.Play.Occupied?> = _occupied

    private var pending: Pair<String, String>? = null

    /** The player's one button: start the clock, or stop it. */
    suspend fun toggle(nodeId: String, title: String) {
        if (running.timingId == nodeId) {
            running.stopTiming()
            return
        }
        when (val attempt = running.startTiming(nodeId, title)) {
            RunningTask.Play.Started -> Unit
            is RunningTask.Play.Occupied -> {
                pending = nodeId to title
                _occupied.value = attempt
            }
        }
    }

    fun confirm() {
        val (id, title) = pending ?: return
        running.switchTimingTo(id, title)
        dismiss()
    }

    fun dismiss() {
        pending = null
        _occupied.value = null
    }
}
