package ie.napkin.supertasks.ui.node

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.components.ChipData
import ie.napkin.supertasks.ui.components.ConfirmDialog
import ie.napkin.supertasks.ui.components.NeutralChip
import ie.napkin.supertasks.ui.components.PomodoroCount
import ie.napkin.supertasks.ui.components.PropertyChip
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.TaskCheck
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.ink.InkPreview
import ie.napkin.supertasks.ui.ink.inkContentHeight
import ie.napkin.supertasks.ui.theme.MonoBreadcrumb
import ie.napkin.supertasks.ui.theme.Yantra

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodePageScreen(nav: NavHostController, nodeId: String) {
    val vm: NodePageViewModel = viewModel(key = "node-$nodeId") { NodePageViewModel(container(), nodeId) }
    val node by vm.node.collectAsStateWithLifecycle()
    val crumbs by vm.breadcrumb.collectAsStateWithLifecycle()
    val children by vm.children.collectAsStateWithLifecycle()
    val chips by vm.chips.collectAsStateWithLifecycle()
    val defs by vm.defs.collectAsStateWithLifecycle()
    val ownValues by vm.ownValues.collectAsStateWithLifecycle()
    val allLabels by vm.allLabels.collectAsStateWithLifecycle()
    val ownLabels by vm.ownLabels.collectAsStateWithLifecycle()
    val childCounts by vm.childCounts.collectAsStateWithLifecycle()
    val pomoCounts by vm.pomoCounts.collectAsStateWithLifecycle()
    val inkPreviews by vm.inkPreviews.collectAsStateWithLifecycle()

    var propertySheetFor by remember { mutableStateOf<String?>(null) }
    var deletingPage by remember { mutableStateOf(false) }
    // A freshly-added task auto-focuses for typing (since tapping a row now opens it).
    var justCreatedId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.addBlock(NodeType.IMAGE, uri.toString())
        }
    }

    val current = node
    val isTask = current?.type == NodeType.TASK
    val y = Yantra.colors

    // Collapse the header once the content scrolls — the page feels immersive, like a note.
    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 }
    }

    // Direct-child task tallies for the list meta line.
    val taskChildren = children.count { it.type == NodeType.TASK }
    val doneChildren = children.count { it.type == NodeType.TASK && it.done }

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page),
    ) {
        PageBand(
            node = current,
            isTask = isTask,
            collapsed = collapsed,
            crumbs = crumbs,
            metaTotal = taskChildren,
            metaDone = doneChildren,
            onBack = { nav.popBackStack() },
            onFocus = { nav.navigate(Routes.focus(nodeId)) },
            onDelete = { deletingPage = true },
            onRename = vm::renamePage,
            onToggleDone = { done -> vm.setDone(nodeId, done) },
            properties = {
                if (isTask) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PropertyPillsRow(
                            defs = defs,
                            values = ownValues,
                            onSet = { def, t, n, d, b -> vm.setProperty(nodeId, def, t, n, d, b) },
                            onClear = { defId -> vm.clearProperty(nodeId, defId) },
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        LabelChipsRow(
                            allLabels = allLabels,
                            attached = ownLabels,
                            onDetach = { label -> vm.detachLabel(nodeId, label.id) },
                            onAttach = { label -> vm.attachLabel(nodeId, label.id) },
                            onCreateAndAttach = { name -> vm.createAndAttachLabel(nodeId, name) },
                        )
                    }
                }
            },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            items(children, key = { it.id }) { child ->
                BlockRow(
                    child = child,
                    chips = chips[child.id].orEmpty(),
                    childCount = childCounts[child.id]?.total ?: 0,
                    pomoCount = pomoCounts[child.id] ?: 0,
                    inkStrokes = inkPreviews[child.id].orEmpty(),
                    autoFocus = child.type == NodeType.TASK && child.id == justCreatedId,
                    onAutoFocusConsumed = { if (justCreatedId == child.id) justCreatedId = null },
                    vm = vm,
                    onOpen = {
                        when (child.type) {
                            NodeType.INK -> nav.navigate(Routes.ink(child.id))
                            else -> nav.navigate(Routes.node(child.id))
                        }
                    },
                    onFocus = { nav.navigate(Routes.focus(child.id)) },
                    onProperties = { propertySheetFor = child.id },
                )
            }
            item(key = "bottom-spacer") { Spacer(Modifier.height(12.dp)) }
        }

        AddBlockBar(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding(),
            onAddTask = { vm.addBlock(NodeType.TASK, "") { id -> justCreatedId = id } },
            onAddText = { vm.addBlock(NodeType.PARAGRAPH, "") },
            onAddHeading = { vm.addBlock(NodeType.HEADING, "") },
            onAddInk = { vm.addBlock(NodeType.INK, null) { id -> nav.navigate(Routes.ink(id)) } },
            onAddImage = { imagePicker.launch(arrayOf("image/*")) },
        )
    }

    propertySheetFor?.let { targetId ->
        PropertySheet(
            vm = vm,
            targetId = targetId,
            onDismiss = { propertySheetFor = null },
        )
    }

    if (deletingPage && current != null) {
        ConfirmDialog(
            title = "Delete \"${current.title.orEmpty()}\"?",
            body = "This page and everything inside it will be deleted.",
            onDismiss = { deletingPage = false },
            onConfirm = {
                deletingPage = false
                vm.delete(nodeId)
                nav.popBackStack()
            },
        )
    }
}

@Composable
private fun PageBand(
    node: NodeEntity?,
    isTask: Boolean,
    collapsed: Boolean,
    crumbs: List<String>,
    metaTotal: Int,
    metaDone: Int,
    onBack: () -> Unit,
    onFocus: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onToggleDone: (Boolean) -> Unit,
    properties: @Composable () -> Unit,
) {
    val y = Yantra.colors
    var menu by remember { mutableStateOf(false) }
    var title by remember(node?.id) { mutableStateOf(node?.title.orEmpty()) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(y.band, RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = if (collapsed) 10.dp else 22.dp),
    ) {
        // top row: back · (breadcrumb / collapsed title) · actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderTile(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = y.textPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (collapsed) {
                Text(
                    title.ifBlank { "Untitled" },
                    fontFamily = ie.napkin.supertasks.ui.theme.YantraDisplay,
                    fontSize = 16.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.2).sp,
                    color = y.textPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                val segments = remember(crumbs) {
                    val all = listOf("Workspace") + crumbs
                    if (all.size <= 3) all else listOf("…") + all.takeLast(2)
                }
                Text(
                    segments.joinToString("  /  ") { it.uppercase() },
                    style = MonoBreadcrumb,
                    color = y.textMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isTask) {
                HeaderTile(onClick = onFocus, accent = true) {
                    Icon(Icons.Default.Timer, contentDescription = "Focus on this task", tint = y.accentGlow, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Box {
                HeaderTile(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Page options", tint = y.textSecondary, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }

        // The big title, checkbox, meta and properties fold away as you scroll.
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Row(
                    Modifier.padding(top = 22.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (isTask && node != null) {
                        TaskCheck(
                            done = node.done,
                            onToggle = { onToggleDone(!node.done) },
                            size = 24.dp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it; onRename(it) },
                        textStyle = MaterialTheme.typography.headlineMedium.copy(color = y.textPrimary),
                        cursorBrush = SolidColor(y.accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box {
                                if (title.isEmpty()) {
                                    Text("Untitled", style = MaterialTheme.typography.headlineMedium, color = y.textMuted.copy(alpha = 0.6f))
                                }
                                inner()
                            }
                        },
                    )
                }

                if (!isTask && metaTotal > 0) {
                    Text(
                        "$metaDone of $metaTotal done",
                        style = MaterialTheme.typography.bodySmall,
                        color = y.textMuted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                properties()
            }
        }
    }
}

@Composable
private fun HeaderTile(onClick: () -> Unit, accent: Boolean = false, content: @Composable () -> Unit) {
    val y = Yantra.colors
    Box(
        Modifier
            .size(38.dp)
            .background(
                if (accent) y.accent.copy(alpha = 0.18f) else y.textPrimary.copy(alpha = 0.06f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BlockRow(
    child: NodeEntity,
    chips: List<ChipData>,
    childCount: Int,
    pomoCount: Int,
    inkStrokes: List<androidx.ink.strokes.Stroke>,
    autoFocus: Boolean,
    onAutoFocusConsumed: () -> Unit,
    vm: NodePageViewModel,
    onOpen: () -> Unit,
    onFocus: () -> Unit,
    onProperties: () -> Unit,
) {
    when (child.type) {
        NodeType.TASK -> TaskRow(child, chips, childCount, pomoCount, autoFocus, onAutoFocusConsumed, vm, onOpen, onFocus, onProperties)
        NodeType.HEADING -> TextBlockRow(child, vm, isHeading = true, onOpen = onOpen)
        NodeType.PARAGRAPH -> TextBlockRow(child, vm, isHeading = false, onOpen = onOpen)
        NodeType.INK -> InkBlockRow(child, inkStrokes, vm, onOpen)
        NodeType.IMAGE -> ImageBlockRow(child, vm)
        else -> TextBlockRow(child, vm, isHeading = false, onOpen = onOpen)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(
    child: NodeEntity,
    chips: List<ChipData>,
    childCount: Int,
    pomoCount: Int,
    autoFocus: Boolean,
    onAutoFocusConsumed: () -> Unit,
    vm: NodePageViewModel,
    onOpen: () -> Unit,
    onFocus: () -> Unit,
    onProperties: () -> Unit,
) {
    val y = Yantra.colors
    var title by remember(child.id) { mutableStateOf(child.title.orEmpty()) }
    // Tapping a task opens it as a page; inline rename is an explicit mode (fresh tasks and
    // the ⋮ → Rename action enter it), so quick-capture still works.
    var editing by remember(child.id) { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(autoFocus) {
        if (autoFocus) { editing = true; onAutoFocusConsumed() }
    }
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }
    val titleColor = if (child.done) y.textDim else y.textPrimary
    val titleDeco = if (child.done) TextDecoration.LineThrough else TextDecoration.None
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            TaskCheck(done = child.done, onToggle = { vm.setDone(child.id, !child.done) }, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(13.dp))
            if (editing) {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it; vm.rename(child.id, it) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = titleColor, textDecoration = titleDeco),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { editing = false }),
                    cursorBrush = SolidColor(y.accent),
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 1.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (!it.isFocused) editing = false },
                    decorationBox = { inner ->
                        Box {
                            if (title.isEmpty()) {
                                Text("New task", style = MaterialTheme.typography.bodyLarge, color = y.textMuted.copy(alpha = 0.5f))
                            }
                            inner()
                        }
                    },
                )
            } else {
                Text(
                    title.ifBlank { "New task" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (title.isBlank()) y.textMuted.copy(alpha = 0.5f) else titleColor,
                    textDecoration = titleDeco,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 1.dp)
                        .clickable(onClick = onOpen),
                )
            }
            if (childCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clickable(onClick = onOpen),
                ) {
                    Text("$childCount", fontSize = 12.sp, fontWeight = FontWeight.W600, color = y.textMuted)
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open as page",
                        tint = y.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                IconButton(onClick = onOpen, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open as page",
                        tint = y.textDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            BlockMenu(child, vm, onFocus = onFocus, onProperties = onProperties, onRename = { editing = true })
        }
        if (chips.isNotEmpty() || pomoCount > 0) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 35.dp, top = 6.dp),
            ) {
                chips.forEach { PropertyChip(it) }
                if (pomoCount > 0) PomodoroCount(pomoCount)
            }
        }
    }
}

@Composable
private fun TextBlockRow(
    child: NodeEntity,
    vm: NodePageViewModel,
    isHeading: Boolean,
    onOpen: () -> Unit,
) {
    val y = Yantra.colors
    var text by remember(child.id) { mutableStateOf(child.title.orEmpty()) }
    val style: TextStyle =
        if (isHeading) TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W800, letterSpacing = (-0.2).sp, color = y.textPrimary)
        else MaterialTheme.typography.bodyMedium.copy(color = y.textSecondary)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = if (isHeading) 10.dp else 6.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it; vm.rename(child.id, it) },
            textStyle = style,
            cursorBrush = SolidColor(y.accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            if (isHeading) "Heading" else "Write something…",
                            style = style,
                            color = y.textMuted.copy(alpha = 0.5f),
                        )
                    }
                    inner()
                }
            },
        )
        BlockMenu(child, vm, onFocus = null, onProperties = null)
    }
}

@Composable
private fun InkBlockRow(
    child: NodeEntity,
    strokes: List<androidx.ink.strokes.Stroke>,
    vm: NodePageViewModel,
    onOpen: () -> Unit,
) {
    val y = Yantra.colors
    // Page-native: the sketch renders straight onto the page background at (near-)true scale.
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .clickable(onClick = onOpen),
        ) {
            if (strokes.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 10.dp),
                ) {
                    Icon(Icons.Default.Draw, contentDescription = null, tint = y.textMuted.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tap to sketch", color = y.textMuted.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val density = LocalDensity.current
                val screenW = LocalContext.current.resources.displayMetrics.widthPixels.toFloat()
                val previewWPx = with(density) { maxWidth.toPx() }
                // Height tracks the sketch's full content so the block grows and pushes the
                // blocks below it down (rather than overlapping them). Clipped so that even a
                // very tall multi-page sketch never paints past its allotted row height.
                val heightDp = remember(strokes, previewWPx) {
                    val contentPx = inkContentHeight(strokes) * (previewWPx / screenW)
                    with(density) { contentPx.toDp() }
                }.coerceIn(64.dp, 2400.dp)
                Box(Modifier.height(heightDp).clipToBounds()) {
                    InkPreview(
                        strokes = strokes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(heightDp),
                    )
                    Text(
                        (child.title?.takeIf { it.isNotBlank() } ?: "Ink").uppercase(),
                        style = MonoBreadcrumb,
                        color = y.textDim,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    )
                }
            }
        }
        BlockMenu(child, vm, onFocus = null, onProperties = null)
    }
}

@Composable
private fun ImageBlockRow(child: NodeEntity, vm: NodePageViewModel) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 6.dp)) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            AsyncImage(
                model = child.title,
                contentDescription = "Image block",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BlockMenu(child, vm, onFocus = null, onProperties = null)
    }
}

@Composable
private fun BlockMenu(
    child: NodeEntity,
    vm: NodePageViewModel,
    onFocus: (() -> Unit)?,
    onProperties: (() -> Unit)?,
    onRename: (() -> Unit)? = null,
) {
    val y = Yantra.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Block options", tint = y.textDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onRename != null) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() })
            }
            if (onProperties != null) {
                DropdownMenuItem(text = { Text("Properties") }, onClick = { open = false; onProperties() })
            }
            if (onFocus != null) {
                DropdownMenuItem(text = { Text("Focus") }, onClick = { open = false; onFocus() })
            }
            DropdownMenuItem(text = { Text("Move up") }, onClick = { open = false; vm.moveUp(child) })
            DropdownMenuItem(text = { Text("Move down") }, onClick = { open = false; vm.moveDown(child) })
            DropdownMenuItem(text = { Text("Indent") }, onClick = { open = false; vm.indent(child) })
            DropdownMenuItem(text = { Text("Outdent") }, onClick = { open = false; vm.outdent(child) })
            when (child.type) {
                NodeType.TASK -> DropdownMenuItem(
                    text = { Text("Turn into text") },
                    onClick = { open = false; vm.convert(child, NodeType.PARAGRAPH) },
                )
                NodeType.PARAGRAPH -> DropdownMenuItem(
                    text = { Text("Turn into task") },
                    onClick = { open = false; vm.convert(child, NodeType.TASK) },
                )
                else -> Unit
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; vm.delete(child.id) },
            )
        }
    }
}

@Composable
private fun AddBlockBar(
    modifier: Modifier = Modifier,
    onAddTask: () -> Unit,
    onAddText: () -> Unit,
    onAddHeading: () -> Unit,
    onAddInk: () -> Unit,
    onAddImage: () -> Unit,
) {
    val y = Yantra.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(y.page)
            .horizontalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ie.napkin.supertasks.ui.components.AccentPillButton("Task", onAddTask, icon = Icons.Default.Add)
        NeutralChip("Note", onAddText)
        NeutralChip("Heading", onAddHeading, icon = Icons.Default.Title)
        NeutralChip("Ink", onAddInk, icon = Icons.Default.Draw)
        NeutralChip("Image", onAddImage, icon = Icons.Default.Image)
    }
}
