package ie.napkin.supertasks

import ie.napkin.supertasks.data.db.DueMigrationLogic
import ie.napkin.supertasks.data.filter.DateRel
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DueMigrationLogicTest {

    private val dublin: ZoneId = ZoneId.of("Europe/Dublin")   // UTC+1 in summer
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")   // UTC+5:30, no DST

    private fun utcMidnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun localMidnight(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `utc-midnight values map to the same calendar date's local midnight`() {
        val date = LocalDate.of(2026, 8, 12)
        assertEquals(
            localMidnight(date, dublin),
            DueMigrationLogic.normalizeDueDate(utcMidnight(date), dublin),
        )
        assertEquals(
            localMidnight(date, kolkata),
            DueMigrationLogic.normalizeDueDate(utcMidnight(date), kolkata),
        )
    }

    @Test
    fun `non-midnight values are interpreted in the local zone`() {
        // Seeder wrote wall-clock `now`: 2026-08-12 22:30 IST
        val instant = LocalDateTime.of(2026, 8, 12, 22, 30)
            .atZone(kolkata).toInstant().toEpochMilli()
        assertEquals(
            localMidnight(LocalDate.of(2026, 8, 12), kolkata),
            DueMigrationLogic.normalizeDueDate(instant, kolkata),
        )
    }

    @Test
    fun `normalization is DST-correct on transition dates`() {
        // Europe/Dublin springs forward 2026-03-29: local midnight exists but the day is 23h.
        val date = LocalDate.of(2026, 3, 29)
        val normalized = DueMigrationLogic.normalizeDueDate(utcMidnight(date), dublin)
        assertEquals(localMidnight(date, dublin), normalized)
        assertEquals(date, DueMigrationLogic.localDateOf(normalized, dublin))
    }

    @Test
    fun `old due becomes deadline only on a different day`() {
        val due = localMidnight(LocalDate.of(2026, 8, 15), kolkata)
        val reminderSameDay = LocalDateTime.of(2026, 8, 15, 9, 0)
            .atZone(kolkata).toInstant().toEpochMilli()
        val reminderEarlierDay = LocalDateTime.of(2026, 8, 13, 9, 0)
            .atZone(kolkata).toInstant().toEpochMilli()
        assertFalse(DueMigrationLogic.oldDueBecomesDeadline(due, reminderSameDay, kolkata))
        assertTrue(DueMigrationLogic.oldDueBecomesDeadline(due, reminderEarlierDay, kolkata))
    }

    @Test
    fun `stripDef removes matching props and collapses empty groups`() {
        val tree = Filter.All(
            listOf(
                Filter.Type("task"),
                Filter.AnyOf(
                    listOf(
                        Filter.Prop(defId = "builtin-reminder", op = Op.IS_SET),
                        Filter.Prop(defId = "due", op = Op.LTE, dateRel = DateRel.TODAY_END),
                    )
                ),
                Filter.Not(Filter.Prop(defId = "builtin-reminder", op = Op.NOT_SET)),
            )
        )
        val stripped = DueMigrationLogic.stripDef(tree, "builtin-reminder") as Filter.All
        // AnyOf keeps the due prop; the Not collapses away entirely.
        assertEquals(2, stripped.filters.size)
        assertTrue(stripped.filters[0] is Filter.Type)
        val any = stripped.filters[1] as Filter.AnyOf
        assertEquals(1, any.filters.size)
        assertEquals("due", (any.filters[0] as Filter.Prop).defId)
    }

    @Test
    fun `stripDef returns the same instance when nothing matches`() {
        val tree = Filter.All(
            listOf(
                Filter.Type("task"),
                Filter.Prop(defId = "due", op = Op.IS_SET),
            )
        )
        assertSame(tree, DueMigrationLogic.stripDef(tree, "builtin-reminder"))
    }

    @Test
    fun `stripDef strips a whole tree to null`() {
        assertNull(
            DueMigrationLogic.stripDef(
                Filter.Prop(defId = "builtin-reminder", op = Op.IS_SET),
                "builtin-reminder",
            )
        )
    }
}
