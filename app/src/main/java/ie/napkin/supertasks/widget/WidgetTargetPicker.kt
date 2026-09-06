package ie.napkin.supertasks.widget

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.YantraField
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * Choosing what a widget shows, in one place.
 *
 * Both routes to this decision — [WidgetConfigActivity] while placing, [WidgetSettingsActivity]
 * afterwards — used to hold their own copy of a list-filtering expression, and both were narrower
 * than the widget behind them: the renderer has always drawn *any* node's task children, while the
 * pickers only ever offered top-level lists. So a task with six subtasks under it — a project, and
 * the most obvious thing anyone would want on a home screen — could not be put there at all.
 *
 * Browse, then search. With nothing typed the picker shows the lists, which is the ordinary answer
 * and the one worth being one tap away; typing widens it to every bindable node. The search itself
 * lives in SQL rather than here, so what is offered and how it is ordered is one decision — see
 * `NodeDao.searchBindable`, which also explains what is deliberately *not* bindable.
 */
@Composable
fun rememberBindableResults(
    query: String,
    search: suspend (String) -> List<NodeEntity>,
): List<NodeEntity>? {
    var results by remember { mutableStateOf<List<NodeEntity>?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        // Blank is "browse", not "everything": the caller has better-organised lists to show than
        // an unranked dump of every node in the workspace.
        results = if (q.isEmpty()) null else search(q)
    }
    return results
}

@Composable
fun WidgetSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    YantraField(
        value = query,
        onValue = onQuery,
        placeholder = "Search lists and tasks",
        modifier = modifier,
    )
}

/**
 * The picker's rows: search results when there is a query, the browse lists when there is not.
 *
 * A `LazyListScope` extension rather than a composable so the settings screen can fold it into the
 * column it already has, instead of nesting a second scroller inside one.
 */
fun LazyListScope.widgetTargetItems(
    results: List<NodeEntity>?,
    smartLists: List<NodeEntity>,
    lists: List<NodeEntity>,
    listTitles: Map<String, String>,
    /** The node the widget is already bound to, marked wherever it appears. Null while placing. */
    selectedId: String? = null,
    /**
     * The bound target, when it is not one of the rows below.
     *
     * A widget can be pointed at a task, and the browse sections are lists — so the thing the
     * widget is actually showing would appear nowhere, and the screen would answer "which list?"
     * with nothing selected and no hint that a perfectly good answer was already in force. Pinned
     * above the sections, it is both the current state and the way back to it.
     */
    pinned: NodeEntity? = null,
    onPick: (NodeEntity) -> Unit,
) {
    if (results != null) {
        if (results.isEmpty()) {
            item(key = "no-results") {
                Text(
                    "Nothing by that name",
                    fontSize = 13.sp,
                    color = Yantra.colors.textMuted,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp),
                )
            }
            return
        }
        items(results, key = { it.id }) { node ->
            WidgetListRow(
                node = node,
                smartList = node.type == NodeType.SMART_LIST,
                onPick = onPick,
                // Which list a result came out of. Only for tasks: a list is already the thing
                // being named, and telling it which group it sits in answers a question nobody
                // asked at the moment they are picking one.
                subtitle = if (node.type == NodeType.TASK) {
                    node.parentId?.let { listTitles[it] }
                } else null,
                selected = node.id == selectedId,
            )
        }
        return
    }

    if (pinned != null) {
        item(key = "pinned-label") {
            SectionLabel("Showing", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        item(key = "pinned-${pinned.id}") {
            WidgetListRow(
                node = pinned,
                smartList = pinned.type == NodeType.SMART_LIST,
                onPick = onPick,
                subtitle = if (pinned.type == NodeType.TASK) {
                    pinned.parentId?.let { listTitles[it] }
                } else null,
                selected = true,
            )
        }
    }
    if (smartLists.isNotEmpty()) {
        item(key = "smart-label") {
            SectionLabel("Smart lists", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        items(smartLists, key = { it.id }) {
            WidgetListRow(it, smartList = true, onPick = onPick, selected = it.id == selectedId)
        }
    }
    if (lists.isNotEmpty()) {
        item(key = "lists-label") {
            SectionLabel("Lists", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        items(lists, key = { it.id }) {
            WidgetListRow(it, smartList = false, onPick = onPick, selected = it.id == selectedId)
        }
    }
}
