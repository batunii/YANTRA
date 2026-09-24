package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.data.format.Links
import ie.shoonya.yantra.data.db.RailTask
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import ie.shoonya.yantra.ui.theme.YantraType
import ie.shoonya.yantra.ui.theme.YantraRadius

/**
 * The tasks beside the day — CALENDAR_PLAN.md §13.
 *
 * A calendar can only draw what has a date, so the task most in need of a time is the one it cannot
 * show. This is that list, cut four ways.
 *
 * **Two ways to place one, and the slow one is the one the rail explains.** Tapping a task lifts it;
 * the next tap on an hour puts it there. That is the path that cannot be dropped between two
 * scrolling surfaces, so it is what the hint describes and what a test drives.
 *
 * **Long press and drag** is the accelerator on top: hold a row and pull it onto the day, and the
 * hour under your finger draws itself as you go. It is the better gesture when it works and the one
 * that can fail on a real finger, which is why it arrived second rather than instead.
 */
@Composable
fun TaskRail(
    shelves: Map<RailBucket, List<RailTask>>,
    shelf: RailBucket,
    armed: String?,
    onShelf: (RailBucket) -> Unit,
    onArm: (RailTask?) -> Unit,
    onOpen: (String) -> Unit,
    /** A row has been held and is now being pulled onto the day. */
    onDragStart: (RailTask) -> Unit = {},
    /** Where the finger is, in root coordinates — the only space both surfaces share. */
    onDragTo: (Offset) -> Unit = {},
    /** Let go. Whether that lands anywhere is the day's business, not the rail's. */
    onDrop: () -> Unit = {},
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
                        .clip(RoundedCornerShape(YantraRadius.block))
                        .then(if (on) Modifier.background(y.accentFill) else Modifier)
                        .clickable { onShelf(b) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        b.label,
                        fontSize = YantraType.dense,
                        fontWeight = if (on) FontWeight.W700 else FontWeight.W500,
                        color = if (on) y.accentText else y.textMuted,
                        maxLines = 1,
                    )
                    if (count > 0) {
                        Text(
                            count.toString(),
                            fontFamily = YantraMono,
                            fontSize = YantraType.dense,
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
                fontSize = YantraType.dense,
                fontWeight = FontWeight.W700,
                color = y.accent,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (tasks.isEmpty()) {
            Text(
                emptyWord(shelf),
                fontSize = YantraType.caption,
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
                        onDragStart = { onDragStart(task) },
                        onDragTo = onDragTo,
                        onDrop = onDrop,
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
 * Three things you might want from a row, and each has its own gesture: **tap** lifts it, **hold and
 * pull** puts it straight on the day, and the **chevron** opens the task.
 *
 * **The chevron appears only when there is room for it to be a second target.** It was taken away
 * once for being a trap: in a rail a quarter of a phone *wide*, a 28dp button sits with its left
 * edge one point from the centre of the row, so a tap aimed at the middle opens the task instead of
 * lifting it — which is the rail's whole purpose failing on its commonest gesture. Rather than trust
 * that no layout will ever be that narrow, the row measures itself and simply does without below
 * [ROOM_FOR_OPEN]. A row with one target degrades to arming, which is the thing you came for; the
 * task is still reachable from the block once it is on the day.
 *
 * Long press is the drag now rather than the open, because pulling a task onto an hour is the thing
 * a held row wants to do.
 */
@Composable
private fun RailRow(
    task: RailTask,
    armed: Boolean,
    onTap: () -> Unit,
    onOpen: () -> Unit,
    onDragStart: () -> Unit = {},
    onDragTo: (Offset) -> Unit = {},
    onDrop: () -> Unit = {},
) {
    val y = Yantra.colors
    // Its own position, so a drag can be reported in a space the day also understands. The row is
    // inside a scrolling list inside a pane; nothing else in the chain knows where it ended up.
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val roomForOpen = maxWidth >= ROOM_FOR_OPEN
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YantraRadius.block))
            .background(if (armed) y.accentFill else y.cardBg)
            .then(if (armed) Modifier.border(1.dp, y.accent, RoundedCornerShape(YantraRadius.block)) else Modifier)
            .clickable(onClick = onTap)
            .onGloballyPositioned { coords = it }
            .pointerInput(task.nodeId) {
                // After a long press, because a plain drag inside a scrolling list is a scroll —
                // the same reason the blocks on the timeline wait for one. The lift is what says
                // the row is yours to move.
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, _ ->
                        change.consume()
                        // Root coordinates: the rail and the day are siblings with no shared
                        // ancestor either of them can see, and root is the space they do share.
                        coords?.takeIf { it.isAttached }?.let { onDragTo(it.localToRoot(change.position)) }
                    },
                    onDragEnd = { onDrop() },
                    onDragCancel = { onDrop() },
                )
            }
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .testTag("rail:${task.nodeId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                // Collapsed, like everywhere else a title is shown away from the page it is typed
                // on — see CalendarBucketer.shown. A rail chip is two lines of a phone's width, and
                // `[[Call Bob|^9f1e…]]` spends both of them on punctuation.
                Links.plain(task.title.orEmpty()).ifEmpty { "Untitled" },
                fontSize = YantraType.section,
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
                    fontSize = YantraType.dense,
                    color = if (armed) y.accentText.copy(alpha = 0.75f) else y.textDim,
                )
            }
        }
        // The task itself, for when what you want is to read it rather than to schedule it.
        if (roomForOpen) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpen)
                    .testTag("open:${task.nodeId}"),
                contentAlignment = Alignment.Center,
            ) {
                Text("›", fontSize = YantraType.row, color = if (armed) y.accentText else y.textDim)
            }
        }
    }
    }
}

/**
 * How wide a row has to be before it can hold two targets.
 *
 * A 28dp button and a title worth reading beside it. Below this the row carries one gesture, and the
 * one it keeps is the one the rail exists for.
 */
private val ROOM_FOR_OPEN = 120.dp

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
