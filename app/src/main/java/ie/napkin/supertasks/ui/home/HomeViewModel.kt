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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    val counts: StateFlow<Map<String, SubtreeTaskCount>> =
        nodes.listTaskCounts()
            .map { list -> list.associateBy { it.rootId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val timerState: StateFlow<PomodoroTimer.State?> = container.timer.state

    /** Built-in property definitions (Priority, Due), for the smart-list filter builder. */
    val defs: StateFlow<List<ie.napkin.supertasks.data.db.PropertyDefEntity>> =
        properties.builtInDefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Labels, for the smart-list filter builder's "has label" condition. */
    val labels: StateFlow<List<LabelEntity>> =
        labelsRepo.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Get-or-create, called from the smart-list builder's inline label picker. */
    suspend fun createLabel(name: String) = labelsRepo.getOrCreate(name)

    fun createList(title: String) {
        viewModelScope.launch { nodes.create(null, NodeType.LIST, title) }
    }

    /** Quick-capture a task from the cog: drop it in an "Inbox" list (created once), open it. */
    fun quickAddTask(title: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val inboxId = allLists.value.firstOrNull {
                it.parentId == null && it.type == NodeType.LIST && it.title.equals("Inbox", ignoreCase = true)
            }?.id ?: nodes.create(null, NodeType.LIST, "Inbox")
            nodes.create(inboxId, NodeType.TASK, title)
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
