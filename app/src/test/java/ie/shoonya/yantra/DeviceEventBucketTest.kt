package ie.shoonya.yantra

import ie.shoonya.yantra.data.device.DeviceEvent
import ie.shoonya.yantra.ui.calendar.CalendarBucketer
import ie.shoonya.yantra.ui.calendar.DayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Somebody else's events, put on the right day — CALENDAR_PLAN.md §5.
 *
 * The provider hands back **instants**, already expanded, which moves the whole problem from
 * recurrence to timezones. Every case here is one of the ways a millisecond lands on the wrong day:
 * an all-day event that the provider stores as UTC midnight and the reader is not in UTC, a meeting
 * that ends exactly at midnight, and one that runs past it.
 */
class DeviceEventBucketTest {

    private val dublin: ZoneId = ZoneId.of("Europe/Dublin")
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
    // West of UTC, which is the half of the world an all-day bug hides from a developer in Europe.
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun at(local: String, zone: ZoneId = dublin): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun utcMidnight(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun event(
        id: Long = 1,
        title: String = "Standup",
        begin: Long,
        end: Long,
        allDay: Boolean = false,
    ) = DeviceEvent(
        instanceId = id, eventId = id * 10, title = title,
        beginUtc = begin, endUtc = end, allDay = allDay, location = null, color = null,
        uid = null,
    )

    private fun bucket(events: List<DeviceEvent>, zone: ZoneId = dublin) = CalendarBucketer.bucket(
        events = emptyList(),
        tasks = emptyList(),
        titles = emptyMap(),
        device = events,
        from = LocalDate.parse("2026-09-01"),
        toExclusive = LocalDate.parse("2026-10-01"),
        zone = zone,
    )

    @Test
    fun `a meeting lands on its own day`() {
        val days = bucket(listOf(event(begin = at("2026-09-14T09:00"), end = at("2026-09-14T09:30"))))
        assertEquals(setOf(LocalDate.parse("2026-09-14")), days.keys)
        val item = days.getValue(LocalDate.parse("2026-09-14")).single()
        assertTrue("must be somebody else's kind", item is DayItem.Device)
        assertEquals("Standup", item.title)
    }

    /**
     * The one that actually goes wrong.
     *
     * An all-day event is stored by the provider as UTC midnight to UTC midnight. Resolved in the
     * reader's zone it lands hours either side of the day it means — nine in the morning on the
     * right day in Tokyo, or the evening before in the Americas — and a birthday on the wrong day
     * is the bug nobody forgives.
     */
    @Test
    fun `an all-day event is the day it says, in any zone`() {
        val birthday = event(
            begin = utcMidnight("2026-09-14"),
            end = utcMidnight("2026-09-15"),
            allDay = true,
        )
        assertEquals(setOf(LocalDate.parse("2026-09-14")), bucket(listOf(birthday), dublin).keys)
        assertEquals(setOf(LocalDate.parse("2026-09-14")), bucket(listOf(birthday), tokyo).keys)
        // Read in the reader's zone this lands on the 13th at seven in the evening, which is the
        // failure the UTC rule exists to prevent.
        assertEquals(setOf(LocalDate.parse("2026-09-14")), bucket(listOf(birthday), newYork).keys)
    }

    @Test
    fun `a one-day all-day event does not bleed into the next day anywhere`() {
        val day = event(begin = utcMidnight("2026-09-14"), end = utcMidnight("2026-09-15"), allDay = true)
        listOf(dublin, tokyo, newYork).forEach { zone ->
            assertEquals("in $zone", setOf(LocalDate.parse("2026-09-14")), bucket(listOf(day), zone).keys)
        }
    }

    @Test
    fun `a three-day all-day event covers three days and not a fourth`() {
        val conference = event(
            begin = utcMidnight("2026-09-14"),
            end = utcMidnight("2026-09-17"),
            allDay = true,
        )
        assertEquals(
            listOf("2026-09-14", "2026-09-15", "2026-09-16").map(LocalDate::parse).toSet(),
            bucket(listOf(conference)).keys,
        )
    }

    @Test
    fun `a meeting ending at midnight belongs to the day it started`() {
        val days = bucket(listOf(event(begin = at("2026-09-14T23:00"), end = at("2026-09-15T00:00"))))
        assertEquals(setOf(LocalDate.parse("2026-09-14")), days.keys)
    }

    @Test
    fun `a meeting running past midnight lands on both days`() {
        val days = bucket(listOf(event(begin = at("2026-09-14T23:00"), end = at("2026-09-15T00:30"))))
        assertEquals(
            setOf(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-15")),
            days.keys,
        )
    }

    @Test
    fun `anything outside the window is not drawn`() {
        assertTrue(bucket(listOf(event(begin = at("2026-11-02T09:00"), end = at("2026-11-02T10:00")))).isEmpty())
    }

    @Test
    fun `a device event never carries a node id that could be written to`() {
        // Every gesture that changes a block asks for a node id. This one has no node, and the
        // prefix is what makes a mistaken write fail loudly rather than land somewhere strange.
        val item = bucket(listOf(event(begin = at("2026-09-14T09:00"), end = at("2026-09-14T10:00"))))
            .getValue(LocalDate.parse("2026-09-14")).single()
        assertTrue("got ${item.nodeId}", item.nodeId.startsWith("device:"))
    }

    @Test
    fun `theirs and ours share a day without one hiding the other`() {
        val days = CalendarBucketer.bucket(
            events = listOf(
                indexed(
                    ie.shoonya.yantra.data.db.EventEntity(
                        nodeId = "e1", startLocal = "2026-09-14T09:00", endLocal = "2026-09-14T10:00",
                        allDay = false, startUtc = 0, endUtc = 0,
                    ),
                    "Mine",
                ),
            ),
            tasks = emptyList(),
            titles = mapOf("e1" to "Mine"),
            device = listOf(event(title = "Theirs", begin = at("2026-09-14T09:30"), end = at("2026-09-14T10:30"))),
            from = LocalDate.parse("2026-09-01"),
            toExclusive = LocalDate.parse("2026-10-01"),
            zone = dublin,
        )
        val items = days.getValue(LocalDate.parse("2026-09-14"))
        assertEquals(2, items.size)
        assertEquals(listOf("Mine", "Theirs"), items.map { it.title })
    }
}
