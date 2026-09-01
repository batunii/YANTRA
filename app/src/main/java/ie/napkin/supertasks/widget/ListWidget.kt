package ie.napkin.supertasks.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.wrapContentHeight
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ie.napkin.supertasks.App
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.MainActivity
import ie.napkin.supertasks.R
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.time.todayMidnight
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.filter.completedVariant
import ie.napkin.supertasks.ui.components.dateLabel
import ie.napkin.supertasks.ui.components.dateTimeLabel
import ie.napkin.supertasks.ui.components.deadlineLabel
import ie.napkin.supertasks.ui.components.selectConfig
import ie.napkin.supertasks.widget.actions.ToggleDoneAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import ie.napkin.supertasks.ui.theme.YantraColors
import ie.napkin.supertasks.data.format.Links

/**
 * Glance-state keys for the configured binding and per-widget settings — see [YantraListWidget]
 * docs. Settings live in Glance state rather than [WidgetPrefs] because a live render session only
 * reacts to state changes, so writing here is what makes a settings screen take effect at once.
 */
object ListWidgetKeys {
    val NODE_ID = stringPreferencesKey("nodeId")
    val IS_SMART = booleanPreferencesKey("isSmart")
    /** Scrim alpha as a percentage, 50..100. */
    val OPACITY = intPreferencesKey("opacity")
    val SHOW_DONE = booleanPreferencesKey("showDone")
}

/** Defaults for the settings in [ListWidgetKeys], shared with the settings screen. */
object ListWidgetDefaults {
    const val OPACITY = 94
    const val SHOW_DONE = true
    const val MIN_OPACITY = 50
}

/** Built-in def ids the widget renders, resolved once per data flow. */
private data class WidgetDefIds(val due: String?, val deadline: String?, val priority: String?) {
    val all: List<String> get() = listOfNotNull(due, deadline, priority)
}

/** Glance-free row model — no Compose-UI types (ImageVector/painter) allowed here. */
data class WidgetRow(
    val id: String,
    val title: String,
    val done: Boolean,
    /** The task glyph's middle state, so a widget shows the same three states the app does. */
    val inProgress: Boolean,
    val dueLabel: String?,
    val dueOverdue: Boolean,
    val hasReminder: Boolean,
    val deadlineLabel: String?,
    val deadlineOverdue: Boolean,   // ≤ today: drives the red label
    val deadlinePast: Boolean,      // strictly before today: drives OVERDUE grouping
    val priorityColor: Long?,
    val listName: String?,          // source list, shown on aggregating (smart) widgets
)

sealed interface WidgetItem {
    /** [urgent] headers are drawn in the overdue red — the section label is the alarm. */
    data class Header(val label: String, val urgent: Boolean = false) : WidgetItem
    data class Task(val row: WidgetRow) : WidgetItem
}

data class WidgetData(
    val title: String,
    val summary: String,
    val items: List<WidgetItem>,
    val nodeId: String?,
    val isSmart: Boolean,
)

private const val DAY_MS = 86_400_000L

/**
 * How many completed tasks the Today widget will show under DONE. Deliberately small: the widget
 * exists to answer "what is left", and a day's finished work is a footnote to that, not the body.
 */
private const val DONE_LIMIT = 3

/**
 * Type and rhythm for one widget size. A home-screen widget is read at arm's length, in passing,
 * usually one-handed — it needs to be *bigger* than the same list inside the app, not smaller.
 * The previous ramp (13sp rows in a 3x3 cell) was app-sized content dropped into a widget box.
 */
private data class WidgetMetrics(
    val header: TextUnit,
    val summary: TextUnit,
    val rowTitle: TextUnit,
    val meta: TextUnit,
    val section: TextUnit,
    val box: Dp,
    val rowGap: Dp,
    val pad: Dp,
    val addButton: Dp,
) {
    companion object {
        /** Anything under this is a strip, not a panel, and has to stay terse. */
        private val COMPACT = 150.dp

        fun forSize(size: DpSize): WidgetMetrics =
            if (size.height < COMPACT) WidgetMetrics(
                header = 15.sp, summary = 11.sp, rowTitle = 13.sp, meta = 11.sp, section = 10.sp,
                box = 19.dp, rowGap = 3.dp, pad = 11.dp, addButton = 26.dp,
            ) else WidgetMetrics(
                header = 19.sp, summary = 12.5.sp, rowTitle = 15.sp, meta = 12.sp, section = 11.sp,
                box = 23.dp, rowGap = 7.dp, pad = 15.dp, addButton = 32.dp,
            )
    }
}

/**
 * Pure mapper — unit-testable, no Android deps beyond java.time.
 *
 * [hideTodayDue] drops a bare "Today" due label. Inside a widget titled *Today* every row is
 * due today, so the subtitle was a line of text per row that carried no information — the only
 * dates left are the ones that say something (an overdue date, or a time of day).
 */
internal fun buildRows(
    nodes: List<NodeEntity>,
    values: List<PropertyValueEntity>,
    due: String?,
    deadline: String?,
    priority: String?,
    priorityColors: Map<String, Long>,
    parentTitles: Map<String, String> = emptyMap(),
    hideTodayDue: Boolean = false,
): List<WidgetRow> {
    val byNode = values.groupBy { it.nodeId }
    val todayStart = todayMidnight()
    return nodes.map { n ->
        val dueRow = byNode[n.id]?.firstOrNull { it.defId == due }
        val deadlineRow = byNode[n.id]?.firstOrNull { it.defId == deadline }
        val prio = byNode[n.id]?.firstOrNull { it.defId == priority }?.vText
        WidgetRow(
            id = n.id,
            title = Links.plain(n.title.orEmpty()).ifBlank { "Untitled" },
            done = n.done,
            inProgress = n.inProgress,
            dueLabel = dueRow?.vDate
                ?.let { if (dueRow.vBool == true) dateTimeLabel(it) else dateLabel(it) }
                ?.takeUnless { hideTodayDue && it == dateLabel(todayStart) },
            dueOverdue = dueRow?.vDate?.let { it < todayStart } == true,
            hasReminder = dueRow?.vNumber != null,
            deadlineLabel = deadlineRow?.vDate?.let { deadlineLabel(it) },
            deadlineOverdue = deadlineRow?.vDate?.let { it < todayStart + DAY_MS } == true,
            deadlinePast = deadlineRow?.vDate?.let { it < todayStart } == true,
            priorityColor = prio?.let { priorityColors[it] },
            listName = n.parentId?.let { parentTitles[it] },
        )
    }
}

/**
 * Today widget: OVERDUE / TODAY sections; headers omitted when only one section has rows —
 * a lone "TODAY" over a Today widget is a label for something already named by the title.
 * The overdue header carries its count, since "how much is late" is the one number worth
 * reading before you read any row. A deadline-only task belongs to OVERDUE only when the
 * deadline is strictly past — "Due today" under an OVERDUE header would read self-contradictory.
 */
internal fun groupToday(rows: List<WidgetRow>, done: List<WidgetRow> = emptyList()): List<WidgetItem> {
    val (overdue, today) = rows.partition { it.dueOverdue || (it.deadlinePast && it.dueLabel == null) }
    val open: List<WidgetItem> = if (overdue.isEmpty() || today.isEmpty()) {
        rows.map { WidgetItem.Task(it) }
    } else buildList {
        add(WidgetItem.Header("OVERDUE · ${overdue.size}", urgent = true))
        overdue.forEach { add(WidgetItem.Task(it)) }
        add(WidgetItem.Header("TODAY"))
        today.forEach { add(WidgetItem.Task(it)) }
    }
    if (done.isEmpty()) return open
    return open + WidgetItem.Header("DONE · ${done.size}") + done.map { WidgetItem.Task(it) }
}

/**
 * The one list widget: shows a chosen list or smart list. [ListWidgetProvider] serves the
 * configurable variant; [TodayWidgetReceiver] serves [TodayWidget], which always resolves the
 * Today smart list.
 *
 * Binding lives in Glance state ([ListWidgetKeys]) because a live render session only reacts
 * to state changes and composition-observed flows — it never re-runs provideGlance. The
 * config activity writes state, so the just-placed widget re-composes immediately; the
 * [WidgetPrefs] fallback keeps widgets placed before the Glance migration working.
 */
open class YantraListWidget : GlanceAppWidget() {

    // Not Single: with Single every layout decision is made against the *minimum* 180x110dp
    // from the provider info, so a widget the user dragged out to 4x4 was still being typeset
    // for a matchbox. Responsive rather than Exact because there are exactly two layouts worth
    // having ([WidgetMetrics]) — Exact would recompose (and re-collect the data flow) for every
    // size the launcher reports, to land on one of the same two answers.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 110.dp), DpSize(300.dp, 240.dp))
    )

    protected open val forceToday: Boolean = false

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as App).container
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val prefsBinding: Pair<String, Boolean>? = if (forceToday) null else {
            WidgetPrefs.nodeId(context, widgetId)
                ?.let { it to WidgetPrefs.isSmart(context, widgetId) }
        }
        provideContent {
            val state = currentState<Preferences>()
            val binding = if (forceToday) null else {
                state[ListWidgetKeys.NODE_ID]?.let { it to (state[ListWidgetKeys.IS_SMART] ?: false) }
                    ?: prefsBinding
            }
            val showDone = state[ListWidgetKeys.SHOW_DONE] ?: ListWidgetDefaults.SHOW_DONE
            val opacity = state[ListWidgetKeys.OPACITY] ?: ListWidgetDefaults.OPACITY
            val dataFlow = remember(binding, showDone) { dataFlow(container, binding, showDone) }
            val data by dataFlow.collectAsState(WidgetData("…", "", emptyList(), null, false))
            val custom = yantraGlanceColors(context)
            val content = @Composable {
                ListContent(data, opacity = opacity, widgetId = widgetId, isToday = forceToday)
            }
            if (custom != null) {
                GlanceTheme(colors = custom) { content() }
            } else {
                GlanceTheme { content() }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun dataFlow(
        container: AppContainer,
        binding: Pair<String, Boolean>?,
        showDone: Boolean,
    ): Flow<WidgetData> = flow {
        val (nodeId, isSmart) = when {
            forceToday -> container.nodes.todaySmartList()?.id to true
            else -> binding?.first to (binding?.second ?: false)
        }
        if (nodeId == null) {
            emit(WidgetData(if (forceToday) "Today" else "Yantra list", "", emptyList(), null, false))
            return@flow
        }

        val defs = container.properties.builtInDefsOnce()
        val dueDef = defs.firstOrNull { it.name.equals(BuiltIns.DUE_NAME, ignoreCase = true) }
        val deadlineDef = defs.firstOrNull { it.name.equals(BuiltIns.DEADLINE_NAME, ignoreCase = true) }
        val priorityDef = defs.firstOrNull { it.name.equals(BuiltIns.PRIORITY_NAME, ignoreCase = true) }
        val defIds = WidgetDefIds(dueDef?.id, deadlineDef?.id, priorityDef?.id)
        val priorityColors: Map<String, Long> = priorityDef
            ?.let { def -> selectConfig(def).options.mapNotNull { o -> o.color?.let { o.name to it } }.toMap() }
            ?: emptyMap()

        val titleFlow = container.nodes.observe(nodeId)
        val smartDef = if (isSmart) container.smartLists.defById(nodeId) else null
        if (isSmart && smartDef == null) {
            emit(WidgetData("List", "", emptyList(), nodeId, true))
            return@flow
        }
        // What you finished today is the other half of "today", and it is the half that makes a
        // list widget feel like it is reporting rather than nagging. The same rules, asked the
        // opposite question — see [completedVariant].
        val doneFlow: Flow<List<NodeEntity>> = if (forceToday && showDone && smartDef != null) {
            val filter = runCatching {
                FilterJson.decodeFromString(Filter.serializer(), smartDef.filterJson)
            }.getOrNull()
            filter?.let { completedVariant(it) }
                ?.let { flipped ->
                    val json = FilterJson.encodeToString(Filter.serializer(), flipped)
                    container.smartLists.query(smartDef.copy(filterJson = json))
                }
                ?: flowOf(emptyList())
        } else flowOf(emptyList())
        // Open work only on this flow. On a plain list widget that is the whole story and the
        // header keeps the done tally; the Today widget re-admits what was finished today via
        // [doneFlow], below the open rows.
        val itemsFlow: Flow<Pair<List<NodeEntity>, String>> =
            if (isSmart) container.smartLists.query(smartDef!!).map { tasks ->
                tasks to if (tasks.size == 1) "1 task" else "${tasks.size} tasks"
            }
            else container.nodes.children(nodeId).map { children ->
                val tasks = children.filter { it.type == NodeType.TASK }
                tasks.filterNot { it.done } to "${tasks.count { it.done }} of ${tasks.size} done"
            }

        emitAll(
            itemsFlow.flatMapLatest { (tasks, summary) ->
                combine(
                    titleFlow,
                    container.db.propertyDao().valuesForNodes(tasks.map { it.id }, defIds.all),
                    container.nodes.allLists(),
                    doneFlow,
                ) { node, values, lists, allDone ->
                    // "Done" has to mean *done today*, or the section fills with anything ever
                    // completed that still matches the date rule and quietly grows forever.
                    // There is no completed_at column, so updatedAt is the proxy: a task
                    // completed today was, necessarily, touched today.
                    val midnight = todayMidnight()
                    val doneTasks = allDone
                        .filter { it.updatedAt >= midnight }
                        .sortedByDescending { it.updatedAt }
                    // Source-list names only where rows aggregate across lists (smart views).
                    val parentTitles = if (isSmart) {
                        lists.associate { it.id to (it.title?.ifBlank { "Untitled" } ?: "Untitled") }
                    } else emptyMap()
                    val rows = buildRows(
                        tasks, values, defIds.due, defIds.deadline, defIds.priority,
                        priorityColors, parentTitles, hideTodayDue = forceToday,
                    )
                    // Capped: the completed section is a record of the day, not an archive, and
                    // it must never push the open work off the widget.
                    val doneRows = buildRows(
                        doneTasks.take(DONE_LIMIT), emptyList(), defIds.due, defIds.deadline,
                        defIds.priority, priorityColors, parentTitles, hideTodayDue = true,
                    )
                    WidgetData(
                        title = Links.plain(node?.title.orEmpty()).ifBlank { if (forceToday) "Today" else "List" },
                        summary = if (forceToday && doneTasks.isNotEmpty()) {
                            "${rows.size} of ${rows.size + doneTasks.size}"
                        } else summary,
                        items = if (forceToday) groupToday(rows, doneRows)
                                else rows.map { WidgetItem.Task(it) },
                        nodeId = nodeId,
                        isSmart = isSmart,
                    )
                }
            }
        )
    }
}

class TodayWidget : YantraListWidget() {
    override val forceToday: Boolean = true
}

private fun openIntent(context: Context, nodeId: String, isSmart: Boolean): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(WidgetIntents.EXTRA_OPEN_NODE, nodeId)
        putExtra(WidgetIntents.EXTRA_OPEN_SMART, isSmart)
        data = Uri.parse("yantra://open/$nodeId")
    }

private fun quickAddIntent(context: Context, nodeId: String, isSmart: Boolean): Intent =
    Intent(context, QuickAddActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(QuickAddActivity.EXTRA_TARGET_NODE, nodeId)
        putExtra(QuickAddActivity.EXTRA_TARGET_IS_SMART, isSmart)
        // Distinct data URI per widget so PendingIntents don't collide/merge.
        data = Uri.parse("yantra://quickadd/$nodeId")
    }

private fun settingsIntent(context: Context, widgetId: Int, isToday: Boolean): Intent =
    Intent(context, WidgetSettingsActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(WidgetSettingsActivity.EXTRA_WIDGET_ID, widgetId)
        putExtra(WidgetSettingsActivity.EXTRA_IS_TODAY, isToday)
        // Distinct data URI per widget so PendingIntents don't collide/merge.
        data = Uri.parse("yantra://widgetsettings/$widgetId")
    }

@Composable
private fun ListContent(data: WidgetData, opacity: Int, widgetId: Int, isToday: Boolean) {
    val context = LocalContext.current
    val status = yantraStatusColors(context)
    val m = WidgetMetrics.forSize(LocalSize.current)
    // The scrim has to win. A widget sits on whatever wallpaper the user chose, and at ~0.86
    // a busy photo still fought every row for legibility; the 0.94 default plus a hairline edge
    // reads as a pane resting on the wallpaper rather than a stain in it. The floor is 50% —
    // below that no scrim survives a busy wallpaper, so it is not offered. Resolved to a
    // concrete color because alpha can't wrap a ColorProvider.
    val scrim = GlanceTheme.colors.surface.getColor(context)
        .copy(alpha = opacity.coerceIn(ListWidgetDefaults.MIN_OPACITY, 100) / 100f)
    val edge = GlanceTheme.colors.onSurface.getColor(context).copy(alpha = 0.10f)
    // A 1dp ring: Glance has no border, so the outer box's background *is* the border.
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(edge)
            .cornerRadius(20.dp)
            .padding(1.dp),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(scrim)
                .cornerRadius(19.dp)
                .padding(horizontal = m.pad, vertical = m.pad - 3.dp),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    data.title,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = m.header, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .let { mod ->
                            data.nodeId?.let { mod.clickable(actionStartActivity(openIntent(context, it, data.isSmart))) } ?: mod
                        },
                )
                Text(
                    data.summary,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = m.summary),
                    maxLines = 1,
                )
                if (data.nodeId != null) {
                    Spacer(GlanceModifier.width(8.dp))
                    // The add affordance gets a surface of its own, so it reads as a button
                    // rather than a stray glyph floating at the end of the title row.
                    Box(
                        modifier = GlanceModifier
                            .size(m.addButton)
                            .background(GlanceTheme.colors.primary.getColor(context).copy(alpha = 0.15f))
                            .cornerRadius(10.dp)
                            .clickable(
                                actionStartActivity(quickAddIntent(context, data.nodeId, data.isSmart))
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_widget_add),
                            contentDescription = "Add task",
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                            modifier = GlanceModifier.size(m.addButton - 14.dp),
                        )
                    }
                }
                // Settings have to be reachable *from* the widget. A launcher only offers its
                // reconfigure gesture for providers that declare a configure activity, and the
                // Today widget deliberately doesn't (it would force a pointless picker at
                // placement), so without this there was no route to them at all.
                Spacer(GlanceModifier.width(4.dp))
                Box(
                    modifier = GlanceModifier
                        .size(m.addButton)
                        .cornerRadius(10.dp)
                        .clickable(
                            actionStartActivity(settingsIntent(context, widgetId, isToday))
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_more),
                        contentDescription = "Widget settings",
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                        modifier = GlanceModifier.size(m.addButton - 16.dp),
                    )
                }
            }
            if (data.items.isEmpty()) {
                EmptyState(
                    setUp = data.nodeId != null,
                    metrics = m,
                    onAdd = data.nodeId?.let {
                        actionStartActivity(quickAddIntent(context, it, data.isSmart))
                    },
                )
            } else {
                Spacer(GlanceModifier.size(4.dp))
                LazyColumn {
                    data.items.forEach { item ->
                        when (item) {
                            // Headers live in a negative id namespace so they can never collide
                            // with row-id hashes.
                            is WidgetItem.Header -> item(itemId = -1L - data.items.indexOf(item)) {
                                // A rule above each later section, so the groups read as
                                // separated bands rather than one list with bold labels in it.
                                Column {
                                    if (data.items.indexOf(item) > 0) {
                                        Box(
                                            GlanceModifier
                                                .fillMaxWidth()
                                                .height(1.dp)
                                                .padding(top = m.rowGap + 3.dp)
                                                .background(
                                                    GlanceTheme.colors.onSurface.getColor(context)
                                                        .copy(alpha = 0.09f)
                                                )
                                        ) {}
                                    }
                                    Text(
                                        item.label,
                                        style = TextStyle(
                                            color = if (item.urgent) ColorProvider(status.overdue)
                                                    else GlanceTheme.colors.onSurfaceVariant,
                                            fontSize = m.section,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        modifier = GlanceModifier.padding(top = m.rowGap + 4.dp, bottom = 3.dp),
                                    )
                                }
                            }
                            is WidgetItem.Task -> item(itemId = item.row.id.hashCode().toLong()) {
                                TaskRow(item.row, status, m)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * An empty widget is the one state the user sees most on a good day, so it gets composed rather
 * than left as a sentence in the middle of a void: the app's mark, one line, and the way out.
 */
@Composable
private fun EmptyState(setUp: Boolean, metrics: WidgetMetrics, onAdd: androidx.glance.action.Action?) {
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_star),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(metrics.box + 4.dp),
            )
            Spacer(GlanceModifier.size(8.dp))
            Text(
                if (!setUp) "Tap and hold to set up" else "All clear",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = metrics.rowTitle),
            )
            if (onAdd != null) {
                Spacer(GlanceModifier.size(10.dp))
                Text(
                    "Add a task",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = metrics.meta,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = GlanceModifier.clickable(onAdd).padding(horizontal = 13.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TaskRow(row: WidgetRow, status: YantraColors, m: WidgetMetrics) {
    val context = LocalContext.current
    val toggle = actionRunCallback<ToggleDoneAction>(
        actionParametersOf(
            ToggleDoneAction.NodeId to row.id,
            ToggleDoneAction.Done to row.done,
        )
    )
    val priorityTint = row.priorityColor?.let { ColorProvider(Color(it)) }
    // The meta line: the date only when it informs, then the source list. Putting the list name
    // here instead of hard against the right edge stops it competing with the title's baseline,
    // and gives every aggregated row the same two-line shape.
    val metaParts = listOfNotNull(row.dueLabel, row.deadlineLabel, row.listName)
    val overdue = row.dueOverdue || row.deadlineOverdue
    // Top-aligned, and the text column takes its own height. Centering a two-line column against
    // a fixed-height checkbox is what let the title and its date paint over each other when the
    // row had both.
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = m.rowGap),
        verticalAlignment = Alignment.Top,
    ) {
        // The task glyph, in the three states a widget can show. Glance has no Canvas, so these are
        // drawables rather than the drawn path — same geometry, same colour law, no choreography.
        // A done task is a bare bindu and keeps no priority tint: the enclosure is what carried
        // urgency, and it has un-drawn.
        Image(
            provider = ImageProvider(
                when {
                    row.done -> R.drawable.ic_widget_check_done
                    row.inProgress -> R.drawable.ic_widget_check_progress
                    else -> R.drawable.ic_widget_check_idle
                }
            ),
            contentDescription = if (row.done) "Mark not done" else "Mark done",
            // The done and in-progress marks are already coral in the drawable; only the open
            // frame takes a tint, and only from priority.
            colorFilter = if (row.done || row.inProgress) null
            else ColorFilter.tint(priorityTint ?: GlanceTheme.colors.outline),
            modifier = GlanceModifier.size(m.box).clickable(toggle),
        )
        Spacer(GlanceModifier.width(11.dp))
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .wrapContentHeight()
                .clickable(actionStartActivity(openIntent(context, row.id, false))),
        ) {
            Text(
                row.title,
                maxLines = 2,
                style = TextStyle(
                    fontSize = m.rowTitle,
                    fontWeight = if (row.done) FontWeight.Normal else FontWeight.Medium,
                    color = if (row.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    // The app strikes a done title with a coral pen mark; Glance has no canvas to
                    // draw one with, so out here it stays a line. The bindu carries the meaning.
                    textDecoration = if (row.done) TextDecoration.LineThrough else TextDecoration.None,
                ),
            )
            if (metaParts.isNotEmpty() && !row.done) {
                Spacer(GlanceModifier.size(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.hasReminder) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_widget_bell),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(
                                if (overdue) ColorProvider(status.overdue) else GlanceTheme.colors.onSurfaceVariant
                            ),
                            modifier = GlanceModifier.size(11.dp),
                        )
                        Spacer(GlanceModifier.width(4.dp))
                    }
                    // One Text, not a Row of them: Glance has no baseline alignment, so several
                    // sibling Texts on a meta line drift against each other at different sizes.
                    // The date is what needs its own colour, so it leads and keeps its own Text.
                    val dated = row.dueLabel ?: row.deadlineLabel
                    if (dated != null) {
                        Text(
                            dated,
                            maxLines = 1,
                            style = TextStyle(
                                fontSize = m.meta,
                                fontWeight = if (overdue) FontWeight.Medium else FontWeight.Normal,
                                color = if (overdue) ColorProvider(status.overdue)
                                        else GlanceTheme.colors.onSurfaceVariant,
                            ),
                        )
                    }
                    val rest = listOfNotNull(
                        row.deadlineLabel.takeIf { row.dueLabel != null },
                        row.listName,
                    )
                    if (rest.isNotEmpty()) {
                        Text(
                            (if (dated != null) " · " else "") + rest.joinToString(" · "),
                            maxLines = 1,
                            style = TextStyle(
                                fontSize = m.meta,
                                color = GlanceTheme.colors.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    }
}
