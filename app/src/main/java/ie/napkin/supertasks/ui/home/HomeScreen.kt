package ie.napkin.supertasks.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.components.Compass
import ie.napkin.supertasks.ui.components.ConfirmDialog
import ie.napkin.supertasks.ui.components.NavCircleSurface
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.TextFieldDialog
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.theme.MonoBanner
import androidx.compose.foundation.layout.offset
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraDisplay
import ie.napkin.supertasks.ui.theme.YantraMono
import ie.napkin.supertasks.ui.theme.YantraText
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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
            HomeTabBar(
                onCog = { showCreate = true },
                onStats = { nav.navigate(Routes.STATS) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
        ) {
            item(key = "greet") {
                Greeting(
                    openCount = openCount,
                    listCount = listCount,
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }

            if (timer != null) {
                item(key = "timer") {
                    ActiveTimerCard(
                        title = timer!!.nodeTitle,
                        remainingSecs = timer!!.remainingSecs,
                        plannedSecs = timer!!.plannedSecs,
                        onClick = { nav.navigate(Routes.FOCUS_CURRENT) },
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

    if (showCreate) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showCreate = false },
            sheetState = sheetState,
            containerColor = y.cardBg,
        ) {
            CreatePanel(
                onCreate = { type, name, makeSmart ->
                    when (type) {
                        CreateType.TASK -> vm.quickAddTask(name) { id -> nav.navigate(Routes.node(id)) }
                        CreateType.LIST -> {
                            if (makeSmart) customSmartName = name
                            else vm.createListThen(name) { id -> nav.navigate(Routes.node(id)) }
                        }
                        CreateType.GROUP -> vm.createGroup(name)
                    }
                    showCreate = false
                },
            )
        }
    }

    customSmartName?.let { seedName ->
        ie.napkin.supertasks.ui.smart.SmartListBuilderSheet(
            initialName = seedName,
            defs = defs,
            labels = labels,
            lists = allRegularLists,
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
            onConfirm = { vm.createGroup(it); showNewGroup = false },
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
                    if (smart) androidx.compose.ui.graphics.Color.Transparent else y.accent.copy(alpha = 0.12f),
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

@Composable
private fun CreatePanel(onCreate: (CreateType, String, Boolean) -> Unit) {
    val y = Yantra.colors
    var type by remember { mutableStateOf(CreateType.TASK) }
    var text by remember { mutableStateOf("") }
    var makeSmart by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val valid = text.isNotBlank()
    val actionLabel = if (type == CreateType.LIST && makeSmart) "Continue" else type.action

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = y.textPrimary),
            cursorBrush = SolidColor(y.accent),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                if (valid) onCreate(type, text.trim(), makeSmart)
            }),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRequester(focus),
            decorationBox = { inner ->
                if (text.isEmpty()) Text(type.placeholder, style = MaterialTheme.typography.headlineSmall, color = y.textMuted.copy(alpha = 0.7f))
                inner()
            },
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CreateType.entries.forEach { t ->
                TypeChip(t.label, selected = t == type, modifier = Modifier.weight(1f)) { type = t }
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
                androidx.compose.material3.Switch(checked = makeSmart, onCheckedChange = { makeSmart = it })
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().background(y.accent, RoundedCornerShape(13.dp))
                .clickable(enabled = valid) { onCreate(type, text.trim(), makeSmart) }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(actionLabel, color = y.onAccent, fontFamily = YantraText, fontWeight = FontWeight.W700, fontSize = 15.sp)
        }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier.background(if (selected) y.accentFill else y.neutralChipBg, shape)
            .border(1.dp, if (selected) y.accentBorder else androidx.compose.ui.graphics.Color.Transparent, shape)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) y.accentText else y.textSecondary, fontFamily = YantraText, fontWeight = FontWeight.W700, fontSize = 12.5.sp)
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
        ) { ie.napkin.supertasks.ui.components.GearMark(Modifier.size(30.dp), tint = y.accent) }
        Box(Modifier.size(44.dp).clickable(onClick = onStats), contentAlignment = Alignment.Center) { StatsGlyph() }
    }
}

@Composable
private fun HomeGlyph(active: Boolean) {
    val y = Yantra.colors
    val tint = if (active) y.textPrimary else y.textDim
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.16f, w * 0.44f); lineTo(w * 0.5f, w * 0.16f); lineTo(w * 0.84f, w * 0.44f)
            lineTo(w * 0.84f, w * 0.84f); lineTo(w * 0.16f, w * 0.84f); close()
        }
        drawPath(p, color = tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.08f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun SettingsGlyph() {
    val y = Yantra.colors
    // three sliders — a settings mark distinct from the create cog
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val w = size.width
        fun line(cy: Float) = drawLine(y.textSecondary, androidx.compose.ui.geometry.Offset(0f, cy), androidx.compose.ui.geometry.Offset(w, cy), strokeWidth = w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        line(w * 0.22f); line(w * 0.5f); line(w * 0.78f)
        fun knob(cx: Float, cy: Float) { drawCircle(y.page, radius = w * 0.13f, center = androidx.compose.ui.geometry.Offset(cx, cy)); drawCircle(y.accent, radius = w * 0.11f, center = androidx.compose.ui.geometry.Offset(cx, cy)) }
        knob(w * 0.68f, w * 0.22f); knob(w * 0.34f, w * 0.5f); knob(w * 0.72f, w * 0.78f)
    }
}

@Composable
private fun StatsGlyph() {
    val y = Yantra.colors
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = 3.6f / 20f * size.width
        val unit = size.height / 18f
        fun bar(x: Float, h: Float, c: androidx.compose.ui.graphics.Color) {
            drawRoundRect(
                color = c,
                topLeft = androidx.compose.ui.geometry.Offset(x / 20f * size.width, size.height - h * unit),
                size = androidx.compose.ui.geometry.Size(w, h * unit),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * unit, 1.2f * unit),
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
    androidx.compose.material3.AlertDialog(
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
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
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
                if (selected) y.accent else androidx.compose.ui.graphics.Color.Transparent,
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

@Composable
private fun ActiveTimerCard(title: String, remainingSecs: Int, plannedSecs: Int, onClick: () -> Unit) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp)
            .background(y.bandTimer, shape)
            .border(1.dp, y.accent.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(y.accent.copy(alpha = 0.16f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Timer, null, tint = y.accent, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("FOCUSING", fontFamily = YantraMono, fontSize = 10.sp, fontWeight = FontWeight.W700, letterSpacing = 1.6.sp, color = y.accentEyebrow)
                Text(title, fontFamily = YantraDisplay, fontSize = 15.sp, fontWeight = FontWeight.W700, color = y.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text("%d:%02d".format(remainingSecs / 60, remainingSecs % 60), style = MonoBanner, color = y.accentText)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).background(y.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(3.dp))) {
            val frac = if (plannedSecs == 0) 0f else (plannedSecs - remainingSecs).toFloat() / plannedSecs
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(5.dp).background(y.accent, RoundedCornerShape(3.dp)))
        }
    }
}
