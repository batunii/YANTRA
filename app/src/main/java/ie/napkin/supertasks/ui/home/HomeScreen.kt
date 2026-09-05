package ie.napkin.supertasks.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.components.PAGE_MARGIN
import ie.napkin.supertasks.ui.components.ComposedEmpty
import ie.napkin.supertasks.ui.components.PullToSync
import ie.napkin.supertasks.ui.components.NowPlayer
import ie.napkin.supertasks.ui.components.SwitchHereDialog
import ie.napkin.supertasks.ui.components.LocalNow
import androidx.compose.ui.text.input.VisualTransformation
import ie.napkin.supertasks.ui.components.CaptureSuggestions
import ie.napkin.supertasks.ui.components.rememberCaptureHighlight
import ie.napkin.supertasks.ui.components.YantraButton
import ie.napkin.supertasks.ui.components.Compass
import ie.napkin.supertasks.ui.components.ConfirmDialog
import ie.napkin.supertasks.ui.components.NavCircleSurface
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.SelectChip
import ie.napkin.supertasks.ui.components.TextFieldDialog
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.theme.MonoBanner
import ie.napkin.supertasks.data.workspace.WorkspaceEntry
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraDisplay
import ie.napkin.supertasks.ui.theme.YantraMono
import ie.napkin.supertasks.ui.theme.YantraText
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import ie.napkin.supertasks.ui.smart.SmartListBuilderSheet
import ie.napkin.supertasks.ui.components.GearMark
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

private enum class CreateType(val label: String, val placeholder: String, val action: String) {
    TASK("Task", "New task", "Create task"),
    LIST("List", "New list", "Create list"),
    GROUP("Group", "New group", "Create group"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeViewModel = viewModel { HomeViewModel(container()) }
    val allLists by vm.allLists.collectAsStateWithLifecycle()
    val nodes by vm.topLevel.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val timer by vm.timerState.collectAsStateWithLifecycle()
    val defs by vm.defs.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val assignable by vm.assignable.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    // Non-null while the smart-list builder is open, seeded with the name typed in step 1.
    var customSmartName by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<NodeEntity?>(null) }
    var deleting by remember { mutableStateOf<NodeEntity?>(null) }
    var movingNode by remember { mutableStateOf<NodeEntity?>(null) }

    val groups = nodes.filter { it.type == NodeType.GROUP }
    val ungrouped = allLists.filter { it.parentId == null }
    val ungroupedSmart = ungrouped.filter { it.type == NodeType.SMART_LIST }
    val ungroupedLists = ungrouped.filter { it.type == NodeType.LIST }
    val byGroup = allLists.filter { it.parentId != null }.groupBy { it.parentId!! }
    val allRegularLists = allLists.filter { it.type == NodeType.LIST }
    val y = Yantra.colors

    // Lists that OWN their tasks only. A smart list re-counts tasks that already live in one of
    // these, so summing every entry would tally the same task twice now that smart lists carry
    // counts too — the header would read 17 open where 13 tasks exist.
    val openCount = allRegularLists
        .mapNotNull { counts[it.id] }
        .sumOf { (it.total - it.doneCount).coerceAtLeast(0) }
    val listCount = allRegularLists.size

    val renderRow: @Composable (NodeEntity) -> Unit = { node ->
        val smart = node.type == NodeType.SMART_LIST
        val c = counts[node.id]
        HomeRow(
            node = node,
            // Every list says where it stands. "Updates live" described the machinery instead —
            // true of a smart list, but it is not what you came to the row to find out, and it
            // left the one genuinely useful number missing from half the lists on the screen.
            subtitle = if (c == null || c.total == 0) "Empty" else "${c.doneCount} of ${c.total} done",
            smart = smart,
            fraction = if (c == null || c.total == 0) 0f else c.doneCount.toFloat() / c.total,
            showCompass = (c?.total ?: 0) > 0,
            grouped = node.parentId != null,
            onClick = { nav.navigate(if (smart) Routes.smart(node.id) else Routes.node(node.id)) },
            onRename = { renaming = node },
            onDelete = { deleting = node },
            onMove = { movingNode = node },
        )
    }

    Scaffold(
        containerColor = y.page,
        bottomBar = {
            Column {
                // The same bar as every other screen, above the strip rather than replacing it —
                // Home's capture is already a key in that strip (the cog), so there is nothing here
                // for the now bar to take.
                val stack by LocalNow.current.collectAsStateWithLifecycle()
                NowPlayer(
                    stack = stack,
                    onOpen = { n ->
                        nav.navigate(
                            if (n.hasSession) Routes.FOCUS_CURRENT else Routes.focus(n.nodeId)
                        )
                    },
                    onToggleClock = { n -> vm.toggleClock(n.nodeId, n.title) },
                )
                val timingOccupied by vm.timing.occupied.collectAsStateWithLifecycle()
                timingOccupied?.let {
                    SwitchHereDialog(
                        runningTitle = it.byTitle,
                        onConfirm = { vm.timing.confirm() },
                        onDismiss = { vm.timing.dismiss() },
                    )
                }
                HomeTabBar(
                    onCog = { showCreate = true },
                    onStats = { nav.navigate(Routes.STATS) },
                )
            }
        },
    ) { padding ->
        PullToSync(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
            ) {
                item(key = "greet") {
                    Greeting(
                        openCount = openCount,
                        listCount = listCount,
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }

                // The session used to be reported twice on this screen — a card up here and,
                // now, the bar at the bottom. One running task, one treatment: the card goes, and
                // what it alone could show (a commitment's countdown and how much of it is spent)
                // stays on the focus screen, one tap away through the bar.

                // The app has an empty state, with its own mark and an action, and until now used it
                // on one screen out of five — not this one, which is the first screen anyone sees.
                if (ungroupedSmart.isEmpty() && ungroupedLists.isEmpty() && groups.isEmpty()) {
                    item(key = "empty") {
                        ComposedEmpty(
                            "Nothing here yet",
                            action = "Make a list",
                            onAction = { showCreate = true },
                        )
                    }
                }
                if (ungroupedSmart.isNotEmpty()) {
                    item(key = "smart-header") { SectionHeader("Pinned") }
                    items(ungroupedSmart, key = { it.id }) { renderRow(it) }
                }
                if (ungroupedLists.isNotEmpty()) {
                    item(key = "lists-header") { SectionHeader("Lists") }
                    items(ungroupedLists, key = { it.id }) { renderRow(it) }
                }
                groups.forEach { group ->
                    item(key = "g-${group.id}") {
                        GroupBanner(
                            title = group.title.orEmpty().ifBlank { "Untitled group" },
                            count = byGroup[group.id]?.size ?: 0,
                            onRename = { renaming = group },
                            onDelete = { deleting = group },
                        )
                    }
                    items(byGroup[group.id].orEmpty(), key = { it.id }) { renderRow(it) }
                }

                item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showCreate) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showCreate = false },
            sheetState = sheetState,
            containerColor = y.cardBg,
        ) {
            CreatePanel(
                allLabels = labels,
                listNames = allRegularLists.mapNotNull { it.title },
                workspaces = vm.workspaces,
                defaultWorkspaceId = vm.defaultWorkspaceId,
                people = assignable,
                findTasks = { q -> vm.linkTargets(q) },
                resolveLinks = { t -> vm.linkIdsFor(t) },
                onCreate = { type, name, makeSmart, wsId ->
                    when (type) {
                        CreateType.TASK -> vm.quickAddTask(name) { id -> nav.navigate(Routes.node(id)) }
                        CreateType.LIST -> {
                            if (makeSmart) customSmartName = name
                            else vm.createListThen(name, wsId) { id -> nav.navigate(Routes.node(id)) }
                        }
                        CreateType.GROUP -> vm.createGroup(name, wsId)
                    }
                    showCreate = false
                },
            )
        }
    }

    customSmartName?.let { seedName ->
        SmartListBuilderSheet(
            initialName = seedName,
            defs = defs,
            labels = labels,
            lists = allRegularLists,
            workspaces = vm.workspaces,
            onCreateLabel = vm::createLabel,
            onDismiss = { customSmartName = null },
            onCreate = { name, filter, sort, homeId ->
                vm.createSmartList(name, filter, sort, homeId)
                customSmartName = null
            },
        )
    }
    if (showNewGroup) {
        TextFieldDialog(
            title = "New group",
            confirmLabel = "Create",
            placeholder = "Group name — e.g. Work",
            onDismiss = { showNewGroup = false },
            // No picker here — a bare text dialog. It lands in the default workspace, and the
            // create sheet is where the choice is offered.
            onConfirm = { vm.createGroup(it, vm.defaultWorkspaceId); showNewGroup = false },
        )
    }
    movingNode?.let { node ->
        MoveToGroupDialog(
            groups = groups,
            currentGroupId = node.parentId,
            onDismiss = { movingNode = null },
            onPick = { groupId -> vm.moveToGroup(node.id, groupId); movingNode = null },
            onNewGroup = { movingNode = null; showNewGroup = true },
        )
    }
    renaming?.let { node ->
        TextFieldDialog(
            title = "Rename",
            confirmLabel = "Save",
            initial = node.title.orEmpty(),
            onDismiss = { renaming = null },
            onConfirm = { vm.rename(node.id, it); renaming = null },
        )
    }
    deleting?.let { node ->
        ConfirmDialog(
            title = "Delete \"${node.title.orEmpty()}\"?",
            body = when (node.type) {
                NodeType.SMART_LIST -> "The smart list view is removed. Tasks it shows live elsewhere and aren't deleted."
                NodeType.GROUP -> "The group is removed. Its lists move back to the top level — nothing is deleted."
                else -> "The list and everything inside it will be deleted."
            },
            onDismiss = { deleting = null },
            onConfirm = {
                if (node.type == NodeType.GROUP) vm.deleteGroup(node.id) else vm.delete(node.id)
                deleting = null
            },
        )
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("EEEE · d MMM")

@Composable
private fun Greeting(openCount: Int, listCount: Int, onSettings: () -> Unit) {
    val y = Yantra.colors
    val greeting = remember {
        when (LocalTime.now().hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Late one"
        }
    }
    val date = remember { LocalDate.now().format(dateFmt) }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(date, fontFamily = YantraText, fontSize = 12.5.sp, fontWeight = FontWeight.W500, color = y.textMuted)
            Text(greeting, style = MaterialTheme.typography.headlineSmall, color = y.textPrimary, modifier = Modifier.padding(top = 3.dp))
            Text(
                if (openCount == 0) "Nothing open. Breathe." else "$openCount open across $listCount ${if (listCount == 1) "list" else "lists"}",
                fontFamily = YantraText, fontSize = 12.5.sp, fontWeight = FontWeight.W500, color = y.textMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        NavCircleSurface(onClick = onSettings, size = 40.dp) { SettingsGlyph() }
    }
}

@Composable
private fun SectionHeader(text: String) {
    SectionLabel(text, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

@Composable
private fun HomeRow(
    node: NodeEntity,
    subtitle: String,
    smart: Boolean,
    fraction: Float,
    showCompass: Boolean,
    grouped: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val y = Yantra.colors
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).background(
                    if (smart) Color.Transparent else y.accent.copy(alpha = 0.12f),
                    RoundedCornerShape(10.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (smart) {
                    Icon(Icons.Default.AutoAwesome, null, tint = y.accent, modifier = Modifier.size(19.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.List, null, tint = y.accent, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.title.orEmpty().ifBlank { "Untitled" },
                    fontFamily = YantraDisplay, fontSize = 15.sp, fontWeight = FontWeight.W500,
                    color = y.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = y.textMuted)
            }
            if (showCompass) {
                Compass(fraction = fraction, size = 30.dp)
                Spacer(Modifier.width(4.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.MoreVert, "Options", tint = y.textDim, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(
                        text = { Text(if (grouped) "Move to another group…" else "Move to group…") },
                        onClick = { menu = false; onMove() },
                    )
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        HorizontalDivider(color = y.hairline, thickness = 1.dp)
    }
}

@Composable
private fun GroupBanner(title: String, count: Int, onRename: () -> Unit, onDelete: () -> Unit) {
    val y = Yantra.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, fontFamily = YantraDisplay, fontSize = 14.sp, fontWeight = FontWeight.W700,
            color = y.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text("$count", fontFamily = YantraMono, fontSize = 10.sp, color = y.textDim)
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.MoreVert, "Group options", tint = y.textDim, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete group") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreatePanel(
    allLabels: List<ie.napkin.supertasks.data.db.LabelEntity>,
    listNames: List<String>,
    /** Every workspace on this device. One (or none) and the choice is not offered. */
    workspaces: List<WorkspaceEntry>,
    defaultWorkspaceId: String,
    /** Who `@` may name. Home captures into the Inbox, so this is Personal's roster. */
    people: List<String> = emptyList(),
    findTasks: suspend (String) -> List<ie.napkin.supertasks.data.db.NodeEntity> = { emptyList() },
    resolveLinks: suspend (String) -> Map<String, String> = { emptyMap() },
    onCreate: (CreateType, String, Boolean, String) -> Unit,
) {
    val y = Yantra.colors
    var type by remember { mutableStateOf(CreateType.TASK) }
    var text by remember { mutableStateOf(TextFieldValue()) }
    var makeSmart by remember { mutableStateOf(false) }
    var wsId by remember { mutableStateOf(defaultWorkspaceId) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Only a task is parsed, so only a task pays for these lookups.
    val linkDraft = if (type == CreateType.TASK) {
        ie.napkin.supertasks.data.format.Links.draft(text.text, text.selection.start)?.second
    } else null
    var taskMatches by remember { mutableStateOf<List<ie.napkin.supertasks.data.db.NodeEntity>>(emptyList()) }
    LaunchedEffect(linkDraft) { taskMatches = linkDraft?.let { findTasks(it) }.orEmpty() }
    var linkIds by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(text.text, type) {
        linkIds = if (type == CreateType.TASK) resolveLinks(text.text) else emptyMap()
    }

    val valid = text.text.isNotBlank()
    val actionLabel = if (type == CreateType.LIST && makeSmart) "Continue" else type.action

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = y.textPrimary),
            cursorBrush = SolidColor(y.accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (valid) onCreate(type, text.text.trim(), makeSmart, wsId)
            }),
            // Only for a task. A list or a group is named literally — its title is whatever you
            // typed — so tinting part of it would promise a reading that is never applied.
            visualTransformation = if (type == CreateType.TASK) {
                rememberCaptureHighlight(allLabels, listNames, people, linkIds)
            } else {
                VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRequester(focus),
            decorationBox = { inner ->
                if (text.text.isEmpty()) Text(type.placeholder, style = MaterialTheme.typography.headlineSmall, color = y.textMuted.copy(alpha = 0.7f))
                inner()
            },
        )
        // Only while a `~` is being typed, and only for a task — a list is named literally, so
        // there is no destination to offer it.
        if (type == CreateType.TASK) {
            CaptureSuggestions(
                text = text.text,
                caret = text.selection.start,
                lists = listNames,
                people = people,
                tasks = taskMatches,
                modifier = Modifier.padding(top = 4.dp),
                onPick = { text = it },
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CreateType.entries.forEach { t ->
                SelectChip(t.label, selected = t == type, onClick = { type = t }, modifier = Modifier.weight(1f))
            }
        }
        // Only for the things that have no parent to inherit from. A task goes to the Inbox and a
        // block belongs to its page; a list or a group is the one create where the repo is a real
        // choice — and only worth asking when there is more than one answer.
        if (type != CreateType.TASK && workspaces.size > 1) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(if (type == CreateType.GROUP) "Group lives in" else "List lives in", color = y.textMuted)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    workspaces.forEach { w ->
                        SelectChip(w.name.ifBlank { "Untitled" }, selected = w.id == wsId) { wsId = w.id }
                    }
                }
            }
        }
        if (type == CreateType.LIST) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clickable { makeSmart = !makeSmart },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Make this a smart list", color = y.textPrimary, fontFamily = YantraText, fontWeight = FontWeight.W600, fontSize = 14.sp)
                    Text("Auto-updates from conditions you set, instead of a fixed set of tasks", color = y.textMuted, fontSize = 11.5.sp)
                }
                Switch(checked = makeSmart, onCheckedChange = { makeSmart = it })
            }
        }
        Spacer(Modifier.height(16.dp))
        YantraButton(
            label = actionLabel,
            modifier = Modifier.fillMaxWidth(),
            enabled = valid,
            onClick = { onCreate(type, text.text.trim(), makeSmart, wsId) },
        )
    }
}


@Composable
private fun HomeTabBar(onCog: () -> Unit, onStats: () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier.fillMaxWidth().background(y.page).navigationBarsPadding()
            .padding(start = 40.dp, end = 40.dp, top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { HomeGlyph(active = true) }
        // the cog — quick create
        Box(
            Modifier.size(54.dp)
                .background(y.accentFill, RoundedCornerShape(17.dp))
                .border(1.dp, y.accentBorder, RoundedCornerShape(17.dp))
                .clickable(onClick = onCog),
            contentAlignment = Alignment.Center,
        ) { GearMark(Modifier.size(30.dp), tint = y.accent) }
        Box(Modifier.size(44.dp).clickable(onClick = onStats), contentAlignment = Alignment.Center) { StatsGlyph() }
    }
}

@Composable
private fun HomeGlyph(active: Boolean) {
    val y = Yantra.colors
    val tint = if (active) y.textPrimary else y.textDim
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.16f, w * 0.44f); lineTo(w * 0.5f, w * 0.16f); lineTo(w * 0.84f, w * 0.44f)
            lineTo(w * 0.84f, w * 0.84f); lineTo(w * 0.16f, w * 0.84f); close()
        }
        drawPath(p, color = tint, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
    }
}

@Composable
private fun SettingsGlyph() {
    val y = Yantra.colors
    // three sliders — a settings mark distinct from the create cog
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        fun line(cy: Float) = drawLine(y.textSecondary, Offset(0f, cy), Offset(w, cy), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        line(w * 0.22f); line(w * 0.5f); line(w * 0.78f)
        fun knob(cx: Float, cy: Float) { drawCircle(y.page, radius = w * 0.13f, center = Offset(cx, cy)); drawCircle(y.accent, radius = w * 0.11f, center = Offset(cx, cy)) }
        knob(w * 0.68f, w * 0.22f); knob(w * 0.34f, w * 0.5f); knob(w * 0.72f, w * 0.78f)
    }
}

@Composable
private fun StatsGlyph() {
    val y = Yantra.colors
    Canvas(Modifier.size(20.dp)) {
        val w = 3.6f / 20f * size.width
        val unit = size.height / 18f
        fun bar(x: Float, h: Float, c: Color) {
            drawRoundRect(
                color = c,
                topLeft = Offset(x / 20f * size.width, size.height - h * unit),
                size = Size(w, h * unit),
                cornerRadius = CornerRadius(1.2f * unit, 1.2f * unit),
            )
        }
        bar(2f, 8f, y.textDim); bar(8.2f, 12f, y.textDim); bar(14.4f, 16f, y.accent)
    }
}

@Composable
private fun MoveToGroupDialog(
    groups: List<NodeEntity>,
    currentGroupId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onNewGroup: () -> Unit,
) {
    val y = Yantra.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to group") },
        text = {
            Column {
                MoveRow("Top level (no group)", selected = currentGroupId == null) { onPick(null) }
                groups.forEach { g ->
                    MoveRow(g.title.orEmpty().ifBlank { "Untitled group" }, selected = currentGroupId == g.id) { onPick(g.id) }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onNewGroup).padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, null, tint = y.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New group…", color = y.accentText, fontWeight = FontWeight.W700)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).background(
                if (selected) y.accent else Color.Transparent,
                RoundedCornerShape(5.dp),
            ).border(2.dp, if (selected) y.accent else y.checkOutline, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, null, tint = y.onAccent, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = y.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

