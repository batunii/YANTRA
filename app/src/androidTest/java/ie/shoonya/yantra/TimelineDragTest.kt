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
        var span: Triple<String, LocalDateTime, LocalDateTime>? = null
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
                    onSpan = { id, from, to -> rec.span = Triple(id, from, to) },
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
    private fun dragOn(tag: String, byY: Float, fromBottom: Boolean = false, fromTop: Boolean = false) {
        val node = rule.onNodeWithTag(tag)
        rule.mainClock.autoAdvance = false
        var anchorY = 0f
        node.performTouchInput {
            // 92% down rather than flush against the bottom: the block carries 2dp of bottom
            // padding, so the last few pixels of the semantics node are outside the gesture node
            // and a press there starts nothing at all.
            anchorY = when {
                fromBottom -> height * 0.92f
                fromTop -> height * 0.04f
                else -> centerY
            }
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
        assertNull("a tap must not move anything", rec.span)
    }

    @Test
    fun aLongPressDragMovesTheBlockLater() {
        val rec = show(listOf(event("a", "09:00", "10:00")))
        dragOn("block:a", byY = 180f)          // roughly three hours down
        val span = rec.span
        assertNotNull("the drag should have committed a span", span)
        val (id, from, to) = span!!
        assertEquals("a", id)
        assertTrue("should have moved later, got $from", from.hour > 9)
        assertEquals("must land on a quarter hour", 0, from.minute % 15)
        assertEquals("a move keeps its length", 60, java.time.Duration.between(from, to).toMinutes())
    }

    @Test
    fun aDragUpMovesItEarlier() {
        val rec = show(listOf(event("a", "14:00", "15:00")))
        dragOn("block:a", byY = -180f)
        assertTrue("should have moved earlier", rec.span!!.second.hour < 14)
    }

    @Test
    fun aDragOnTheFootStretchesTheEndAndLeavesTheStart() {
        val rec = show(listOf(event("a", "09:00", "10:00")))
        // Start the press at the very bottom of the block: that is the handle.
        dragOn("block:a", byY = 180f, fromBottom = true)
        val (_, from, to) = rec.span ?: error("the foot should stretch the block")
        assertEquals("the start must not move", 9, from.hour)
        assertTrue("should have grown, got $to", to.hour >= 10)
    }

    /**
     * The other handle, which is the new one.
     *
     * A block has two edges and a person stretching an hour has no reason to prefer one of them:
     * pulling the top back to half past eight is the same thought as pushing the foot out to eleven.
     */
    @Test
    fun aDragOnTheHeadStretchesTheStartAndLeavesTheEnd() {
        val rec = show(listOf(event("a", "09:00", "11:00")))
        dragOn("block:a", byY = -60f, fromTop = true)
        val (_, from, to) = rec.span ?: error("the head should stretch the block")
        assertEquals("the end must not move", 11, to.hour)
        assertTrue("the start should have come earlier, got $from", from.isBefore(LocalDateTime.parse("2026-09-11T09:00")))
        assertEquals("must land on a quarter hour", 0, from.minute % 15)
    }

    @Test
    fun aHeadDragCannotPullTheBlockThroughItself() {
        val rec = show(listOf(event("a", "09:00", "10:00")))
        // Far past the foot. The start must stop short of it rather than invert the block.
        dragOn("block:a", byY = 600f, fromTop = true)
        val (_, from, to) = rec.span ?: error("expected a span")
        assertTrue("start must stay before end, got $from..$to", from.isBefore(to))
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
