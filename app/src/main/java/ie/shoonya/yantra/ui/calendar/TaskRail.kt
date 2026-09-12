package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.data.db.RailTask
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono

/**
 * The tasks beside the day — CALENDAR_PLAN.md §13.
 *
 * A calendar can only draw what has a date, so the task most in need of a time is the one it cannot
 * show. This is that list, cut four ways.
 *
 * **Tap-to-arm, not drag.** Tapping a task lifts it; the next tap on an hour puts it there. Drag
 * across two scrolling surfaces is the accelerator, and it is the one interaction that can pass a
 * test and fail a real finger — so the reliable gesture is the one that ships first and the one the
 * rail explains.
 */
@Composable
fun TaskRail(
    shelves: Map<RailBucket, List<RailTask>>,
    shelf: RailBucket,
    armed: String?,
    onShelf: (RailBucket) -> Unit,
    onArm: (RailTask?) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val y = Yantra.colors
    val tasks = shelves[shelf].orEmpty()

    Column(modifier.testTag("rail")) {
        // The four shelves, always all four — see railShelves. Scrollable because four words do not
        // fit a quarter of a phone, and wrapping them would move the day.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RailBucket.entries.forEach { b ->
                val on = b == shelf
                val count = shelves[b].orEmpty().size
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (on) Modifier.background(y.accentFill) else Modifier)
                        .clickable { onShelf(b) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        b.label,
                        fontSize = 10.sp,
                        fontWeight = if (on) FontWeight.W700 else FontWeight.W500,
                        color = if (on) y.accentText else y.textMuted,
                        maxLines = 1,
                    )
                    if (count > 0) {
                        Text(
                            count.toString(),
                            fontFamily = YantraMono,
                            fontSize = 9.sp,
                            color = if (on) y.accentText.copy(alpha = 0.7f) else y.textDim,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }

        if (armed != null) {
            // The instruction only exists while it is true. A standing hint is furniture.
            Text(
                "Tap a time on the day",
                fontSize = 10.sp,
                fontWeight = FontWeight.W700,
                color = y.accent,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (tasks.isEmpty()) {
            Text(
                emptyWord(shelf),
                fontSize = 11.sp,
                color = y.textDim,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(tasks, key = { it.nodeId }) { task ->
                    RailRow(
                        task = task,
                        armed = task.nodeId == armed,
                        onTap = { onArm(if (task.nodeId == armed) null else task) },
                        onOpen = { onOpen(task.nodeId) },
                    )
                }
            }
        }
    }
}

/** What an empty shelf says. Each one means something different, so none of them says "No items". */
private fun emptyWord(shelf: RailBucket): String = when (shelf) {
    RailBucket.TODAY -> "Nothing due today."
    RailBucket.SOON -> "No deadlines coming up."
    RailBucket.UNDATED -> "Everything has a date."
    RailBucket.OTHER -> "Nothing else waiting."
}

/**
 * One task, waiting for a time.
 *
 * **One row, one target.** An earlier version put a chevron at the end for opening the task, and in
 * a rail a quarter of a phone wide a 22dp button beside a 37dp title is not two targets — it is one
 * target with a trap in it. A tap aimed at the row landed on the chevron often enough to be the
 * normal outcome, which meant the rail's whole purpose failed on its most common gesture.
 *
 * So the tap arms, the whole row, and **a long press opens the task** — the same idiom as everywhere
 * else a list row has a second thing you might want from it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RailRow(task: RailTask, armed: Boolean, onTap: () -> Unit, onOpen: () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (armed) y.accentFill else y.cardBg)
            .then(if (armed) Modifier.border(1.dp, y.accent, RoundedCornerShape(8.dp)) else Modifier)
            .combinedClickable(onClick = onTap, onLongClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .testTag("rail:${task.nodeId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                task.title.orEmpty().ifEmpty { "Untitled" },
                fontSize = 12.sp,
                fontWeight = FontWeight.W600,
                color = if (armed) y.accentText else y.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Only shown once there is more than one, because "1" beside every planned task is a
            // column of ones telling you nothing.
            if (task.sittings > 1) {
                Text(
                    "planned ${task.sittings}×",
                    fontSize = 9.sp,
                    color = if (armed) y.accentText.copy(alpha = 0.75f) else y.textDim,
                )
            }
        }
    }
}

/** Separator between the day and the rail, so the two read as two things rather than one column. */
@Composable
fun RailDivider() {
    val y = Yantra.colors
    Spacer(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(y.tileBorder.copy(alpha = 0.5f)),
    )
}

/** A hairline used when the rail sits under the day rather than beside it. */
@Composable
fun RailDividerHorizontal() {
    val y = Yantra.colors
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(y.tileBorder.copy(alpha = 0.5f)))
}
