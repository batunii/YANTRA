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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import ie.napkin.supertasks.ui.components.PomodoroCount
import ie.napkin.supertasks.ui.components.PropertyChip
import ie.napkin.supertasks.ui.components.TaskCheck
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.theme.MonoBreadcrumb
import ie.napkin.supertasks.ui.theme.Yantra

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
                .padding(top = 10.dp, bottom = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(y.textPrimary.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .clickable { nav.popBackStack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = y.textPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(38.dp)
                        .background(y.textPrimary.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = y.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Row(
                Modifier.padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = y.accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text(node?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, color = y.textPrimary)
            }
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = y.textMuted,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            if (tasks.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing matches right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = y.textMuted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                Column(Modifier.padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        TaskCheck(done = task.done, onToggle = { vm.setDone(task.id, !task.done) }, modifier = Modifier.padding(top = 1.dp))
                        Spacer(Modifier.width(13.dp))
                        Text(
                            task.title.orEmpty().ifBlank { "Untitled" },
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (task.done) y.textDim else y.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 1.dp)
                                .clickable { nav.navigate(Routes.node(task.id)) },
                        )
                        IconButton(onClick = { nav.navigate(Routes.node(task.id)) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open as page", tint = y.textDim, modifier = Modifier.size(18.dp))
                        }
                    }
                    val taskChips = chips[task.id].orEmpty()
                    val pomo = pomoCounts[task.id] ?: 0
                    if (taskChips.isNotEmpty() || pomo > 0) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(start = 35.dp, top = 6.dp),
                        ) {
                            taskChips.forEach { PropertyChip(it) }
                            if (pomo > 0) PomodoroCount(pomo)
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
    Column(
        modifier
            .fillMaxWidth()
            .background(y.page)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 22.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(y.cardBg, RoundedCornerShape(16.dp))
                .border(1.dp, y.tileBorder, RoundedCornerShape(16.dp))
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
        Text(
            "New tasks are created in your home list and tagged to match this view automatically.",
            fontSize = 11.sp,
            color = y.textDim,
            lineHeight = 15.sp,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}
