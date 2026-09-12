package ie.shoonya.yantra.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.shoonya.yantra.AppContainer
import ie.shoonya.yantra.data.db.BuiltIns
import ie.shoonya.yantra.data.db.RailTask
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * A month of the calendar — CALENDAR_PLAN.md §6.
 *
 * The window queried is the **grid**, not the month: the six-week grid shows the last days of the
 * previous month and the first of the next, and a dot missing from those cells would make the grid
 * look emptier than the document is.
 */
class CalendarViewModel(private val container: AppContainer) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    /**
     * How many days the multi-day view shows — seven on a tablet, three on a phone.
     *
     * Set by the screen, which is the only thing that knows how wide it is. A seven-column week on a
     * phone gives each day about fifty pixels, which fits a coloured sliver and no words.
     */
    var daysOnScreen: Int = 7
        private set

    fun setDaysOnScreen(count: Int) { daysOnScreen = count }

    /** Month, week or day — the same data, three amounts of detail. */
    private val _mode = MutableStateFlow(CalendarMode.MONTH)
    val mode: StateFlow<CalendarMode> = _mode.asStateFlow()

    fun setMode(m: CalendarMode) { _mode.value = m }

    private val _selected = MutableStateFlow(LocalDate.now())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    fun show(month: YearMonth) {
        _month.value = month
        // Keep the selection inside what is on screen, or the day list below the grid describes a
        // day nobody can see. Same day-of-month where the month has one; its last day otherwise,
        // which is what stops the 31st vanishing on the way into November.
        val day = _selected.value
        if (YearMonth.from(day) != month) {
            _selected.value = month.atDay(minOf(day.dayOfMonth, month.lengthOfMonth()))
        }
    }

    fun select(day: LocalDate) {
        _selected.value = day
        if (YearMonth.from(day) != _month.value) _month.value = YearMonth.from(day)
    }

    fun today() {
        _month.value = YearMonth.now()
        _selected.value = LocalDate.now()
    }

    /**
     * Steps by whatever the current view is a view *of* — a month, a week, or a day.
     *
     * The arrows mean "next one of these", not "next month" regardless of what is on screen, which
     * is what makes them usable in the week view rather than a way to lose your place.
     */
    fun step(forward: Int) {
        when (_mode.value) {
            CalendarMode.MONTH -> show(_month.value.plusMonths(forward.toLong()))
            // Steps by however many days are on screen, so the arrows move you exactly one
            // screenful whether that is a week or three days.
            CalendarMode.WEEK -> select(_selected.value.plusDays((forward * daysOnScreen).toLong()))
            CalendarMode.DAY -> select(_selected.value.plusDays(forward.toLong()))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val days: StateFlow<CalendarDays> = _month
        .flatMapLatest { month ->
            val grid = monthGrid(month.atDay(1))
            val from = grid.first()
            val toExclusive = grid.last().plusDays(1)
            val fromUtc = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val toUtc = toExclusive.atStartOfDay(zone).toInstant().toEpochMilli()

            val events = container.db.eventDao().inRange(fromUtc, toUtc)
            val tasks = dueDefId()
                .flatMapLatest { defId ->
                    if (defId == null) flowOf(emptyList<ie.shoonya.yantra.data.db.DueRow>())
                    else container.db.propertyDao().observeDueInRange(defId, fromUtc, toUtc)
                }

            combine(events, tasks) { e, t ->
                CalendarBucketer.bucket(
                    events = e.map { it.event },
                    // A sitting is drawn as its task, and tapping it should reach the task.
                    sittingOf = e.mapNotNull { row -> row.event.forNodeId?.let { row.event.nodeId to it } }.toMap(),
                    tasks = t,
                    // The title comes down with the row, joined from node — see [EventWithTitle].
                    titles = e.mapNotNull { row -> row.displayTitle?.let { row.event.nodeId to it } }.toMap(),
                    from = from,
                    toExclusive = toExclusive,
                    zone = zone,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The day list under the grid. */
    val selectedItems: StateFlow<List<DayItem>> = combine(days, _selected) { d, day -> d[day].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun dueDefId() = container.db.propertyDao().observeBuiltInDefIdByName(BuiltIns.DUE_NAME)

    private fun deadlineDefId() =
        container.db.propertyDao().observeBuiltInDefIdByName(BuiltIns.DEADLINE_NAME)

    // ---- the rail: tasks waiting for a time — CALENDAR_PLAN.md §13 ----

    /** Which shelf of the rail is showing. */
    private val _shelf = MutableStateFlow(RailBucket.TODAY)
    val shelf: StateFlow<RailBucket> = _shelf.asStateFlow()

    fun setShelf(b: RailBucket) { _shelf.value = b }

    /**
     * The task the rail is holding up, waiting for a time — null when it is holding nothing.
     *
     * Tap-to-arm rather than drag, for the reason §14 names: a drag between two independently
     * scrolling surfaces is the one gesture here that can pass a test and fail a real finger. This
     * one cannot be dropped.
     */
    private val _armed = MutableStateFlow<RailTask?>(null)
    val armed: StateFlow<RailTask?> = _armed.asStateFlow()

    fun arm(task: RailTask?) { _armed.value = task }

    @OptIn(ExperimentalCoroutinesApi::class)
    val rail: StateFlow<Map<RailBucket, List<RailTask>>> =
        combine(dueDefId(), deadlineDefId()) { due, deadline -> due to deadline }
            .flatMapLatest { (due, deadline) ->
                // Both ids are per-install UUIDs and arrive through a Flow, so a fresh install that
                // has not finished seeding shows an empty rail rather than a wrong one.
                if (due == null || deadline == null) flowOf(emptyList())
                else container.db.propertyDao().railTasks(due, deadline)
            }
            // Bucketed against the real today, not the day on screen: the rail answers "what is
            // waiting", which does not change because you paged forward to November.
            .map { tasks -> railShelves(tasks, LocalDate.now(), zone) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Blocks out [length] from [start] for a task — one sitting, no sheet.
     *
     * No naming step, deliberately: a sitting has no title of its own, it draws with the task's, so
     * the objection in §12 to creating blocks silently does not apply. What you dropped is what you
     * meant.
     *
     * It is written on **the task's own page**, right under the task. Two devices reading that file
     * see the plan beside the thing it is a plan for, and a page deleted takes its sittings with it
     * rather than leaving them pointing at nothing.
     */
    fun createSitting(taskId: String, start: java.time.LocalDateTime, length: java.time.Duration) {
        viewModelScope.launch {
            val page = pageOf(taskId) ?: container.nodes.inboxList()
            container.workspaces.writerFor(page).addEvent(
                pageId = page,
                event = ie.shoonya.yantra.data.format.EventRef(
                    id = "",
                    title = "",
                    time = ie.shoonya.yantra.data.format.EventTime(
                        start = start,
                        end = start.plus(length),
                        zone = null,
                        allDay = false,
                    ),
                    forTaskId = taskId,
                    // At the time, not before it. A sitting is not an appointment you have to travel
                    // to — the notification *is* the moment, and it arrives alongside the task
                    // appearing on the bar with its play button.
                    reminderMin = 0,
                ),
                afterId = taskId.takeIf { pageOf(it) == page },
            )
            _armed.value = null
        }
    }

    /** The list a node lives on, climbing past any task it is nested under. */
    private suspend fun pageOf(nodeId: String): String? {
        var at = container.db.nodeDao().byId(nodeId)?.parentId
        var hops = 0
        while (at != null && hops++ < 16) {
            val node = container.db.nodeDao().byId(at) ?: return null
            if (node.type == ie.shoonya.yantra.data.db.NodeType.LIST) return node.id
            at = node.parentId
        }
        return null
    }

    /**
     * Saves an event, creating it on the Inbox when it is new.
     *
     * The Inbox is where this app already puts a thing captured with no home — the same answer
     * quick-add gives — rather than inventing a `calendar/` area the file format has no notion of.
     * An event made from a page belongs to that page; one made from a month belongs nowhere in
     * particular, and "nowhere in particular" already has a name here.
     */
    fun save(existingId: String?, event: ie.shoonya.yantra.data.format.EventRef) {
        viewModelScope.launch {
            if (existingId == null) {
                val page = container.nodes.inboxList()
                container.workspaces.writerFor(page).addEvent(page, event)
            } else {
                container.workspaces.writerFor(existingId).editEvent(existingId) { event.copy(id = existingId) }
            }
        }
    }

    fun delete(nodeId: String) {
        viewModelScope.launch { container.workspaces.writerFor(nodeId).removeBlock(nodeId) }
    }

    /** The event behind a day-list row, for the sheet to open on. */
    suspend fun eventFor(nodeId: String): ie.shoonya.yantra.data.format.EventRef? =
        container.db.eventDao().byId(nodeId)?.let { row ->
            val title = container.nodes.byId(nodeId)?.title.orEmpty()
            ie.shoonya.yantra.data.format.EventRef(
                id = row.nodeId,
                title = title,
                time = ie.shoonya.yantra.data.format.EventTime(
                    start = java.time.LocalDateTime.parse(row.startLocal),
                    end = java.time.LocalDateTime.parse(row.endLocal),
                    zone = row.zone?.let { java.time.ZoneId.of(it) },
                    allDay = row.allDay,
                ),
                rrule = row.rrule,
                cancelled = row.cancelled,
                location = row.location,
                reminderMin = row.reminderMin,
                // Carried, and it has to be: the sheet saves whatever it was handed, so dropping
                // this here would quietly turn a sitting into an ordinary untitled event the first
                // time anybody nudged its start time.
                forTaskId = row.forNodeId,
            )
        }

    /** A node's title, for the sheet to say whose time a sitting is. */
    suspend fun titleOf(nodeId: String): String? = container.nodes.byId(nodeId)?.title

    /**
     * Moves a block to a new start, keeping its length.
     *
     * A drag says where a thing now begins; how long it takes is not what the finger was saying, so
     * the end travels with the start rather than being recomputed from where the thumb let go.
     */
    fun moveTo(nodeId: String, start: java.time.LocalDateTime) {
        viewModelScope.launch {
            val writer = container.workspaces.writerFor(nodeId)
            val event = container.db.eventDao().byId(nodeId)
            if (event != null) {
                val length = java.time.Duration.between(
                    java.time.LocalDateTime.parse(event.startLocal),
                    java.time.LocalDateTime.parse(event.endLocal),
                )
                writer.editEvent(nodeId) { e ->
                    e.copy(time = e.time.copy(start = start, end = start.plus(length)), raw = null)
                }
            } else {
                writer.editTask(nodeId) { t -> t.copy(due = t.due?.movedTo(start, zone), raw = null) }
            }
        }
    }

    /** Changes how long a block lasts. The start stays where it is. */
    fun resizeTo(nodeId: String, end: java.time.LocalDateTime) {
        viewModelScope.launch {
            val writer = container.workspaces.writerFor(nodeId)
            val event = container.db.eventDao().byId(nodeId)
            if (event != null) {
                writer.editEvent(nodeId) { e ->
                    e.copy(time = e.time.copy(end = maxOf(end, e.time.start)), raw = null)
                }
            } else {
                writer.editTask(nodeId) { t ->
                    val spec = t.due ?: return@editTask t
                    val from = spec.startLocal(zone) ?: return@editTask t
                    val mins = java.time.Duration.between(from, end).toMinutes()
                    if (mins <= 0) t
                    else t.copy(due = spec.copy(duration = java.time.Duration.ofMinutes(mins)), raw = null)
                }
            }
        }
    }
}

/**
 * The same due date, moved to a new moment.
 *
 * An all-day due becomes a timed one when it is dragged onto an hour — putting it on the ruler at
 * half past two *is* saying it has a time now, and leaving it all-day would silently ignore the
 * drag.
 */
private fun ie.shoonya.yantra.data.format.DueSpec.movedTo(
    start: java.time.LocalDateTime,
    zone: ZoneId,
): ie.shoonya.yantra.data.format.DueSpec = copy(
    value = ie.shoonya.yantra.data.format.DueValue.At(start.atZone(zone).toInstant()),
)

/** Where a due date sits in local time, or null for a value that has no moment. */
private fun ie.shoonya.yantra.data.format.DueSpec.startLocal(zone: ZoneId): java.time.LocalDateTime? =
    when (val v = value) {
        is ie.shoonya.yantra.data.format.DueValue.At ->
            java.time.LocalDateTime.ofInstant(v.instant, zone)
        is ie.shoonya.yantra.data.format.DueValue.AllDay -> v.date.atStartOfDay()
    }

/** How much of the calendar is on screen. */
enum class CalendarMode(val label: String) {
    MONTH("Month"), WEEK("Week"), DAY("Day");
}
