package ie.napkin.supertasks.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import ie.napkin.supertasks.ArchivedGroup
import ie.napkin.supertasks.ArchivedTask
import ie.napkin.supertasks.ui.appContainer
import ie.napkin.supertasks.ui.components.ComposedEmpty
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.rememberHeaderFold
import androidx.compose.ui.input.nestedscroll.nestedScroll
import ie.napkin.supertasks.ui.components.PAGE_MARGIN
import ie.napkin.supertasks.ui.components.PageHeader
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import ie.napkin.supertasks.data.format.Links

/**
 * What has left the working set.
 *
 * Archived tasks are deliberately absent from the index — that is the entire point of moving them —
 * so this screen is the one place that reads the archive files directly. It is also the reason the
 * automatic sweep can exist at all: a feature that moves your finished work without anywhere to go
 * and look at it is indistinguishable from one that quietly deletes it.
 *
 * Grouped by the list each task came from, because "where did this go" is answered by "back to where
 * it was" and the grouping is that answer stated in advance.
 */
@Composable
fun ArchiveScreen(nav: NavHostController) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val y = Yantra.colors

    var groups by remember { mutableStateOf<List<ArchivedGroup>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // Re-read on every return: the sweep can run while this screen is in the back stack, and a list
    // that still showed the old contents would be the one thing this screen must never do.
    LifecycleResumeEffect(Unit) {
        val job = scope.launch {
            groups = container.archivedItems()
            loaded = true
        }
        onPauseOrDispose { job.cancel() }
    }

    fun restore(group: ArchivedGroup, task: ArchivedTask) {
        scope.launch {
            container.restoreArchived(group.workspaceId, group.pageId, task.id)
            groups = container.archivedItems()
        }
    }

    Column(Modifier.fillMaxSize().background(y.page).statusBarsPadding()) {
        val fold = rememberHeaderFold()
        PageHeader("Archive", onBack = { nav.popBackStack() }, collapsed = fold.collapsed)

        if (loaded && groups.isEmpty()) {
            ComposedEmpty("Nothing archived yet")
            return@Column
        }

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .nestedScroll(fold.connection)
                .padding(horizontal = PAGE_MARGIN),
        ) {
            item(key = "why") {
                Text(
                    "Finished tasks that left your lists. They are still in the repository — putting " +
                        "one back returns it exactly where it was.",
                    color = y.textMuted,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            groups.forEach { group ->
                item(key = "h-${group.pageId}") {
                    SectionLabel(group.listTitle)
                    Spacer(Modifier.height(8.dp))
                }
                items(group.tasks, key = { "${group.pageId}-${it.id}" }) { task ->
                    ArchivedRow(task) { restore(group, task) }
                }
                item(key = "s-${group.pageId}") { Spacer(Modifier.height(20.dp)) }
            }
            item(key = "bottom") { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ArchivedRow(task: ArchivedTask, onRestore: () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(y.cardBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                Links.plain(task.title).ifBlank { "Untitled task" },
                color = y.textSecondary,
                fontFamily = YantraText,
                fontWeight = FontWeight.W600,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            task.doneAt?.let {
                Spacer(Modifier.height(2.dp))
                Text("Finished ${finishedLabel(it)}", color = y.textDim, fontSize = 11.5.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        // The way back, on the row itself. Restoring one thing should not require understanding the
        // archive as a whole, which is what a single "restore everything" would have demanded.
        NavCircle(
            Icons.Default.Undo,
            contentDescription = "Put back",
            onClick = onRestore,
            accent = true,
            size = 34.dp,
            iconSize = 17.dp,
        )
    }
}

/** How long ago, in the units a person would use for something they finished. */
private fun finishedLabel(date: LocalDate): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(date, LocalDate.now())
    return when {
        days < 1 -> "today"
        days == 1L -> "yesterday"
        days < 30 -> "$days days ago"
        days < 365 -> "${days / 30} months ago"
        else -> date.format(archiveDateFmt)
    }
}

private val archiveDateFmt = DateTimeFormatter.ofPattern("MMM yyyy")
