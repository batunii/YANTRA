package ie.napkin.supertasks.ui.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.components.ComposedEmpty
import ie.napkin.supertasks.ui.components.GroupDivider
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.PomodoroCount
import ie.napkin.supertasks.ui.components.PropertyChip
import ie.napkin.supertasks.ui.components.TaskCheck
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraText

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SmartListScreen(nav: NavHostController, nodeId: String) {
    val vm: SmartListViewModel = viewModel(key = "smart-$nodeId") { SmartListViewModel(container(), nodeId) }
    val node by vm.node.collectAsStateWithLifecycle()
    val def by vm.def.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val chips by vm.chips.collectAsStateWithLifecycle()
    val pomoCounts by vm.pomoCounts.collectAsStateWithLifecycle()
    val description by vm.description.collectAsStateWithLifecycle()
    val y = Yantra.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page),
    ) {
        // band header
        Column(
            Modifier
                .fillMaxWidth()
                .background(y.band, RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NavCircle(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    onClick = { nav.popBackStack() },
                    iconSize = 20.dp,
                )
                Spacer(Modifier.weight(1f))
                NavCircle(Icons.Default.MoreVert, contentDescription = "Options", iconSize = 18.dp)
            }
            // The star moves up into an eyebrow: it says what kind of page this is, which is
            // context, not part of the title.
            Row(
                Modifier.padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = y.accent, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    "SMART VIEW",
                    fontFamily = YantraText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = 1.5.sp,
                    color = y.textMuted,
                )
            }
            Text(
                node?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = y.textPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Rule-speak demoted to a pill. How the view is assembled is mechanics; it belongs
            // beside the title at label size, not under it in a sentence.
            if (description.isNotBlank()) {
                Row(
                    Modifier
                        .padding(top = 12.dp)
                        .background(y.page, RoundedCornerShape(99.dp))
                        .border(1.dp, y.tileBorder, RoundedCornerShape(99.dp))
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(y.accent, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        description,
                        fontFamily = YantraText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.W600,
                        color = y.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (tasks.isEmpty()) {
                item(key = "empty") {
                    ComposedEmpty("Nothing matches right now")
                }
            }
            // One card, hairline-divided: two elevation layers instead of rows floating on the
            // page. Corners are rounded only where the group actually ends.
            itemsIndexed(tasks, key = { _, t -> t.id }) { index, task ->
                val first = index == 0
                val last = index == tasks.lastIndex
                val shape = RoundedCornerShape(
                    topStart = if (first) 20.dp else 0.dp,
                    topEnd = if (first) 20.dp else 0.dp,
                    bottomStart = if (last) 20.dp else 0.dp,
                    bottomEnd = if (last) 20.dp else 0.dp,
                )
                Column(Modifier.fillMaxWidth().background(y.cardBg, shape)) {
                    if (!first) GroupDivider()
                    val taskChips = chips[task.id].orEmpty()
                    val pomo = pomoCounts[task.id] ?: 0
                    Row(
                        Modifier
                            // The whole row is the target now — a chevron per row was five
                            // pixels of arrow repeating down the page saying nothing.
                            .clickable { nav.navigate(Routes.node(task.id)) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        TaskCheck(
                            done = task.done,
                            onToggle = { vm.setDone(task.id, !task.done) },
                            tint = taskChips.firstOrNull { it.isPriority }?.color,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.title.orEmpty().ifBlank { "Untitled" },
                                style = MaterialTheme.typography.bodyLarge,
                                textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (task.done) y.textDim else y.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (taskChips.isNotEmpty() || pomo > 0) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 8.dp),
                                ) {
                                    taskChips.forEach { PropertyChip(it) }
                                    if (pomo > 0) PomodoroCount(pomo)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "bottom-spacer") { Spacer(Modifier.height(12.dp)) }
        }

        if (def?.homeParentId != null || def?.scopeRootId != null) {
            QuickAddBar(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
                onAdd = vm::addTask,
            )
        }
    }
}

@Composable
private fun QuickAddBar(modifier: Modifier = Modifier, onAdd: (String) -> Unit) {
    val y = Yantra.colors
    var text by remember { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onAdd(text.trim())
            text = ""
        }
    }
    // No explainer paragraph. Where a new task lands is already stated by the filter pill in
    // the header; saying it twice made the input look like it needed a manual.
    Row(
        modifier
            .fillMaxWidth()
            .background(y.page)
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(y.cardBg, RoundedCornerShape(18.dp))
                .border(1.dp, y.tileBorder, RoundedCornerShape(18.dp))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = y.textPrimary),
                cursorBrush = SolidColor(y.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text("Add a task…", color = y.textDim, fontSize = 14.sp)
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .background(y.accentFill, RoundedCornerShape(12.dp))
                    .border(1.dp, y.accentBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = send),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Add task", tint = y.accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}
