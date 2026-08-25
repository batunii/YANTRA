package ie.napkin.supertasks.data.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What "a day" means, in one place.
 *
 * Due, Deadline, the Today rule, the widget's overdue check and the seeder all have to agree on
 * where a day starts, and they used to answer it six separate times in three idioms — `java.time`
 * in the repositories, seeder, widget and migration; `Calendar` in the query compiler. Agreeing by
 * coincidence is not agreeing: the [Calendar] version computed the end of today as midnight plus
 * 24 hours, which is an hour wrong on both DST changeover days, so twice a year "due today" and
 * "due today" meant different things depending on which one asked.
 *
 * Every function takes an explicit [zone] defaulting to the system one, so the logic is testable
 * without touching the device clock.
 */

/** The local calendar date [millis] falls on. */
fun localDateOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** Floor an instant to the local-midnight instant of its local calendar day. */
fun localMidnight(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    startOfDay(localDateOf(millis, zone), zone)

/** The local-midnight instant that opens [date]. */
fun startOfDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atStartOfDay(zone).toInstant().toEpochMilli()

/** Local midnight that opened today. */
fun todayMidnight(zone: ZoneId = ZoneId.systemDefault()): Long =
    startOfDay(LocalDate.now(zone), zone)

/**
 * The last millisecond of [date]'s local day — derived from the *next* day's midnight rather than
 * by adding 24 hours, so a 23- or 25-hour DST day still ends when it actually ends.
 */
fun endOfDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    startOfDay(date.plusDays(1), zone) - 1

/** The last millisecond of today, local. */
fun todayEnd(zone: ZoneId = ZoneId.systemDefault()): Long = endOfDay(LocalDate.now(zone), zone)
