package ie.shoonya.yantra

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import ie.shoonya.yantra.data.db.RailTask
import ie.shoonya.yantra.ui.calendar.DayWithRail
import ie.shoonya.yantra.ui.calendar.RailBucket
import ie.shoonya.yantra.ui.calendar.railShelves
import ie.shoonya.yantra.ui.theme.SuperTasksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Getting a task onto the day — CALENDAR_PLAN.md §13A.
 *
 * The flow this drives is arm-then-place: tap a task in the rail, tap an hour, and a sitting exists
 * with no sheet in between. It is driven through [DayWithRail] rather than through a hand-written
 * copy of the rules, because the rules *are* the feature — which tap means "put it there" and which
 * still means "make an event" is the only thing here that can be wrong.
 */
class TaskRailTest {

    @get:Rule
    val rule = createComposeRule()

    private val day: LocalDate = LocalDate.parse("2026-09-12")
    private val dublin: ZoneId = ZoneId.of("Europe/Dublin")

    private fun task(id: String, due: String? = null) = RailTask(
        nodeId = id,
        title = id,
        dueMillis = due?.let { LocalDate.parse(it).atStartOfDay(dublin).toInstant().toEpochMilli() },
        deadlineMillis = null,
        sittings = 0,
    )

    private class Recorder {
        var sat: Triple<String, LocalDateTime, Duration>? = null
        var newEvent: LocalTime? = null
        var opened: String? = null
    }

    /** The rail beside the day, wired the way the screen wires it, with the arming state held here. */
    private fun show(tasks: List<RailTask>): Recorder {
        val rec = Recorder()
        rule.setContent {
            SuperTasksTheme {
                var shelf by remember { mutableStateOf(RailBucket.TODAY) }
                var armed by remember { mutableStateOf<RailTask?>(null) }
                DayWithRail(
                    day = day,
                    items = emptyList(),
                    shelves = railShelves(tasks, day, dublin),
                    shelf = shelf,
                    armed = armed,
                    railOpen = true,
                    onShelf = { shelf = it; armed = null },
                    onArm = { armed = it },
                    onOpenTask = { rec.opened = it },
                    onOpen = { },
                    onNewEvent = { rec.newEvent = it },
                    onMark = { _, _ -> },
                    onSit = { id, at, len -> rec.sat = Triple(id, at, len); armed = null },
                    onMove = { _, _ -> },
                    onResize = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return rec
    }

    /** The whole feature in one gesture: lift a task off the rail, put it on an hour. */
    @Test
    fun tapATaskThenTapAnHourAndTheSittingExists() {
        val rec = show(listOf(task("t1", due = day.toString())))

        rule.onNodeWithTag("rail:t1").performClick()
        // The rail says what it is now waiting for, and says it only while it is true.
        rule.onNodeWithText("Tap a time on the day").assertIsDisplayed()

        rule.onNodeWithTag("ruler").performTouchInput { click() }
        rule.waitForIdle()

        val (id, at, len) = rec.sat ?: error("no sitting was made")
        assertEquals("t1", id)
        assertEquals(day, at.toLocalDate())
        assertEquals(Duration.ofHours(1), len)
        // And it did not also offer to make an event — a tap means one thing at a time.
        assertNull(rec.newEvent)

        // The task goes back down once it has landed, so the next tap on the day is not a second
        // sitting for a task you have already placed.
        rule.onNodeWithText("Tap a time on the day").assertDoesNotExist()
    }

    @Test
    fun aTapOnAnHourWithNothingHeldStillMeansANewEvent() {
        val rec = show(listOf(task("t1", due = day.toString())))
        rule.onNodeWithTag("ruler").performTouchInput { click() }
        rule.waitForIdle()
        assertNull(rec.sat)
        assertNotNull("bare ruler still offers an hour", rec.newEvent)
    }

    @Test
    fun tappingTheSameTaskTwicePutsItBackDown() {
        val rec = show(listOf(task("t1", due = day.toString())))
        rule.onNodeWithTag("rail:t1").performClick()
        rule.onNodeWithTag("rail:t1").performClick()
        rule.onNodeWithTag("ruler").performTouchInput { click() }
        rule.waitForIdle()
        assertNull("a task put back down must not land on the day", rec.sat)
        assertNotNull("and the tap goes back to meaning what it meant", rec.newEvent)
    }

    @Test
    fun aLongPressOpensTheTaskRatherThanArmingIt() {
        val rec = show(listOf(task("t1", due = day.toString())))
        rule.onNodeWithTag("rail:t1").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("t1", rec.opened)
        rule.onNodeWithText("Tap a time on the day").assertDoesNotExist()
    }

    @Test
    fun theShelvesShowDifferentTasks() {
        show(listOf(task("duetoday", due = day.toString()), task("backlog")))
        rule.onNodeWithTag("rail:duetoday").assertIsDisplayed()
        // Scrolled to first: four words do not fit a quarter of a phone, which is why the shelf
        // chips sit in a scrolling row in the first place.
        rule.onNodeWithText("Undated").performScrollTo().performClick()
        rule.onNodeWithTag("rail:backlog").assertIsDisplayed()
        rule.onNodeWithTag("rail:duetoday").assertDoesNotExist()
    }
}
