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

    private val device = ie.shoonya.yantra.data.device.DeviceCalendarSource(container.app)
    private val calendarChoice = ie.shoonya.yantra.data.device.CalendarChoice(container.app)

    /**
     * Asked again whenever the overlay might have changed — a permission granted, a calendar ticked.
     *
     * A screen cannot observe a `SharedPreferences` write or a permission grant through a Flow, and
     * both happen while this view model is alive. One nudge covers them.
     */
    private val overlay = MutableStateFlow(0)

    fun overlayChanged() { overlay.value++ }

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

    /**
     * Month, week or day — the same data, three amounts of detail.
     *
     * Opens on the **day**, because that is the one you can work in: it draws to scale, it takes a
     * task from the rail, and it is where a sitting gets made. A month answers "when is that thing"
     * and is one tap away for it; landing there meant the calendar opened on the only view that
     * cannot be planned in.
     */
    private val _mode = MutableStateFlow(CalendarMode.DAY)
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

            // The phone's own calendars, re-read when the provider changes and when the choice
            // does. Permission is checked at the point of asking rather than remembered: it can be
            // revoked from Settings while this screen is open, and the honest response to that is an
            // empty overlay on the next emission rather than stale rows.
            val theirs = combine(device.changes(), overlay) { _, _ -> Unit }
                .map {
                    device.instances(fromUtc, toUtc, calendarChoice.effective(device))
                }

            combine(events, tasks, theirs) { e, t, d ->
                // Before drawing, not after: a block drawn from a stale due date would be visibly
                // wrong for one frame and then jump.
                reconcile(e, t, d)
                CalendarBucketer.bucket(
                    // The repository as a hue, by the same rule the smart lists and the widget
                    // already follow — including the part where a single open repository gets none,
                    // because then it distinguishes nothing and would only tint the whole app.
                    workspaceTints = workspaceTints(),
                    events = e.map { it.event },
                    // A sitting is drawn as its task, and tapping it should reach the task.
                    sittingOf = e.mapNotNull { row -> row.event.forNodeId?.let { row.event.nodeId to it } }.toMap(),
                    tasks = t,
                    // The title comes down with the row, joined from node — see [EventWithTitle].
                    titles = e.mapNotNull { row -> row.displayTitle?.let { row.event.nodeId to it } }.toMap(),
                    device = d,
                    from = from,
                    toExclusive = toExclusive,
                    zone = zone,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Keeps a task's due date in step with the meeting it is about — CALENDAR_PLAN.md §20, §22.
     *
     * **Everything in this app that acts on a time reads our own row.** Today, the rail, the widget
     * and the reminder scheduler all read the task's `due:`. For a task about somebody else's
     * meeting, that date is what the meeting said when the task was made — so moving the meeting in
     * the other calendar would leave the task due at the old hour, in Today on the wrong day, with
     * an alarm to match.
     *
     * One rule repairs all of it: when the meeting has moved, rewrite the line. Nothing else then
     * has to know a provider exists. It is a write to **our** file and never to theirs — there is no
     * permission to do the latter and there never will be.
     *
     * Self-terminating by construction: the rewrite makes the next comparison equal. Guarded on an
     * actual difference, because rewriting on every read would dirty the repository each time the
     * calendar is looked at and fill somebody's history with empty diffs.
     */
    private fun reconcile(
        ours: List<ie.shoonya.yantra.data.db.EventWithTitle>,
        tasks: List<ie.shoonya.yantra.data.db.DueRow>,
        theirs: List<ie.shoonya.yantra.data.device.DeviceEvent>,
    ) {
        if (theirs.isEmpty()) return
        val byKey = theirs.mapNotNull { d -> d.uid?.let { externalKey(it, occurrenceOf(d, zone)) to d } }.toMap()
        val byUid = theirs.mapNotNull { d -> d.uid?.let { it to d } }.toMap()
        fun match(uid: String?, occurrence: String?) =
            uid?.let { byKey[externalKey(it, occurrence)] ?: byUid[it] }

        // Event nodes: their own times are the cache, so they are what gets corrected.
        for (row in ours) {
            val d = match(row.event.extUid, row.event.extStart) ?: continue
            val (start, end) = deviceLocalSpan(d, zone)
            if (d.title == row.title &&
                row.event.startLocal == start.toString() &&
                row.event.endLocal == end.toString()
            ) continue
            viewModelScope.launch {
                container.workspaces.writerFor(row.event.nodeId).editEvent(row.event.nodeId, PLACED) { e ->
                    // The words follow too: a meeting that was renamed should not leave a file
                    // describing the old one.
                    e.copy(
                        title = d.title,
                        time = e.time.copy(start = start, end = end, allDay = d.allDay),
                        raw = null,
                    )
                }
            }
        }

        // And a task about a meeting, for anyone who turned one into work.
        for (t in tasks) {
            val d = match(t.extUid, t.extStart) ?: continue
            val (start, end) = deviceLocalSpan(d, zone)
            val startMillis = start.atZone(zone).toInstant().toEpochMilli()
            val minutes = java.time.Duration.between(start, end).toMinutes().toInt().takeIf { it > 0 }
            if (t.dueMillis == startMillis && t.durationMin == minutes) continue
            viewModelScope.launch {
                container.workspaces.writerFor(t.nodeId).editTask(t.nodeId, PLACED) { task ->
                    val due = task.due ?: return@editTask task
                    task.copy(
                        due = due.movedTo(start, zone)
                            .copy(duration = minutes?.let { java.time.Duration.ofMinutes(it.toLong()) }),
                        raw = null,
                    )
                }
            }
        }
    }

    /**
     * Opens somebody else's meeting as a node of yours, making one the first time — §23.
     *
     * **An event node, not a task.** The app has had the shape all along: `NodeType.EVENT` is a
     * `node` row with an `event` row of event-specific columns beside it — a node with everything a
     * node has, and a start, an end, a place and a colour as well. That is the thing to open.
     *
     * Made **lazily**, on the first tap, so a calendar of two hundred meetings costs two hundred
     * nothing until you touch one. Made **once**: the second tap finds the line already there by the
     * identity its sync source gave the meeting, and goes to the same page.
     *
     * One node, and the day still draws one block — the meeting's, at the meeting's hours, which
     * takes you here. Nothing is duplicated because nothing is copied: the title and times on the
     * line are what lets it be found and drawn when the calendar cannot be read, and everything else
     * is read live from the calendar that owns it.
     */
    fun openLocally(item: DayItem.Device, onOpen: (String) -> Unit) {
        val existing = item.noteId
        if (existing != null) {
            onOpen(existing)
            return
        }
        val uid = item.uid ?: return
        viewModelScope.launch {
            val page = container.nodes.inboxList()
            val id = container.workspaces.writerFor(page).addEvent(
                pageId = page,
                event = ie.shoonya.yantra.data.format.EventRef(
                    id = "",
                    title = item.title,
                    time = ie.shoonya.yantra.data.format.EventTime(
                        start = item.start,
                        end = item.end,
                        zone = null,
                        allDay = item.allDay,
                    ),
                    location = item.location,
                    external = ie.shoonya.yantra.data.format.ExternalRef(
                        uid = uid,
                        // Named only when the meeting repeats, so a page about this Monday does not
                        // become the page for every Monday.
                        occurrence = if (item.repeating) item.start else null,
                    ),
                ),
            )
            if (id.isNotEmpty()) onOpen(id)
        }
    }

    /**
     * Turns an event into a task — CALENDAR_PLAN.md §21.
     *
     * The one way to write about anything on your calendar. A task is this app's only noun that
     * carries a document, and it already has the checkbox, the list, the focus timer and the place
     * in Today that a second page-bearing thing would have had to grow for itself.
     *
     * Lossless, because a due date has carried a **duration** since §10 — which is what lets a task
     * be drawn to scale on a timeline at all. An hour-long event is an hour-long task.
     *
     * **The id is kept.** `editBlock` maps a block to a block and the node is the same node, so an
     * event that already had notes written on it comes out as a task with those notes: this upgrades
     * what is there rather than replacing it.
     *
     * The one thing a task line cannot hold is a colour, and that is a real loss rather than a
     * rounding — see §21.
     */
    fun turnIntoTask(nodeId: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val writer = container.workspaces.writerFor(nodeId)
            var location: String? = null
            writer.editBlock(nodeId, PLACED) { b ->
                if (b !is ie.shoonya.yantra.data.format.EventRef) b else {
                    location = b.location
                    ie.shoonya.yantra.data.format.TaskRef(
                        id = b.id,
                        title = b.title.ifBlank { "Untitled" },
                        due = ie.shoonya.yantra.data.format.DueSpec(
                            value = if (b.time.allDay) {
                                ie.shoonya.yantra.data.format.DueValue.AllDay(b.time.start.toLocalDate())
                            } else {
                                ie.shoonya.yantra.data.format.DueValue.At(b.time.start.atZone(zone).toInstant())
                            },
                            reminderMin = b.reminderMin,
                            // An all-day task is a day, not a span: giving it twenty-four hours
                            // would draw a block down the whole ruler for something that has no
                            // hours of its own.
                            duration = if (b.time.allDay) null else b.time.duration.takeIf { !it.isZero },
                        ),
                        labels = b.labels,
                        priority = b.priority,
                        indent = b.indent,
                        raw = null,
                    )
                }
            }
            // A task has nowhere to put a place, and a page is exactly where a detail about a thing
            // belongs. Written only when there is one, so an ordinary event does not earn a file it
            // has no use for.
            location?.takeIf { it.isNotBlank() }?.let { where ->
                writer.addBlock(nodeId, ie.shoonya.yantra.data.db.NodeType.PARAGRAPH, where)
            }
            onDone(nodeId)
        }
    }


    /** The day list under the grid. */
    val selectedItems: StateFlow<List<DayItem>> = combine(days, _selected) { d, day -> d[day].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Which hue each open repository wears, or nothing at all when only one is open. */
    private fun workspaceTints(): Map<String, Long> {
        val open = container.registry.entries().filter { container.workspaces.isOpen(it.id) }
        return if (open.size < 2) emptyMap()
        else open.associate { it.id to ie.shoonya.yantra.data.label.LabelPalette.defaultFor(it.name) }
    }

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
                // PLACED for the same reason a drag is: the sheet can change the hour, and the
                // block behind it is drawn from the index.
                container.workspaces.writerFor(existingId)
                    .editEvent(existingId, PLACED) { event.copy(id = existingId) }
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
                color = row.color,
                // Carried, and it has to be: the sheet saves whatever it was handed, so dropping
                // this here would quietly turn a sitting into an ordinary untitled event the first
                // time anybody nudged its start time.
                forTaskId = row.forNodeId,
            )
        }

    /** A node's title, for the sheet to say whose time a sitting is. */
    suspend fun titleOf(nodeId: String): String? = container.nodes.byId(nodeId)?.title

    /**
     * A block now runs from [start] to [end].
     *
     * One method for all three grabs — dragged, stretched from the top, stretched from the foot —
     * because they are one fact about a block said three ways. It used to be two, a move and a
     * resize, and the two drifted: a screen recording showed the resize landing a beat later than
     * the move, and the only reason was that each had its own copy of the same write.
     *
     * A task carries the same fact differently: the due date is where it starts, and the length it
     * is blocked out for is how long. Both are written, so dragging a due task on the timeline says
     * the same thing about it that dragging an event says about an event.
     */
    fun spanTo(nodeId: String, start: java.time.LocalDateTime, end: java.time.LocalDateTime) {
        viewModelScope.launch {
            val writer = container.workspaces.writerFor(nodeId)
            val event = container.db.eventDao().byId(nodeId)
            if (event != null) {
                writer.editEvent(nodeId, PLACED) { e ->
                    e.copy(time = e.time.copy(start = start, end = maxOf(end, start)), raw = null)
                }
            } else {
                writer.editTask(nodeId, PLACED) { t ->
                    val spec = t.due ?: return@editTask t
                    val mins = java.time.Duration.between(start, end).toMinutes()
                    t.copy(
                        due = spec.movedTo(start, zone)
                            .copy(duration = if (mins > 0) java.time.Duration.ofMinutes(mins) else spec.duration),
                        raw = null,
                    )
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

/**
 * Putting a block somewhere is a structural change, not a text edit.
 *
 * A plain edit defers its reindex by a couple of hundred milliseconds, which is right for typing —
 * the letters are already on screen from the field's own state, so nothing is waiting on the index.
 * A calendar has no such second copy: **every block it draws comes from the index**, so a deferred
 * one means the block you just dropped springs back to where it was and arrives at its new hour a
 * beat later, or sits in the wrong place indefinitely if the next thing you do is drag it again and
 * restart the timer.
 *
 * The writer's own rule already said so — "creating, deleting, completing or *moving* a block
 * changes what the list contains, and that list is drawn from the index" — and a drag ends once, on
 * release, so the rebuild it costs is one, not one per frame.
 */
private val PLACED = ie.shoonya.yantra.data.sync.Change.STRUCTURAL

/** How much of the calendar is on screen. */
enum class CalendarMode(val label: String) {
    MONTH("Month"), WEEK("Week"), DAY("Day");
}
