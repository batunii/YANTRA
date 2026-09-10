package ie.shoonya.yantra.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.shoonya.yantra.AppContainer
import ie.shoonya.yantra.data.db.BuiltIns
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
                    tasks = t,
                    // The title comes down with the row, joined from node — see [EventWithTitle].
                    titles = e.mapNotNull { row -> row.title?.let { row.event.nodeId to it } }.toMap(),
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
            )
        }
}
