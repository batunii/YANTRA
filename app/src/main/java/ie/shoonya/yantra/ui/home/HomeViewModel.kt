package ie.shoonya.yantra.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.shoonya.yantra.AppContainer
import ie.shoonya.yantra.domain.TimingRequest
import ie.shoonya.yantra.data.workspace.WorkspaceEntry
import ie.shoonya.yantra.data.db.LabelEntity
import ie.shoonya.yantra.data.db.NodeEntity
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.db.SubtreeTaskCount
import ie.shoonya.yantra.data.filter.Filter
import ie.shoonya.yantra.data.filter.SortSpec
import ie.shoonya.yantra.domain.FocusTimer
import ie.shoonya.yantra.data.db.SmartListDefEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ie.shoonya.yantra.data.db.PropertyDefEntity

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val nodes = container.nodes
    private val smartLists = container.smartLists
    private val properties = container.properties
    private val labelsRepo = container.labels

    /** The player's play/stop, and the consent it needs when the clock is elsewhere. */
    val timing = TimingRequest(container.running)

    /**
     * The player's one button.
     *
     * An open stopwatch, not a commitment — pressing play on a bar you were passing anyway says
     * "start counting", and says nothing about for how long. The length is a decision with its own
     * screen, which the body of the player opens.
     */
    fun toggleClock(id: String, title: String) {
        viewModelScope.launch { timing.toggle(id, title) }
    }

    /**
     * The workspaces the builder may offer as a rule's reach.
     *
     * Registry order, filtered to what is actually open — a repo listed but not opened cannot be
     * searched, and offering it would let someone write a rule that silently matches nothing.
     */
    val workspaces: List<WorkspaceEntry> =
        container.registry.entries().filter { container.workspaces.isOpen(it.id) }

    val topLevel: StateFlow<List<NodeEntity>> =
        nodes.topLevel().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every list & smart list, at top level or inside a group. */
    val allLists: StateFlow<List<NodeEntity>> =
        nodes.allLists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Done/total per list — for every list, rules or no rules. A list that owns its tasks is
     * counted by walking its subtree; a smart list is counted by running its rule and, separately,
     * the done counterpart of it, because a rule that says "not done" hides exactly the half of the
     * answer we need. Different arithmetic, same fact, one map: a list on the home screen should
     * report where it stands regardless of how it was assembled.
     */
    val counts: StateFlow<Map<String, SubtreeTaskCount>> =
        combine(nodes.listTaskCounts(), smartCounts()) { owned, smart ->
            owned.associateBy { it.rootId } + smart
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun smartCounts(): Flow<Map<String, SubtreeTaskCount>> =
        smartLists.allDefs().flatMapLatest { defs ->
            if (defs.isEmpty()) flowOf(emptyMap())
            else combine(defs.map { def -> countOf(def) }) { pairs -> pairs.toMap() }
        }

    private fun countOf(def: SmartListDefEntity): Flow<Pair<String, SubtreeTaskCount>> {
        val matching = smartLists.query(def)
        val completed = smartLists.queryCompleted(def)
        return if (completed == null) {
            // No done clause, so the rule already returns both halves.
            matching.map { all ->
                def.nodeId to SubtreeTaskCount(def.nodeId, all.size, all.count { it.done })
            }
        } else {
            combine(matching, completed) { open, done ->
                def.nodeId to SubtreeTaskCount(def.nodeId, open.size + done.size, done.size)
            }
        }
    }

    val timerState: StateFlow<FocusTimer.State?> = container.timer.state

    /**
     * Today's events, for the byline and for NEXT — HOME_UI.md §5, §6.
     *
     * A day at a time, recomputed at midnight by the range moving rather than by a timer: the flow
     * is keyed on the window, so a device left open overnight simply asks for a different day.
     */
    private val todayEvents: StateFlow<List<ie.shoonya.yantra.data.db.EventWithTitle>> =
        container.db.eventDao()
            .inRange(startOfToday(), startOfToday() + DAY_MS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * How much of today is already spoken for, in minutes.
     *
     * **All-day events are excluded.** A passport renewal marked all day is not twenty-four hours
     * of capacity, and counting it as such would make the one number Home reports useless on
     * exactly the days it matters.
     *
     * Clipped to today at both ends, so a meeting running past midnight contributes the part of it
     * that is actually today.
     */
    val bookedMinutes: StateFlow<Int> = todayEvents
        .map { events ->
            val from = startOfToday()
            val to = from + DAY_MS
            events.filterNot { it.event.allDay }.sumOf { e ->
                val start = e.event.startUtc.coerceAtLeast(from)
                val end = e.event.endUtc.coerceAtMost(to)
                ((end - start).coerceAtLeast(0L) / 60_000L).toInt()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The next event today that has not yet ended — HOME_UI.md §6.
     *
     * Not *started*: one already running is still the thing you are next expected at, and dropping
     * it the moment it begins would take the row away at the point it is most use. Null when there
     * is nothing left today, so evenings are quiet rather than showing tomorrow morning.
     */
    val nextToday: StateFlow<ie.shoonya.yantra.data.db.EventWithTitle?> = todayEvents
        .map { events ->
            val now = System.currentTimeMillis()
            events.filterNot { it.event.allDay }
                .filter { it.event.endUtc > now }
                .minByOrNull { it.event.startUtc }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Built-in property definitions (Priority, Due), for the smart-list filter builder. */
    val defs: StateFlow<List<PropertyDefEntity>> =
        properties.builtInDefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Labels, for the smart-list filter builder's "has label" condition. */
    val labels: StateFlow<List<LabelEntity>> =
        labelsRepo.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Get-or-create, called from the smart-list builder's inline label picker. */
    suspend fun createLabel(name: String) = labelsRepo.getOrCreate(name)

    fun createList(title: String, workspaceId: String) {
        viewModelScope.launch { nodes.create(null, NodeType.LIST, title, workspaceId = workspaceId) }
    }

    /** Quick-capture a task from the cog: drop it in the Inbox, then open that list. */
    /**
     * Who a typed `@` may name on Home.
     *
     * Home captures into the Inbox, which is Personal's, so this is Personal's roster — not a union
     * across repos. Offering a shared project's collaborators on a line that is about to land in
     * your own workspace is exactly the mix-up the scoped query exists to prevent.
     */
    val assignable: StateFlow<List<String>> =
        flow { emit(container.people.loginsFor("")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun linkTargets(query: String) = container.nodes.searchLinkTargets(query)

    suspend fun linkIdsFor(text: String) = container.nodes.linkIdsFor(text)

    fun quickAddTask(title: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val inboxId = nodes.inboxList()
            nodes.captureTask(inboxId, title, container.labels, container.properties, container.people)
            onDone(inboxId)
        }
    }

    /** Create a list and hand back its id so the caller can open it. */
    fun createListThen(title: String, workspaceId: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            onDone(nodes.create(null, NodeType.LIST, title, workspaceId = workspaceId))
        }
    }

    fun createGroup(title: String, workspaceId: String) {
        viewModelScope.launch { nodes.createGroup(title, workspaceId) }
    }

    /**
     * Where a new list goes when nobody has said otherwise: the local workspace.
     *
     * Named rather than positional. Registry order happens to put Personal first today, and a
     * default that quietly followed whatever came back first would move people's lists the day that
     * changed.
     */
    val defaultWorkspaceId: String
        get() = workspaces.firstOrNull { it.id.isEmpty() }?.id ?: workspaces.firstOrNull()?.id ?: ""

    /** The colour a list wears, or null to take it off — the colour law, as remade. */
    fun setListColor(listId: String, color: String?) {
        viewModelScope.launch { container.nodes.setListColor(listId, color) }
    }

    fun moveToGroup(id: String, groupId: String?) {
        viewModelScope.launch { nodes.moveToGroup(id, groupId) }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch { nodes.deleteGroup(id) }
    }

    /** Create a smart list from a fully-built filter (the custom builder). */
    fun createSmartList(title: String, filter: Filter, sort: List<SortSpec>, homeParentId: String?) {
        viewModelScope.launch {
            smartLists.createSmartList(
                title = title, scopeRootId = null, filter = filter, sort = sort, homeParentId = homeParentId,
            )
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { nodes.rename(id, title) }
    }

    fun delete(id: String) {
        viewModelScope.launch { nodes.delete(id) }
    }

    private fun startOfToday(): Long =
        java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
}

private const val DAY_MS = 24L * 60 * 60 * 1000
