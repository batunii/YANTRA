package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ie.shoonya.yantra.data.db.RailTask
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** How much of the day view the day itself gets when the rail is beside it. */
const val DAY_SHARE = 0.75f

/** What a sitting is worth when a single tap placed it and nobody said how long. */
val SITTING_LENGTH: Duration = Duration.ofHours(1)

/**
 * The day, and the tasks waiting for a place in it — CALENDAR_PLAN.md §13A.
 *
 * Three quarters timeline, one quarter rail. The rail is what a calendar page is otherwise missing:
 * a task with no date cannot be drawn on a calendar at all, which is precisely the task most in need
 * of being given a time.
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
    onMove: (String, LocalDateTime) -> Unit,
    onResize: (String, LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier) {
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
            onMove = onMove,
            onResize = onResize,
            // A drag with a task held up is that task, exactly that long — the length was the point
            // of dragging rather than tapping. With nothing held, the range is a question.
            onCreateRange = { from, to ->
                val held = armed
                if (held != null) onSit(held.nodeId, from, Duration.between(from, to))
                else onMark(from, to)
            },
            modifier = Modifier
                .weight(if (railOpen) DAY_SHARE else 1f)
                .padding(horizontal = 8.dp),
        )
        if (railOpen) {
            RailDivider()
            TaskRail(
                shelves = shelves,
                shelf = shelf,
                armed = armed?.nodeId,
                onShelf = onShelf,
                onArm = onArm,
                onOpen = onOpenTask,
                modifier = Modifier
                    .weight(1f - DAY_SHARE)
                    .padding(start = 8.dp, end = 6.dp, top = 4.dp),
            )
        }
    }
}
