package ie.napkin.supertasks

import ie.napkin.supertasks.data.time.endOfDay
import ie.napkin.supertasks.data.time.localDateOf
import ie.napkin.supertasks.data.time.localMidnight
import ie.napkin.supertasks.data.time.startOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Day boundaries used to be recomputed at six call sites in two idioms; these pin the shared
 * ones, including the DST days where the old `Calendar` version (midnight + 24h) was an hour off.
 */
class DaysTest {

    private val dublin: ZoneId = ZoneId.of("Europe/Dublin")
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")   // UTC+5:30, no DST

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `localMidnight floors to the start of the local day`() {
        val date = LocalDate.of(2026, 3, 14)
        val evening = at(date, LocalTime.of(23, 45), dublin)
        assertEquals(startOfDay(date, dublin), localMidnight(evening, dublin))
    }

    @Test
    fun `localMidnight is idempotent`() {
        val m = localMidnight(at(LocalDate.of(2026, 7, 1), LocalTime.NOON, kolkata), kolkata)
        assertEquals(m, localMidnight(m, kolkata))
    }

    @Test
    fun `a half-hour-offset zone still lands on its own midnight`() {
        // Kolkata midnight is never a UTC-midnight multiple, which is what the pre-v4 Due bug
        // assumed everywhere.
        val date = LocalDate.of(2026, 7, 1)
        val m = localMidnight(at(date, LocalTime.of(9, 0), kolkata), kolkata)
        assertEquals(date, localDateOf(m, kolkata))
        assertTrue(m % 86_400_000L != 0L)
    }

    @Test
    fun `end of day is the last millisecond before the next midnight`() {
        val date = LocalDate.of(2026, 6, 10)
        assertEquals(startOfDay(date.plusDays(1), dublin) - 1, endOfDay(date, dublin))
        assertEquals(date, localDateOf(endOfDay(date, dublin), dublin))
    }

    @Test
    fun `end of day is correct on the short DST day`() {
        // Clocks go forward in Dublin on 2026-03-29: the day is 23 hours long, so midnight plus
        // 24 hours would have spilled an hour into the 30th and swept the next day's tasks into
        // a "due today" list.
        val short = LocalDate.of(2026, 3, 29)
        val end = endOfDay(short, dublin)
        assertEquals(short, localDateOf(end, dublin))
        assertEquals(23L * 3600 * 1000 - 1, end - startOfDay(short, dublin))
    }

    @Test
    fun `end of day is correct on the long DST day`() {
        // Clocks go back on 2026-10-25: a 25-hour day, where midnight plus 24 hours would have
        // stopped an hour early and hidden late-evening tasks from "due today".
        val long = LocalDate.of(2026, 10, 25)
        val end = endOfDay(long, dublin)
        assertEquals(long, localDateOf(end, dublin))
        assertEquals(25L * 3600 * 1000 - 1, end - startOfDay(long, dublin))
    }
}
