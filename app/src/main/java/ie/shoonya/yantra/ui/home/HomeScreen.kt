package ie.shoonya.yantra.ui.home

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
import ie.shoonya.yantra.data.db.NodeEntity
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.ui.Routes
import ie.shoonya.yantra.ui.components.PAGE_MARGIN
import ie.shoonya.yantra.ui.components.ComposedEmpty
import ie.shoonya.yantra.ui.components.PullToSync
import ie.shoonya.yantra.ui.components.NowPlayer
import ie.shoonya.yantra.ui.components.SwitchHereDialog
import ie.shoonya.yantra.ui.components.LocalNow
import androidx.compose.ui.text.input.VisualTransformation
import ie.shoonya.yantra.ui.components.CaptureSuggestions
import ie.shoonya.yantra.ui.components.rememberCaptureHighlight
import ie.shoonya.yantra.ui.components.YantraButton
import ie.shoonya.yantra.ui.components.ConfirmDialog
import ie.shoonya.yantra.ui.components.NavCircle
import ie.shoonya.yantra.ui.components.NavCircleSurface
import ie.shoonya.yantra.ui.components.SectionLabel
import ie.shoonya.yantra.ui.components.SelectChip
import ie.shoonya.yantra.ui.components.TextFieldDialog
import ie.shoonya.yantra.ui.container
import ie.shoonya.yantra.ui.theme.MonoBanner
import ie.shoonya.yantra.data.workspace.WorkspaceEntry
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraDisplay
import ie.shoonya.yantra.ui.theme.YantraMono
import ie.shoonya.yantra.ui.theme.YantraText
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import ie.shoonya.yantra.ui.smart.SmartListBuilderSheet
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
import ie.shoonya.yantra.ui.components.YantraMark
import ie.shoonya.yantra.ui.components.YantraIcon
import androidx.compose.foundation.combinedClickable
import java.time.LocalDateTime
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import ie.shoonya.yantra.ui.components.YantraIcons
import ie.shoonya.yantra.data.label.LabelPalette
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import ie.shoonya.yantra.ui.theme.YantraType
import ie.shoonya.yantra.ui.theme.YantraRadius

private enum class CreateType(
    val label: String,
    val placeholder: String,
    val action: String,
    /** Its mark — ICONS.md §1. The chips here were words where every other chip bar is marked. */
    val mark: YantraMark,
) {
    TASK("Task", "New task", "Create task", YantraMark.Task),
    LIST("List", "New list", "Create list", YantraMark.List),
    GROUP("Group", "New group", "Create group", YantraMark.Group),
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
    // The list whose colour is being chosen — the colour law, as remade.
    var colouring by remember { mutableStateOf<NodeEntity?>(null) }
    val booked by vm.bookedMinutes.collectAsStateWithLifecycle()
    val next by vm.nextToday.collectAsStateWithLifecycle()
    val live = timer

    // Which repository each list came from, as a word and a hue — the one resolver in AppContainer,
    // so Home does not derive a colour of its own. Both maps are empty while a single workspace is
    // open, and then the row says nothing about a distinction there is nothing to distinguish.
    val app = ie.shoonya.yantra.ui.appContainer()
    val spaceNames = remember { app.workspaceNames() }
    val spaceHues = remember { app.workspaceColours() }
    // One section per open repository, in registry order (the local one first), or a single
    // untitled-by-repo section when there is only one and the split would say nothing. A null id
    // means "everything", which is also the safety net for a list whose repository has since been
    // closed — it lands in a section rather than vanishing off the screen.
    val sections: List<Pair<String?, String>> = remember(spaceNames, allLists) {
        if (spaceNames.size < 2) listOf(null to "Lists")
        else {
            val known = spaceNames.keys
            val strays = allLists.map { it.workspaceId }.filterNot { it in known }.distinct()
            spaceNames.map { (id, name) -> id as String? to name } + strays.map { it as String? to "Workspace" }
        }
    }

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
            onColour = { colouring = node },
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
                    onCreate = { showCreate = true },
                    onStats = { nav.navigate(Routes.STATS) },
                    onCalendar = { nav.navigate(Routes.CALENDAR) },
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
                        bookedMinutes = booked,
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }

                // NEXT — HOME_UI.md §6. Above PINNED, today only, gone when there is nothing left.
                //
                // This gives Home the symmetry it lacked: NEXT at the top is what is coming, the
                // now player at the bottom is what you are on. Both contextual, both vanish empty.
                next?.let { event ->
                    item(key = "next-header") { SectionHeader("Next") }
                    item(key = "next-row") {
                        NextRow(
                            event = event,
                            // Neutral while a session runs — HOME_UI.md §7. Two accented things at
                            // opposite ends of the screen signal two kinds of urgency at once, and
                            // the one you are *in* should win. The row stays, because what is
                            // coming is exactly what you need while overrunning.
                            accented = live == null,
                            onClick = { nav.navigate(Routes.node(event.event.nodeId)) },
                        )
                    }
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
                // Lists under the repository they belong to, and groups nested inside it.
                //
                // Said once instead of on every row. The workspace *replaces* the "Lists" heading
                // rather than sitting above it, so the screen gains a fact and no depth: it was
                // LISTS → group → row and it is PERSONAL → group → row. This is the shape every
                // app with more than one account converges on — Todoist's team workspaces, Notion's
                // teamspaces, an account section in Notes and in Mail — and it holds here for the
                // reason it holds there: a list belongs to exactly one repository, so the grouping
                // is a fact about the data rather than a view someone chose.
                //
                // **Pinned is deliberately not grouped.** A smart list's rule spans every open repo
                // unless it names one, so it has no repository to sit under, and heading it with a
                // repo name would be a claim the rule contradicts. It goes on top, ungrouped, which
                // is where All Inboxes sits in Mail and All Notes in Notes.
                //
                // With a single repository open there is nothing to tell apart, so it goes back to
                // being one section called "Lists".
                sections.forEach { (id, name) ->
                    val loose = ungroupedLists.filter { id == null || it.workspaceId == id }
                    val mine = groups.filter { id == null || it.workspaceId == id }
                    if (loose.isEmpty() && mine.isEmpty()) return@forEach
                    item(key = "ws-$id") {
                        SectionHeader(
                            name,
                            // The heading is the legend the spine is read against — DESIGN.md §4.7.
                            // A hue is a glance and its name is right here, once.
                            ink = LabelPalette.byName(spaceHues[id])
                                ?.let { Color(LabelPalette.display(it.light, y.isDark)) },
                        )
                    }
                    items(loose, key = { it.id }) { renderRow(it) }
                    mine.forEach { group ->
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
    colouring?.let { node ->
        ListColourDialog(
            current = node.color,
            onDismiss = { colouring = null },
            onPick = { name -> vm.setListColor(node.id, name); colouring = null },
        )
    }

    movingNode?.let { node ->
        MoveToGroupDialog(
            // Only groups from this list's own repository — the rule Todoist arrived at for the
            // same shape (a folder is scoped to one workspace, and cannot span two).
            //
            // It offered every group before, and `moveToGroup` only reparents: it does not move a
            // node between repositories. So a Personal list could be given a v2-tasks parent, and
            // the parent id it then carried does not exist in Personal's files — the group is
            // unresolvable on any device that has not added v2-tasks. It was a broken write before
            // Home grouped by repository; grouping is what makes it visible.
            groups = groups.filter { it.workspaceId == node.workspaceId },
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

/**
 * Choosing the colour a list wears.
 *
 * The same closed strip a label uses, and deliberately the same one: two palettes would mean two
 * vocabularies for the one idea, and the swatches are curated so a mark stays legible on warm paper
 * and on dark. "None" is first and is not a colour — it is how a list goes back to frame ink, which
 * has to be as easy to reach as any hue or the screen fills up with colour nobody chose.
 */
@Composable
private fun ListColourDialog(
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val y = Yantra.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour") },
        text = {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Frame ink, shown as a swatch so "no colour" is a choice on the same row as the
                // colours rather than a link underneath them.
                ColourDot(
                    colour = y.checkOutline,
                    selected = current == null,
                    onClick = { onPick(null) },
                )
                LabelPalette.swatches.forEach { swatch ->
                    ColourDot(
                        colour = Color(LabelPalette.display(swatch.light, y.isDark)),
                        selected = current.equals(swatch.name, ignoreCase = true),
                        onClick = { onPick(swatch.name) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ColourDot(colour: Color, selected: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            // The ring says which one is on. It is drawn outside the fill rather than over it, so
            // the swatch is still the colour you are judging.
            .border(if (selected) 2.dp else 0.dp, if (selected) y.textPrimary else Color.Transparent, CircleShape)
            .padding(if (selected) 4.dp else 0.dp)
            .clip(CircleShape)
            .background(colour)
            .clickable(onClick = onClick),
    )
}

/**
 * The next thing today — HOME_UI.md §6.
 *
 * A row rather than a line in the band, because At-a-Glance-style information wants to be tappable
 * and DESIGN.md §6 says nothing in the band is. As a row it opens the event and sits in the same
 * grammar as PINNED and LISTS.
 */
@Composable
private fun NextRow(
    event: ie.shoonya.yantra.data.db.EventWithTitle,
    accented: Boolean,
    onClick: () -> Unit,
) {
    val y = Yantra.colors
    val ink = if (accented) y.accent else y.checkOutline
    val start = runCatching { LocalDateTime.parse(event.event.startLocal) }.getOrNull()
    val end = runCatching { LocalDateTime.parse(event.event.endLocal) }.getOrNull()
    val minutesAway = start?.let {
        java.time.Duration.between(LocalDateTime.now(), it).toMinutes()
    } ?: 0L
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                YantraIcon(YantraMark.Calendar, tint = ink)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.title.orEmpty().ifBlank { "Untitled" },
                    fontFamily = YantraDisplay, fontSize = YantraType.row, fontWeight = FontWeight.W500,
                    color = y.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        if (start != null && end != null) {
                            append(start.format(CLOCK_FMT))
                            append("–")
                            append(end.format(CLOCK_FMT))
                        }
                        // How long until it starts, inside the same window that promotes a Join
                        // control. Further out than that it is noise: you are not about to leave.
                        if (minutesAway in 1..MINUTES_BEFORE) append("  ·  in ${minutesAway}m")
                        else if (minutesAway <= 0L) append("  ·  now")
                    },
                    fontFamily = YantraMono, fontSize = YantraType.caption,
                    color = if (accented) y.accent else y.textMuted,
                )
            }
        }
        HorizontalDivider(color = y.hairline, thickness = 1.dp)
    }
}

/** The window inside which "in 12m" is worth saying — the same one that promotes a Join control. */
private const val MINUTES_BEFORE = 30L

private val CLOCK_FMT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/** "2h 20m", "45m" — how much of the day is already spoken for. */
private fun bookedWords(minutes: Int): String =
    if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m".removeSuffix(" 0m")

@Composable
private fun Greeting(openCount: Int, bookedMinutes: Int, onSettings: () -> Unit) {
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
            Text(date, fontFamily = YantraText, fontSize = YantraType.meta, fontWeight = FontWeight.W500, color = y.textMuted)
            Text(greeting, style = MaterialTheme.typography.headlineSmall, color = y.textPrimary, modifier = Modifier.padding(top = 3.dp))
            // What the day costs, not how it is filed — HOME_UI.md §5.
            //
            // "across 2 lists" told you nothing you could not get by looking down the screen.
            // Booked hours is the one figure Home cannot otherwise show, and the only one that
            // makes tasks and events commensurable — which is this app's actual argument about
            // time. Numerals in mono, per the type rule.
            Text(
                buildAnnotatedString {
                    if (openCount == 0 && bookedMinutes == 0) {
                        append("Nothing open. Breathe.")
                    } else {
                        withStyle(SpanStyle(fontFamily = YantraMono)) { append("$openCount") }
                        append(" open")
                        if (bookedMinutes > 0) {
                            append(" · ")
                            withStyle(SpanStyle(fontFamily = YantraMono)) {
                                append(bookedWords(bookedMinutes))
                            }
                            append(" booked")
                        }
                    }
                },
                fontFamily = YantraText, fontSize = YantraType.meta, fontWeight = FontWeight.W500, color = y.textMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // Home has its own header rather than the shared one, so it needs this explicitly. It is
        // also the screen most likely to be open while a sync runs, since that is where you land
        // after writing something.
        ie.shoonya.yantra.ui.components.NetworkPulse(Modifier.padding(top = 10.dp, end = 8.dp))
        NavCircle(mark = YantraMark.Settings, contentDescription = "Settings", onClick = onSettings, size = 40.dp)
    }
}

@Composable
private fun SectionHeader(text: String, ink: Color? = null) {
    SectionLabel(
        text,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
        color = ink ?: Yantra.colors.textMuted,
    )
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
    onColour: () -> Unit,
    /** The repository this list came from, or null while there is only one of them. */
    workspace: String? = null,
    /** That repository's colour, as a palette name. */
    workspaceColour: String? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val y = Yantra.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both bare, and wearing whatever colour you gave them — HOME_UI.md §1, and the
            // colour law as remade.
            //
            // A list used to be a coral-tinted tile around its mark while a smart list was a bare
            // mark: the same kind of thing in two treatments, and the tile was the heaviest element
            // on the screen after the title. They are told apart by their drawing now.
            //
            // The colour is *yours*, not the app's. The accent means your own effort and a list is
            // not effort, so it cannot borrow the accent — but it can carry a hue you chose, the
            // way a label already does, and then the colour on this screen comes from your data
            // rather than from the app having one loud idea. Uncoloured lists stay frame ink, which
            // is what makes a coloured one mean something.
            val mine = node.color
                ?.let { name -> LabelPalette.swatches.firstOrNull { it.name.equals(name, true) } }
                ?.let { Color(LabelPalette.display(it.light, y.isDark)) }
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                YantraIcon(
                    if (smart) YantraMark.SmartList else YantraMark.List,
                    tint = mine ?: y.checkOutline,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.title.orEmpty().ifBlank { "Untitled" },
                    fontFamily = YantraDisplay, fontSize = YantraType.row, fontWeight = FontWeight.W500,
                    color = y.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Mono, per the type rule for numbers — HOME_UI.md §2. The subtitle is "0 of 4
                // done", which is a count and reads as one.
                //
                // The repository's name rides on the end of it, in the repository's colour. This is
                // the line the whole colour system is built on: **a colour is never more than a
                // glance from its name.** The spine on a widget row and on a block is the same hue
                // with no room for a word beside it, and this is where you learn which word it is.
                // The mark above already wears the *list's* colour, so the two facts a row carries
                // are told apart by where they sit — a fill for the list, a word for the repo — and
                // never by hue alone, which five swatches could not do.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        subtitle,
                        fontFamily = YantraMono,
                        fontSize = YantraType.caption,
                        color = y.textMuted,
                    )
                    if (workspace != null) {
                        Text(
                            "  ·  ",
                            fontFamily = YantraMono,
                            fontSize = YantraType.caption,
                            color = y.textDim,
                        )
                        Text(
                            workspace,
                            fontFamily = YantraMono,
                            fontSize = YantraType.caption,
                            // The count beside it is W400; bolding this one made the least
                            // important fact on the row the heaviest thing on its line. The hue is
                            // the mark — it does not need a weight as well.
                            fontWeight = FontWeight.W400,
                            color = LabelPalette.byName(workspaceColour)
                                ?.let { Color(LabelPalette.display(it.light, y.isDark)) }
                                ?: y.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // The ring is gone, and so is the trailing key.
            //
            // The ring said what "0 of 4 done" already said, less precisely — the same argument
            // NAMING.md makes for keeping Rhythm as a numeral. It was also a collision: a ring means
            // "a task you have taken up" in the deck counter, so one beside a list title claimed a
            // state a list cannot be in.
            //
            // Four identical overflow keys at full ink ran down the right edge of a four-row screen.
            // The menu is on long press, which also fixes the ragged edge where Inbox carried a key
            // but no ring while its neighbours carried both.
            Box {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(
                        text = { Text("Colour…") },
                        onClick = { menu = false; onColour() },
                    )
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
            title, fontFamily = YantraDisplay, fontSize = YantraType.body, fontWeight = FontWeight.W700,
            color = y.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text("$count", fontFamily = YantraMono, fontSize = YantraType.dense, color = y.textDim)
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                YantraIcon(YantraMark.More, tint = y.textDim, contentDescription = "Group options")
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
    allLabels: List<ie.shoonya.yantra.data.db.LabelEntity>,
    listNames: List<String>,
    /** Every workspace on this device. One (or none) and the choice is not offered. */
    workspaces: List<WorkspaceEntry>,
    defaultWorkspaceId: String,
    /** Who `@` may name. Home captures into the Inbox, so this is Personal's roster. */
    people: List<String> = emptyList(),
    findTasks: suspend (String) -> List<ie.shoonya.yantra.data.db.NodeEntity> = { emptyList() },
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
        ie.shoonya.yantra.data.format.Links.draft(text.text, text.selection.start)?.second
    } else null
    var taskMatches by remember { mutableStateOf<List<ie.shoonya.yantra.data.db.NodeEntity>>(emptyList()) }
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
                SelectChip(
                    t.label,
                    selected = t == type,
                    mark = t.mark,
                    stretch = true,
                    onClick = { type = t },
                    modifier = Modifier.weight(1f),
                )
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
                    Text("Make this a smart list", color = y.textPrimary, fontFamily = YantraText, fontWeight = FontWeight.W600, fontSize = YantraType.body)
                    Text("Auto-updates from conditions you set, instead of a fixed set of tasks", color = y.textMuted, fontSize = YantraType.caption)
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
private fun HomeTabBar(onCreate: () -> Unit, onStats: () -> Unit, onCalendar: () -> Unit) {
    val y = Yantra.colors
    // Three zones, one thing in each, and the cog in the middle where it can be found without
    // looking — it is the biggest, the only accented, and the only one whose position does not move
    // when something is added beside it.
    //
    // **The home mark is gone.** It sat on the left of the home screen's own bar doing nothing: a
    // button for where you already are is a button that can only ever be a no-op, and it was taking
    // the best-reachable corner of the bar to do it. The calendar has that corner now, which also
    // puts the two ways of *looking* at your work on either side of the one way of *adding* to it.
    Row(
        Modifier.fillMaxWidth().background(y.page).navigationBarsPadding()
            // 22dp, the one page margin — CALENDAR_UI.md §1 asked for the bars to agree, and
            // PAGE_MARGIN is the number they should agree on rather than a third one.
            .padding(start = PAGE_MARGIN, end = PAGE_MARGIN, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onCalendar), contentAlignment = Alignment.Center) {
                YantraIcon(YantraMark.Calendar, size = YantraIcons.Large, tint = y.textSecondary, contentDescription = "Calendar")
            }
        }
        // The make-something key — HOME_UI.md §3.
        //
        // It wore `GearMark` while opening the create sheet, and Settings lived in the top-right
        // circle wearing sliders. That is inverted from every other app on the phone: a cog means
        // settings, and a person reaching for one found a new-list sheet. The key is `Add` now, at
        // 56dp and radius 17 so it is the same object as the calendar's create key in size, shape
        // and position — the make-something key is in one place on both board screens.
        Box(
            Modifier.size(56.dp)
                .background(y.accentFill, RoundedCornerShape(YantraRadius.card))
                .border(1.dp, y.accentBorder, RoundedCornerShape(YantraRadius.card))
                .clickable(onClick = onCreate),
            contentAlignment = Alignment.Center,
        ) { YantraIcon(YantraMark.Add, size = YantraIcons.Large, tint = y.accent) }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clickable(onClick = onStats), contentAlignment = Alignment.Center) {
                YantraIcon(YantraMark.Stats, size = YantraIcons.Large, tint = y.textSecondary, contentDescription = "Stats")
            }
        }
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
                    YantraIcon(YantraMark.Add, tint = y.accent, contentDescription = null)
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
                RoundedCornerShape(YantraRadius.tiny),
            ).border(2.dp, if (selected) y.accent else y.checkOutline, RoundedCornerShape(YantraRadius.tiny)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) YantraIcon(YantraMark.Check, tint = y.onAccent)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = y.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

