package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.dp
import ie.shoonya.yantra.data.db.RailTask
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * How much the day itself gets when the rail is under it.
 *
 * Three quarters left the rail two rows deep, which is a list you scroll rather than one you read.
 * What the rail costs here is *height*, and a timeline can give some: an empty afternoon still looks
 * empty at three fifths of a screen, which is the whole reason to draw one.
 */
const val DAY_SHARE_STACKED = 0.6f

/**
 * And when it is beside the day instead.
 *
 * Left alone at three quarters, because what the rail wants side by side is *width*, and a quarter
 * of a tablet is already more of that than a task title needs. Widening it here would take from the
 * day to buy nothing.
 */
const val DAY_SHARE_WIDE = 0.75f

/** What a sitting is worth when a single tap or a drop placed it and nobody said how long. */
val SITTING_LENGTH: Duration = Duration.ofHours(1)

/** Everything on a timeline lands on a quarter hour. A sitting that began at 14:07 is one nobody chose. */
private const val SNAP_MINUTES = 15

private const val MINUTES_IN_DAY = 24 * 60

/** How fast the day scrolls under a drag held at its edge, per frame. */
private const val EDGE_SCROLL_PX = 12f

/**
 * The day, and the tasks waiting for a place in it — CALENDAR_PLAN.md §13A.
 *
 * Timeline and rail, and **which way the screen is cut depends on the screen**. A phone splits top
 * and bottom, so the rail gets the full width and its four shelves fit across it; a tablet splits
 * left and right, where there is width to spare and stacking would waste it. Sliced the other way on
 * a phone, the rail came out about ninety points wide: shelf names clipped at the edge and task
 * titles wrapping to three lines, which is a list you cannot read to choose from.
 *
 * The shares differ with the cut — see [DAY_SHARE_STACKED] and [DAY_SHARE_WIDE].
 *
 * The rail is what a calendar page is otherwise missing: a task with no date cannot be drawn on a
 * calendar at all, which is precisely the task most in need of being given a time.
 *
 * **Arm, then place.** Tapping a task lifts it; the next tap on an hour puts it there. Drag between
 * two independently scrolling surfaces is the accelerator and it is the one gesture here that can
 * pass a test and fail a real finger, so the reliable one is what ships — see CALENDAR_PLAN.md §14.
 *
 * Kept out of `CalendarScreen` so the arming rules are a thing a test can drive rather than a branch
 * buried three composables deep in a screen that needs a database to draw.
 */
@Composable
fun DayWithRail(
    day: LocalDate,
    items: List<DayItem>,
    shelves: Map<RailBucket, List<RailTask>>,
    shelf: RailBucket,
    armed: RailTask?,
    railOpen: Boolean,
    /** Side by side rather than stacked. True where there is width to spare — see the class note. */
    sideBySide: Boolean,
    onShelf: (RailBucket) -> Unit,
    onArm: (RailTask?) -> Unit,
    /** The task itself, reached from the rail without arming it. */
    onOpenTask: (String) -> Unit,
    onOpen: (DayItem) -> Unit,
    /** A tap on a free hour with nothing held up. */
    onNewEvent: (LocalTime) -> Unit,
    /** A range dragged out with nothing held up — §13B asks what goes in it. */
    onMark: (LocalDateTime, LocalDateTime) -> Unit,
    /** Time set aside for a task. No sheet: a sitting has no name to ask for. */
    onSit: (String, LocalDateTime, Duration) -> Unit,
    /** A block now runs from here to here — moved, or stretched from either end. */
    onSpan: (String, LocalDateTime, LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ---- dragging a task out of the rail and onto an hour ----
    //
    // The two surfaces scroll independently and neither is inside the other, so the drag is carried
    // in root coordinates: the rail row reports where the finger is, the timeline reports where its
    // hour lane is, and the arithmetic that turns one into an hour happens here, between them.
    val scroll = rememberScrollState()
    var lane by remember { mutableStateOf<LaneMetrics?>(null) }
    var dragged by remember { mutableStateOf<RailTask?>(null) }
    var finger by remember { mutableStateOf<Offset?>(null) }

    // The minute under the finger, read rather than remembered.
    //
    // Deliberately a function of the two states rather than a value computed once per composition:
    // the drop handler lives inside a `pointerInput` keyed on the row, so it is the lambda from the
    // *first* composition for the whole life of the gesture. A captured minute would be the minute
    // as it was before the finger moved — which is to say null, every time, for every drag.
    // Reading the states through it is safe because `remember` hands back the same holders.
    fun minuteUnderFinger(): Int? {
        val l = lane?.takeIf { it.coords.isAttached } ?: return null
        val p = finger ?: return null
        // Clipped bounds, so this is the *visible* lane — dragging over the rail's own half of the
        // screen must not silently schedule something on an hour nobody can see.
        if (!l.coords.boundsInRoot().contains(p)) return null
        val top = l.coords.localToRoot(Offset.Zero).y
        // Centred on the finger rather than hung below it, so the hour you are pointing at is the
        // hour that highlights.
        val raw = ((p.y - top) / l.hourPx * 60f).toInt() - SITTING_LENGTH.toMinutes().toInt() / 2
        val snapped = (raw / SNAP_MINUTES) * SNAP_MINUTES
        return snapped.coerceIn(0, MINUTES_IN_DAY - SITTING_LENGTH.toMinutes().toInt())
    }

    /** What the day draws while a task is on its way in. */
    val ghostMinute: Int? = if (dragged == null) null else minuteUnderFinger()

    // Pull toward an edge and the day comes to you. Without this you could only drop on an hour
    // already on screen, which on a phone is about five of them.
    LaunchedEffect(dragged) {
        if (dragged == null) return@LaunchedEffect
        while (true) {
            val l = lane?.takeIf { it.coords.isAttached }
            val p = finger
            if (l != null && p != null) {
                val visible = l.coords.boundsInRoot()
                val edge = l.hourPx * 0.75f
                val by = when {
                    p.y < visible.top + edge && p.y > visible.top - edge -> -EDGE_SCROLL_PX
                    p.y > visible.bottom - edge && p.y < visible.bottom + edge -> EDGE_SCROLL_PX
                    else -> 0f
                }
                if (by != 0f) scroll.scrollBy(by)
            }
            withFrameNanos { }
        }
    }

    // One set of callbacks, two arrangements. The wiring is the feature; which way the screen is cut
    // is a question about the screen, so the two are kept apart rather than the whole thing written
    // out twice with one `Row` changed to a `Column`.
    val timeline: @Composable (Modifier) -> Unit = { mod ->
        DayTimeline(
            day = day,
            items = items,
            onOpen = onOpen,
            // With a task held up, a tap on an hour is where it goes. With nothing held, a tap on a
            // free hour still means what it always meant.
            onEmptyTap = { at ->
                val held = armed
                if (held != null) onSit(held.nodeId, day.atTime(at), SITTING_LENGTH)
                else onNewEvent(at)
            },
            onSpan = onSpan,
            // A drag with a task held up is that task, exactly that long — the length was the point
            // of dragging rather than tapping. With nothing held, the range is a question.
            onCreateRange = { from, to ->
                val held = armed
                if (held != null) onSit(held.nodeId, from, Duration.between(from, to))
                else onMark(from, to)
            },
            scroll = scroll,
            onLane = { lane = it },
            ghost = ghostMinute?.let { it..it + SITTING_LENGTH.toMinutes().toInt() },
            modifier = mod.padding(horizontal = 8.dp),
        )
    }
    val rail: @Composable (Modifier) -> Unit = { mod ->
        TaskRail(
            shelves = shelves,
            shelf = shelf,
            // The row being dragged wears the same mark as an armed one: it is the same state —
            // this task, waiting for an hour — arrived at by a different gesture.
            armed = (dragged ?: armed)?.nodeId,
            onShelf = onShelf,
            onArm = onArm,
            onOpen = onOpenTask,
            onDragStart = { dragged = it; finger = null },
            onDragTo = { finger = it },
            onDrop = {
                val task = dragged
                val minute = minuteUnderFinger()
                // Dropped on the day, or dropped nowhere. Nowhere is not a failure — it is how you
                // change your mind halfway, so it leaves no sitting and says nothing.
                if (task != null && minute != null) {
                    onSit(task.nodeId, day.atStartOfDay().plusMinutes(minute.toLong()), SITTING_LENGTH)
                }
                dragged = null
                finger = null
            },
            modifier = mod.padding(horizontal = 8.dp),
        )
    }

    if (sideBySide) {
        Row(modifier) {
            timeline(Modifier.weight(if (railOpen) DAY_SHARE_WIDE else 1f))
            if (railOpen) {
                RailDivider()
                rail(Modifier.weight(1f - DAY_SHARE_WIDE).padding(top = 4.dp))
            }
        }
    } else {
        Column(modifier) {
            timeline(Modifier.weight(if (railOpen) DAY_SHARE_STACKED else 1f))
            if (railOpen) {
                RailDividerHorizontal()
                rail(Modifier.weight(1f - DAY_SHARE_STACKED).padding(top = 6.dp))
            }
        }
    }
}
