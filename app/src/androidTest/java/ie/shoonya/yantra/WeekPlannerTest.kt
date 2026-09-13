package ie.shoonya.yantra

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import ie.shoonya.yantra.ui.calendar.DayItem
import ie.shoonya.yantra.ui.calendar.WeekTimeline
import ie.shoonya.yantra.ui.theme.SuperTasksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The multi-day view is a planner, not a picture — CALENDAR_PLAN.md §10.
 *
 * It used to be read-only: nothing on it could be dragged, stretched or tapped out, so changing
 * anything about Tuesday meant first navigating to Tuesday. It is the same lane as the day view now,
 * three or seven times across, and these check that each column edits *its own* day — the failure
 * that a shared lane invites is every column writing to the first one.
 */
class WeekPlannerTest {

    @get:Rule
    val rule = createComposeRule()

    private val week = (0..2).map { LocalDate.parse("2026-09-14").plusDays(it.toLong()) }

    private fun event(id: String, day: LocalDate, from: String, to: String) = DayItem.Event(
        nodeId = id,
        title = id,
        start = LocalDateTime.of(day, LocalTime.parse(from)),
        end = LocalDateTime.of(day, LocalTime.parse(to)),
        allDay = false,
        location = null,
        repeating = false,
        cancelled = false,
        sortKey = 0,
    )

    private class Recorder {
        var span: Triple<String, LocalDateTime, LocalDateTime>? = null
        var tapped: Pair<LocalDate, LocalTime>? = null
        var opened: DayItem? = null
    }

    private fun show(items: Map<LocalDate, List<DayItem>>): Recorder {
        val rec = Recorder()
        rule.setContent {
            SuperTasksTheme {
                WeekTimeline(
                    week = week,
                    days = items,
                    selected = week.first(),
                    onSelectDay = { },
                    onOpen = { rec.opened = it },
                    onEmptyTap = { d, t -> rec.tapped = d to t },
                    onSpan = { id, from, to -> rec.span = Triple(id, from, to) },
                    onCreateRange = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return rec
    }

    @Test
    fun aBlockInTheWeekCanBeStretchedWithoutLeavingIt() {
        val rec = show(mapOf(week[1] to listOf(event("a", week[1], "09:00", "10:00"))))
        // Hold to pick it out, then pull the foot down. The handles are the same ones the day view
        // uses, which is the whole reason this works at all.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("block:a").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("block:a").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        rule.onNodeWithTag("grip:bottom:a").performTouchInput {
            down(center)
            repeat(8) { i -> moveTo(center.copy(y = center.y + 120f * (i + 1) / 8)) }
            up()
        }
        rule.waitForIdle()

        val (id, from, to) = rec.span ?: error("the week should be editable")
        assertEquals("a", id)
        // The day it belonged to, not the first column.
        assertEquals(week[1], from.toLocalDate())
        assertEquals("the start must not move", 9, from.hour)
        assertTrue("should have grown, got $to", to.isAfter(LocalDateTime.of(week[1], LocalTime.parse("10:00"))))
    }

    @Test
    fun tappingAColumnOffersAnHourOnThatColumnsDay() {
        val rec = show(emptyMap())
        rule.onAllNodesWithTag("ruler")[2].performTouchInput { click() }
        rule.waitForIdle()
        val tapped = rec.tapped
        assertNotNull("a tap on bare ruler must offer an hour", tapped)
        assertEquals("the third column is the third day", week[2], tapped!!.first)
    }

    @Test
    fun aTapOnABlockStillOpensIt() {
        val rec = show(mapOf(week[0] to listOf(event("a", week[0], "09:00", "10:00"))))
        rule.onNodeWithTag("block:a").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals("a", rec.opened?.nodeId)
    }
}
