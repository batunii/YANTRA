package ie.shoonya.yantra.domain

import ie.shoonya.yantra.data.format.Links
import ie.shoonya.yantra.data.db.SittingSpan
import ie.shoonya.yantra.data.repo.NodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    /**
     * Time already set aside for a task — CALENDAR_PLAN.md §13.
     *
     * A third source, combined here rather than folded into `inProgress()`, and the separation is
     * deliberate: a sitting arriving is a claim about the *plan*, not about you. Writing `- [~]` at
     * the stroke of two would mark a task picked up that you spent the hour in a meeting instead —
     * in a file that syncs and commits. So the bar carries the readiness and the file carries the
     * fact, and the fact is written when you press play.
     */
    sittings: Flow<List<SittingSpan>> = flowOf(emptyList()),
    /**
     * Task id to the palette name of the list it lives on — what the player's spine carries.
     *
     * Defaulted empty so every test of the ordering, which is what this class is really about,
     * carries nothing about colour.
     */
    colours: Flow<Map<String, String?>> = flowOf(emptyMap()),
    private val clock: () -> Long = System::currentTimeMillis,
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
        /** Its sitting is happening right now. Ready, whether or not anybody has picked it up. */
        val scheduled: Boolean = false,
        /**
         * The colour of the list this task lives on, as a palette name — the colour law, as remade.
         *
         * What the player's spine carries. The bar shows a title, and a title alone does not say
         * whether "Draft the deck" is work or the side project; the colour does, and it is the same
         * thing a spine already means on a timeline block and an event line. Null where the list
         * wears no colour.
         */
        val colour: String? = null,
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
        combine(nodes.inProgress(), timer.state, sittings, minutes(), colours) {
            started, session, spans, at, hues ->
            stack(
                started = started.map { it.id to Links.plain(it.title.orEmpty()) },
                timing = session?.takeIf { !it.isFinished }?.let { it.nodeId to it.elapsedSecs },
                sittings = spans,
                at = at,
                colours = hues,
            )
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Ticks on the minute, so a sitting that has arrived is noticed without anybody opening a screen. */
    private fun minutes(): Flow<Long> = flow {
        while (true) {
            val at = clock()
            emit(at)
            // To the next minute rather than every sixty seconds from whenever this started, so the
            // bar changes as the clock does rather than up to a minute after it.
            delay(60_000L - at % 60_000L)
        }
    }

    companion object {
        /**
         * The bar, in order — pure, because the ordering is the part that is easy to get wrong and
         * hard to see going wrong.
         *
         * Two sources, and neither is a subset of the other. A task you have picked up is on the go
         * whatever the calendar says; a task whose sitting is happening now is **ready** even though
         * nothing has been written about it anywhere. Both belong on the bar, once each.
         *
         * The order is a claim about what deserves the front card:
         *
         * 1. **The timed one**, always. It is the only card reporting something that changes, and
         *    having to swipe to find out how long you have been at it defeats showing it at all.
         * 2. **Then whatever is scheduled now.** Newest-first is a reasonable default with nothing
         *    better to go on; a sitting is something better to go on — it is you, earlier, saying
         *    this is the hour for this.
         * 3. **Then the rest**, in the order they came, which is newest first.
         */
        fun stack(
            started: List<Pair<String, String>>,
            timing: Pair<String, Int>?,
            sittings: List<SittingSpan>,
            at: Long,
            /** Task id to the palette name of the list it lives on. Absent means no colour. */
            colours: Map<String, String?> = emptyMap(),
        ): List<Now> {
            val nowOn = sittings.filter { it.covers(at) }
            val scheduledIds = nowOn.mapTo(HashSet()) { it.taskId }
            val startedIds = started.mapTo(HashSet()) { it.first }
            val cards = started.map { (id, title) ->
                Now(
                    nodeId = id,
                    title = title,
                    elapsedSecs = timing?.takeIf { it.first == id }?.second,
                    scheduled = id in scheduledIds,
                    colour = colours[id],
                )
            } + nowOn
                // Two sittings for the same task in one hour is one card, not two.
                .distinctBy { it.taskId }
                .filter { it.taskId !in startedIds }
                .map {
                    Now(
                        it.taskId, Links.plain(it.title.orEmpty()),
                        elapsedSecs = null, scheduled = true, colour = colours[it.taskId],
                    )
                }
            // Stable, so within each rank the order the sources gave is kept.
            return cards.sortedWith(
                compareByDescending<Now> { it.hasSession }.thenByDescending { it.scheduled }
            )
        }
    }

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
