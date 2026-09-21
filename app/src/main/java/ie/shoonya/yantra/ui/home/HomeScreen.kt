package ie.shoonya.yantra.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import ie.shoonya.yantra.ui.components.ListGlyph
import ie.shoonya.yantra.ui.components.YantraField
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
import ie.shoonya.yantra.data.format.ListIcon
import ie.shoonya.yantra.data.label.LabelPalette
import androidx.compose.foundation.layout.heightIn
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

    val renderRow: @Composable (NodeEntity, Boolean) -> Unit = { node, closesRun ->
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
            closesRun = closesRun,
        )
    }

    Scaffold(
        containerColor = y.page,
        bottomBar = {
            // One dock, two rows — see NowDock. Home's keys and whatever is running are the same
            // object at the foot of the screen, not a panel parked on top of a bar.
            ie.shoonya.yantra.ui.components.NowDock {
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
                // Only when there is a player above them to be separated from.
                if (stack.isNotEmpty()) ie.shoonya.yantra.ui.components.NowDockSeam()
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
                    items(ungroupedSmart, key = { it.id }) { node ->
                        renderRow(node, node.id == ungroupedSmart.last().id)
                    }
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
                    // Lists and groups **in one rank order**, not lists-then-groups.
                    //
                    // They were rendered as two buckets, which put every group at the bottom of its
                    // repository however you had arranged them — the ordering you dragged into
                    // place was thrown away on the way to the screen. A group is a sibling of a
                    // top-level list: `topLevel()` already returns both in rank order, and the only
                    // thing that had to change is not taking them apart again.
                    val entries = nodes.filter {
                        (it.type == NodeType.LIST || it.type == NodeType.GROUP) &&
                            (id == null || it.workspaceId == id)
                    }
                    if (entries.isEmpty()) return@forEach
                    item(key = "ws-$id") {
                        SectionHeader(
                            name,
                            // The heading is the legend the spine is read against — DESIGN.md §4.7.
                            // A hue is a glance and its name is right here, once.
                            ink = LabelPalette.byName(spaceHues[id])
                                ?.let { Color(LabelPalette.display(it.light, y.isDark)) },
                        )
                    }
                    entries.forEachIndexed { i, node ->
                        if (node.type != NodeType.GROUP) {
                            // A loose row closes its run when a group — or the end of the
                            // repository — comes next.
                            val next = entries.getOrNull(i + 1)
                            item(key = node.id) {
                                renderRow(node, next == null || next.type == NodeType.GROUP)
                            }
                            return@forEachIndexed
                        }
                        item(key = "g-${node.id}") {
                            GroupBanner(
                                title = node.title.orEmpty().ifBlank { "Untitled group" },
                                count = byGroup[node.id]?.size ?: 0,
                                collapsed = node.collapsed,
                                onToggle = { vm.setCollapsed(node.id, !node.collapsed) },
                                onRename = { renaming = node },
                                onDelete = { deleting = node },
                            )
                        }
                        if (!node.collapsed) {
                            val kids = byGroup[node.id].orEmpty()
                            items(kids, key = { it.id }) { kid ->
                                renderRow(kid, kid.id == kids.last().id)
                            }
                        }
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
        // Re-read from the live list on every recomposition rather than held in `colouring`.
        // The sheet applies each tap immediately and stays open, so a captured copy would show the
        // grid and the colour row disagreeing with the row behind them the moment anything was
        // picked.
        val live = allLists.firstOrNull { it.id == node.id } ?: node
        ListLookDialog(
            icon = live.icon,
            color = live.color,
            smart = live.type == NodeType.SMART_LIST,
            onIcon = { emoji -> vm.setListIcon(live.id, emoji) },
            onColour = { name -> vm.setListColor(live.id, name) },
            onDismiss = { colouring = null },
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
/**
 * How a list looks: its icon and its colour, in one sheet.
 *
 * **Reached by tapping the glyph itself**, which is the thing being changed. It was behind a long
 * press and a menu item called "Colour…", which is a fair place for a rename and the wrong place
 * for an appearance: nothing on the row suggested the mark was a control, so the feature existed
 * for whoever had already found it.
 *
 * **The list's own mark is the first cell, not the absence of a choice.** Somebody who wants the
 * drawn mark in a different colour is doing something completely ordinary, and if the only way to
 * say "no emoji" were to never touch the grid, then the moment you tried one emoji you could not
 * get back without knowing that Reset also clears the colour you were happy with. So the mark sits
 * in the grid, wearing the colour currently chosen, and is selected exactly when no emoji is.
 *
 * Every tap applies at once and the sheet stays open, because the two choices are judged together:
 * you pick an emoji, see it against the colour, and change the colour rather than guessing. Done
 * closes; there is nothing to confirm, since everything is already done.
 */
@Composable
private fun ListLookDialog(
    icon: String?,
    color: String?,
    smart: Boolean,
    onIcon: (String?) -> Unit,
    onColour: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val y = Yantra.colors
    var typed by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Icon & colour") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel("Icon")
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The list's own mark, offered as a choice rather than left as the state you
                    // are in when you have not made one.
                    IconCell(selected = icon == null, onClick = { onIcon(null) }) {
                        ListGlyph(icon = null, color = color, smart = smart, size = 30.dp)
                    }
                    ListIcon.suggested.forEach { emoji ->
                        IconCell(selected = icon == emoji, onClick = { onIcon(emoji) }) {
                            Text(emoji, fontSize = 20.sp)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                // The escape hatch from a curated set. Forty is enough for almost everybody and is
                // wrong for somebody, and the somebody is the person whose list is about a thing
                // nobody anticipated.
                Text(
                    "Or type one — your keyboard's emoji tab, or any character",
                    color = y.textMuted,
                    fontSize = YantraType.meta,
                )
                Spacer(Modifier.height(8.dp))
                YantraField(
                    value = typed,
                    onValue = { entered ->
                        typed = entered
                        // Applied as it is typed, so the grid selection and the row behind the
                        // sheet both answer immediately. Cleaned to one character on the way in —
                        // see ListIcon.
                        ListIcon.clean(entered)?.let(onIcon)
                    },
                    placeholder = "🙂",
                )

                Spacer(Modifier.height(20.dp))
                SectionLabel("Colour")
                Spacer(Modifier.height(2.dp))
                Text(
                    if (icon != null) "Sits behind the emoji" else "Colours the mark",
                    color = y.textMuted,
                    fontSize = YantraType.meta,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Frame ink, shown as a swatch so "no colour" is a choice on the same row as
                    // the colours rather than a link underneath them.
                    ColourDot(
                        colour = y.checkOutline,
                        selected = color == null,
                        onClick = { onColour(null) },
                    )
                    LabelPalette.swatches.forEach { swatch ->
                        ColourDot(
                            colour = Color(LabelPalette.display(swatch.light, y.isDark)),
                            selected = color.equals(swatch.name, ignoreCase = true),
                            onClick = { onColour(swatch.name) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(
                onClick = { typed = ""; onIcon(null); onColour(null) },
            ) { Text("Reset") }
        },
    )
}

/** One cell of the icon grid. The ring says which is on, exactly as [ColourDot]'s does. */
@Composable
private fun IconCell(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val y = Yantra.colors
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(
                if (selected) 2.dp else 0.dp,
                if (selected) y.textPrimary else Color.Transparent,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
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
            // 8, not 11 — the same step the list rows took. The mark is 34dp tall, so the row is
            // still 50 and clear of the 48dp a finger needs; the space was doing nothing the mark's
            // own height was not already doing.
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                YantraIcon(YantraMark.Calendar, tint = ink)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // A sitting borrows its task's words — see EventWithTitle.displayTitle. This
                    // row read the event's own title, and a sitting has none, so the one place on
                    // Home that says what you are next expected at said "Untitled" for a task that
                    // was perfectly well named.
                    event.displayTitle.orEmpty().ifBlank { "Untitled" },
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
    // **The pulse is drawn over the header, not laid out inside it.**
    //
    // It sat in this Row, and a Row shares its width out: the pill expanding took width from the
    // weighted column beside it, the greeting re-wrapped to fit what was left, the header grew a
    // line taller, and the whole screen moved down — every time a sync started, and back up when it
    // finished. A report that something is happening in the background must not rearrange the
    // foreground; that is the one thing it was built not to do.
    //
    // Chrome's shared header does not have the problem and does not need this: the pill eats a
    // flexible spacer there, and the title beside it is one line with an ellipsis, so nothing it
    // takes can make anything taller.
    Box(Modifier.fillMaxWidth()) {
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
        NavCircle(mark = YantraMark.Settings, contentDescription = "Settings", onClick = onSettings, size = 40.dp)
    }
        // Home has its own header rather than the shared one, so it needs this explicitly. It is
        // also the screen most likely to be open while a sync runs, since that is where you land
        // after writing something. Aligned past the cog's 40dp and its gap, into the space beside
        // the date, which is empty on every screen width this app supports.
        ie.shoonya.yantra.ui.components.NetworkPulse(
            Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 48.dp),
        )
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
    /**
     * Whether to close the run with a hairline.
     *
     * **A line ends a run; it does not separate two rows inside one.** Every row used to carry one,
     * which drew four rules through a four-row screen and read as padding — and it was saying what
     * the heading above already said, since a heading and the space under it is what groups these
     * rows in the first place. Now the only rule is the one under the last row before the next
     * heading, which is a boundary and therefore worth a mark.
     */
    closesRun: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    val y = Yantra.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                // 8dp against a 34dp mark: a 50dp row, which still clears the 48 a finger needs.
                // Rows were separated by a rule when this was 11, and a line and a gap were both
                // paying for the same separation; with the rules gone the gap can come in.
                .padding(vertical = 8.dp),
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
            //
            // And the glyph is the way in to changing it. It was behind a long press and a menu
            // item, which meant nothing on the row said the mark could be anything else -- the
            // feature was there for whoever had already found it. Tapping the thing you want to
            // change is the gesture people try first, and it costs the row nothing: the title and
            // the rest of it still open the list.
            ListGlyph(
                icon = node.icon,
                color = node.color,
                smart = smart,
                onClick = onColour,
                contentDescription = "Icon and colour",
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.title.orEmpty().ifBlank { "Untitled" },
                    fontFamily = YantraDisplay, fontSize = YantraType.row, fontWeight = FontWeight.W500,
                    color = y.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Mono, per the type rule for numbers — HOME_UI.md §2. The subtitle is "0 of 4
                // done", which is a count and reads as one — and only that, now that the repository
                // is named once at the top of its section rather than on every row under it.
                Text(
                    subtitle,
                    fontFamily = YantraMono,
                    fontSize = YantraType.caption,
                    color = y.textMuted,
                )
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
                        text = { Text("Icon & colour…") },
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
        if (closesRun) HorizontalDivider(color = y.hairline, thickness = 1.dp)
    }
}

/**
 * A group of lists, inside the repository that owns it.
 *
 * **It is a heading, and it was louder than both its neighbours.** The Display face at 14.5/W700 in
 * full ink put it above the rows it heads (15/W500) in weight and far above the workspace heading
 * that contains it (11/W700, muted) — a child heading shouting over its parent. Three levels now
 * step down in the order they nest: the repository is tracked caps in its own hue, a group is a
 * quiet secondary line, a list row is the loudest thing because it is the thing you came for.
 *
 * **It folds, and remembers.** Every app with folders lets you collapse them, and the app already
 * had the machinery — `node.collapsed` with a deliberately device-local write, on the grounds that
 * whether a section is folded is about this screen and not about the work. A group on Home is that
 * exact case, so it reuses it rather than inventing a second kind of memory.
 *
 * The trailing overflow key is gone, for the reason HomeRow gave when it dropped its own: a column
 * of identical keys down the right edge. Tap folds, long-press is the menu — the same gesture the
 * rows beneath it already use, so there is one rule on this screen rather than two.
 */
@Composable
private fun GroupBanner(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val y = Yantra.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = { menu = true })
                .padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // One drawing at two rotations — see YantraMark.Up. The wedge is the fold's only
            // affordance now, so it is the one part of this line that is not muted into the paper.
            YantraIcon(
                if (collapsed) YantraMark.Forward else YantraMark.Down,
                size = YantraIcons.Small,
                tint = y.textMuted,
                contentDescription = if (collapsed) "Expand group" else "Collapse group",
            )
            Spacer(Modifier.width(7.dp))
            Text(
                title,
                fontFamily = YantraText,
                fontSize = YantraType.label,
                fontWeight = FontWeight.W600,
                color = y.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            // The count is what a folded group still has to say: it is the only thing left of it.
            Text("$count", fontFamily = YantraMono, fontSize = YantraType.dense, color = y.textDim)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Delete group") }, onClick = { menu = false; onDelete() })
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
        // No ground and no navigation-bar inset of its own: it is a row inside the dock, which
        // carries both. It painted y.page here, which is what made the player above it read as a
        // separate panel rather than the top of this one.
        Modifier.fillMaxWidth()
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

