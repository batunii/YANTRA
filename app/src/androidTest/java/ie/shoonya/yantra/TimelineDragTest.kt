package ie.shoonya.yantra

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import ie.shoonya.yantra.ui.calendar.DayItem
import ie.shoonya.yantra.ui.calendar.DayTimeline
import ie.shoonya.yantra.ui.theme.SuperTasksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * That a block can actually be dragged — CALENDAR_PLAN.md §12.
 *
 * Written because `adb shell input motionevent` cannot produce a gesture Compose reads as a long
 * press followed by a drag: each event goes through its own process, and the detector tracks pointer
 * identity across them. A tap could be verified from the shell and a drag could not, which left the
 * whole gesture unverifiable from outside. `performTouchInput` drives the real detectors.
 */
class TimelineDragTest {

    @get:Rule
    val rule = createComposeRule()

    private val day: LocalDate = LocalDate.parse("2026-09-11")

    private fun event(id: String, from: String, to: String) = DayItem.Event(
        nodeId = id,
        title = id,
        start = LocalDateTime.parse("2026-09-11T$from"),
        end = LocalDateTime.parse("2026-09-11T$to"),
        allDay = false,
        location = null,
        repeating = false,
        cancelled = false,
        sortKey = 0,
    )

    private class Recorder {
        var moved: Pair<String, LocalDateTime>? = null
        var resized: Pair<String, LocalDateTime>? = null
        var created: Pair<LocalDateTime, LocalDateTime>? = null
        var opened: DayItem? = null
        var tapped: java.time.LocalTime? = null
    }

    private fun show(items: List<DayItem>): Recorder {
        val rec = Recorder()
        rule.setContent {
            SuperTasksTheme {
                DayTimeline(
                    day = day,
                    items = items,
                    onOpen = { rec.opened = it },
                    onEmptyTap = { rec.tapped = it },
                    onMove = { id, at -> rec.moved = id to at },
                    onResize = { id, at -> rec.resized = id to at },
                    onCreateRange = { a, b -> rec.created = a to b },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return rec
    }

    /**
     * Hold, then drag — the gesture `detectDragGesturesAfterLongPress` waits for.
     *
     * The hold has to advance the **main** clock, not just the event clock. `advanceEventTime` moves
     * the timestamps on injected events; the long-press timeout is a coroutine `withTimeout` running
     * on the composition clock, so with `advanceEventTime` alone the press never ripens and every
     * drag reads as a scroll. Splitting the gesture across separate `performTouchInput` calls is
     * what lets the clock move in the middle of it.
     */
    private fun dragOn(tag: String, byY: Float, fromBottom: Boolean = false) {
        val node = rule.onNodeWithTag(tag)
        rule.mainClock.autoAdvance = false
        var anchorY = 0f
        node.performTouchInput {
            // 92% down rather than flush against the bottom: the block carries 2dp of bottom
            // padding, so the last few pixels of the semantics node are outside the gesture node
            // and a press there starts nothing at all.
            anchorY = if (fromBottom) height * 0.92f else centerY
            down(androidx.compose.ui.geometry.Offset(centerX, anchorY))
        }
        rule.mainClock.advanceTimeBy(1_000)          // past the long-press timeout
        node.performTouchInput {
            repeat(8) { i ->
                moveTo(androidx.compose.ui.geometry.Offset(centerX, anchorY + byY * (i + 1) / 8))
            }
            up()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    @Test
    fun aTapOnABlockOpensIt() {
        val rec = show(listOf(event("a", "09:00", "10:00")))
        rule.onNodeWithTag("block:a").performClick()
        rule.waitForIdle()
        assertEquals("a", rec.opened?.nodeId)
        assertNull("a tap must not move anything", rec.moved)
    }

    @Test
    fun aLongPressDragMovesTheBlockLater() {
        val rec = show(listOf(event("a", "09:00", "10:00")))
        dragOn("block:a", byY = 180f)          // roughly three hours down
        val moved = rec.moved
        assertNotNull("the drag should have committed a move", moved)
        assertEquals("a", moved!!.first)
        assertTrue("should have moved later, got ${moved.second}", moved.second.hour > 9)
        assertEquals("must land on a quarter hour", 0, moved.second.minute % 15)
    }

    @Test
    fun aDragUpMovesItEarlier() {
        val rec = show(listOf(event("a", "14:00", "15:00")))
        dragOn("block:a", byY = -180f)
        assertTrue("should have moved earlier", rec.moved!!.second.hour < 14)
    }

    @Test
    fun aDragOnTheFootResizesRatherThanMoves() {
        val rec = show(listOf(event("a", "09:00", "10:00")))
        // Start the press at the very bottom of the block: that is the grip.
        dragOn("block:a", byY = 180f, fromBottom = true)
        assertNull("a foot drag must not move the block", rec.moved)
        val resized = rec.resized
        assertNotNull("the foot should resize", resized)
        assertTrue("should have grown, got ${resized!!.second}", resized.second.hour >= 10)
    }

    @Test
    fun aLongPressDragOnEmptyRulerDrawsARange() {
        val rec = show(emptyList())
        dragOn("ruler", byY = 200f)
        val made = rec.created
        assertNotNull("dragging bare ruler should offer a block", made)
        assertTrue("must span forwards", made!!.second.isAfter(made.first))
        assertEquals("must land on a quarter hour", 0, made.second.minute % 15)
    }

    @Test
    fun aPlainTapOnEmptyRulerStillOffersAnHour() {
        val rec = show(emptyList())
        rule.onNodeWithTag("ruler").performTouchInput { click() }
        rule.waitForIdle()
        assertNotNull("a tap is the short version of the same gesture", rec.tapped)
        assertNull(rec.created)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClick() =
    performTouchInput { click() }
