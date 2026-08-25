package ie.napkin.supertasks.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.SubtreeTaskCount
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.SortSpec
import ie.napkin.supertasks.domain.PomodoroTimer
import ie.napkin.supertasks.data.db.SmartListDefEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ie.napkin.supertasks.data.db.PropertyDefEntity

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val nodes = container.nodes
    private val smartLists = container.smartLists
    private val properties = container.properties
    private val labelsRepo = container.labels

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

    val timerState: StateFlow<PomodoroTimer.State?> = container.timer.state

    /** Built-in property definitions (Priority, Due), for the smart-list filter builder. */
    val defs: StateFlow<List<PropertyDefEntity>> =
        properties.builtInDefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Labels, for the smart-list filter builder's "has label" condition. */
    val labels: StateFlow<List<LabelEntity>> =
        labelsRepo.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Get-or-create, called from the smart-list builder's inline label picker. */
    suspend fun createLabel(name: String) = labelsRepo.getOrCreate(name)

    fun createList(title: String) {
        viewModelScope.launch { nodes.create(null, NodeType.LIST, title) }
    }

    /** Quick-capture a task from the cog: drop it in the Inbox, then open that list. */
    fun quickAddTask(title: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val inboxId = nodes.inboxList()
            nodes.captureTask(inboxId, title, container.labels, container.properties)
            onDone(inboxId)
        }
    }

    /** Create a list and hand back its id so the caller can open it. */
    fun createListThen(title: String, onDone: (String) -> Unit) {
        viewModelScope.launch { onDone(nodes.create(null, NodeType.LIST, title)) }
    }

    fun createGroup(title: String) {
        viewModelScope.launch { nodes.createGroup(title) }
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
}
