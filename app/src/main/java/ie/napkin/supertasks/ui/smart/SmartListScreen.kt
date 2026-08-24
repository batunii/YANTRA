package ie.napkin.supertasks.ui.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.components.ComposedEmpty
import ie.napkin.supertasks.ui.components.ListGroupRow
import ie.napkin.supertasks.ui.components.QuickAddBar
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.node.TextualBlockRow
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
    val childCounts by vm.childCounts.collectAsStateWithLifecycle()
    val description by vm.description.collectAsStateWithLifecycle()
    val y = Yantra.colors
    var activeId by remember { mutableStateOf<String?>(null) }

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
                ListGroupRow(first = index == 0, last = index == tasks.lastIndex) {
                    // The SAME row a list page draws. Not a look-alike: this screen used to carry
                    // its own read-only copy, which is how tap-to-edit ended up working on Inbox
                    // and not here. A task is a task wherever it is shown; the only thing a screen
                    // decides is which tasks to put in front of it.
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        TextualBlockRow(
                            child = task,
                            active = activeId == task.id,
                            onActivate = { activeId = task.id },
                            onFocusChange = { if (it) activeId = task.id },
                            // Caret hand-off belongs to a document, where blocks split and merge.
                            claimCaret = false,
                            onCaretClaimed = {},
                            // Nothing types here, so there is no Enter to split on and no
                            // Backspace to merge back with.
                            onSplit = { _, _ -> },
                            onMergeBack = {},
                            chips = chips[task.id].orEmpty(),
                            childCount = childCounts[task.id] ?: 0,
                            ordinal = 0,
                            pomoCount = pomoCounts[task.id] ?: 0,
                            autoFocus = false,
                            onAutoFocusConsumed = {},
                            onRename = {},
                            onToggleDone = { vm.setDone(task.id, it) },
                            // Only tasks are gathered here, so there is no type to convert to.
                            onBecome = {},
                            onOpen = { nav.navigate(Routes.node(task.id)) },
                            // A smart list is a list: the row opens the task, it does not become a
                            // text field. Typing happens on the task's own page.
                            editable = false,
                        )
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
