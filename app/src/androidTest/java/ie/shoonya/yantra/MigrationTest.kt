package ie.shoonya.yantra

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.MIGRATION_1_2
import ie.shoonya.yantra.data.db.MIGRATION_2_3
import ie.shoonya.yantra.data.db.MIGRATION_3_4
import ie.shoonya.yantra.data.db.MIGRATION_4_5
import ie.shoonya.yantra.data.db.MIGRATION_5_6
import ie.shoonya.yantra.data.db.MIGRATION_6_7
import ie.shoonya.yantra.data.db.MIGRATION_7_8
import ie.shoonya.yantra.data.db.MIGRATION_8_9
import ie.shoonya.yantra.data.db.MIGRATION_10_11
import ie.shoonya.yantra.data.db.MIGRATION_11_12
import ie.shoonya.yantra.data.db.MIGRATION_12_13
import ie.shoonya.yantra.data.db.MIGRATION_13_14
import ie.shoonya.yantra.data.db.MIGRATION_14_15
import ie.shoonya.yantra.data.db.MIGRATION_15_16
import ie.shoonya.yantra.data.db.MIGRATION_16_17
import ie.shoonya.yantra.data.db.MIGRATION_17_18
import ie.shoonya.yantra.data.db.MIGRATION_18_19
import ie.shoonya.yantra.data.db.MIGRATION_9_10
import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.data.db.SystemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays every migration against the exported schemas.
 *
 * The app is on schema 7 with six migrations and, until this file, none of them had ever been
 * run against anything but a developer's own phone. A migration is the one kind of bug that
 * destroys data the user cannot get back, and the schemas needed to check them were already
 * committed — only the harness was missing.
 *
 * [MigrationTestHelper.runMigrationsAndValidate] asserts the resulting schema matches the
 * exported JSON for the target version, so structural drift fails without anyone writing an
 * assertion; the per-migration tests below cover the parts that are about *data*.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val ALL = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
        MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
    )

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private companion object {
        const val DB = "migration-test.db"
        const val LATEST = 14
    }

    private fun SupportSQLiteDatabase.scalar(sql: String): String? =
        query(sql).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun SupportSQLiteDatabase.count(sql: String): Int =
        query(sql).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    /**
     * Minimal v1 node row — valid up to and including v4.
     *
     * Not usable from v5 on: MIGRATION_4_5 adds `indent` as `NOT NULL DEFAULT 0`, but the entity
     * declares only `val indent: Int = 0` with no `@ColumnInfo(defaultValue = ...)`, so the
     * *exported* schema has no default and a freshly-created v5 table rejects an insert that
     * omits the column. Migrated installs and fresh installs therefore carry slightly different
     * DDL. Harmless for the app — Room always supplies the value from Kotlin — but raw SQL has to
     * name the column, which is what the v5+ tests below do.
     */
    private fun SupportSQLiteDatabase.insertNode(
        id: String, type: String, title: String, parent: String? = null,
    ) = execSQL(
        "INSERT INTO node (id, parent_id, type, title, rank, done, collapsed, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, 'i', 0, 0, 1000, 1000)",
        arrayOf(id, parent, type, title),
    )

    // ---- the whole chain ----

    @Test
    fun migrateAll_fromV1_matchesExportedSchema() {
        helper.createDatabase(DB, 1).use { db ->
            db.insertNode("list-1", "list", "Inbox")
            db.insertNode("task-1", "task", "A task", parent = "list-1")
        }
        // Validates the final schema against schemas/7.json.
        helper.runMigrationsAndValidate(DB, LATEST, true, *ALL).use { db ->
            assertEquals(2, db.count("SELECT COUNT(*) FROM node"))
        }
    }

    @Test
    fun everyStartingVersion_reachesLatest() {
        // A user who skipped releases starts at any version, not just 1.
        for (from in 1 until LATEST) {
            val name = "$DB-$from"
            helper.createDatabase(name, from).close()
            helper.runMigrationsAndValidate(name, LATEST, true, *ALL).close()
        }
    }

    // ---- per-migration data behaviour ----

    /**
     * The link to somebody else's meeting moves onto the node — CALENDAR_PLAN.md §22.
     *
     * On `event` it forced a task about a meeting to be two rows; on the node it is a fact about a
     * line of any kind, and a task is the only node there is.
     */
    /**
     * A list can wear a colour of its own — the colour law, as remade.
     *
     * The accent means your own effort and nothing else, so a list cannot borrow it; it carries a
     * colour you chose instead. A **name**, not a value, which is the part worth guarding: a hex is
     * picked against one theme and the light and dark twins of a swatch are different numbers, so a
     * column holding `4294945637` would be a colour that is right in one theme and wrong in the
     * other, for ever.
     */
    @Test
    fun migration17to18_letsAListWearAColour() {
        helper.createDatabase(DB, 17).use { db ->
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, " +
                    "in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('l1', '', NULL, 'list', 'Shopping', 'i', 0, 0, 0, 0, 1, 1)"
            )
        }
        helper.runMigrationsAndValidate(DB, 18, true, *ALL).use { db ->
            assertEquals(
                "a list that existed wears nothing",
                1, db.count("SELECT COUNT(*) FROM node WHERE id = 'l1' AND color IS NULL"),
            )
            db.execSQL("UPDATE node SET color = 'teal' WHERE id = 'l1'")
            assertEquals(
                "and holds a palette name rather than a number",
                1, db.count("SELECT COUNT(*) FROM node WHERE color = 'teal'"),
            )
        }
    }

    @Test
    fun migration16to17_letsAnyLineBeAboutSomebodyElsesMeeting() {
        helper.createDatabase(DB, 16).use { db ->
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, " +
                    "in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('t1', '', NULL, 'task', 'Design review', 'i', 0, 0, 0, 0, 1, 1)"
            )
        }
        helper.runMigrationsAndValidate(DB, 17, true, *ALL).use { db ->
            assertEquals(
                "nothing that existed is about anything",
                1, db.count("SELECT COUNT(*) FROM node WHERE id = 't1' AND ext_uid IS NULL"),
            )
            // A uid is usually an address, which is the shape that has to survive.
            db.execSQL("UPDATE node SET ext_uid = 'abc123@google.com' WHERE id = 't1'")
            assertEquals(1, db.count("SELECT COUNT(*) FROM node WHERE ext_uid = 'abc123@google.com'"))
        }
    }

    /**
     * A line can be a note about somebody else's meeting — CALENDAR_PLAN.md §19.
     *
     * What the column holds is the identity the **sync source** gave the event, never this device's
     * row id, which is the only reason it is allowed in a file at all: a row number means something
     * different on your other phone and nothing after a reinstall.
     */
    @Test
    fun migration15to16_letsALineBeANoteAboutSomebodyElsesMeeting() {
        helper.createDatabase(DB, 15).use { db ->
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, " +
                    "in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('n1', '', NULL, 'event', 'Design review', 'i', 0, 0, 0, 0, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO event (node_id, workspace_id, start_local, end_local, all_day, " +
                    "start_utc, end_utc, cancelled) VALUES " +
                    "('n1', '', '2026-09-16T14:00', '2026-09-16T15:00', 0, 1000, 2000, 0)"
            )
        }
        helper.runMigrationsAndValidate(DB, 16, true, *ALL).use { db ->
            assertEquals(
                "nothing that existed is a note about anything",
                1, db.count("SELECT COUNT(*) FROM event WHERE node_id = 'n1' AND ext_uid IS NULL"),
            )
            // A uid is very often an address, which is exactly the shape that has to survive.
            db.execSQL("UPDATE event SET ext_uid = 'abc123@google.com' WHERE node_id = 'n1'")
            assertEquals(1, db.count("SELECT COUNT(*) FROM event WHERE ext_uid = 'abc123@google.com'"))
        }
    }

    /**
     * Null is not "no colour" here — it means *the workspace's*, which is a live answer that changes
     * when the workspace does. So nothing is backfilled: writing a value into every existing row
     * would sever that inheritance for everything that already exists, permanently and silently.
     */
    @Test
    fun migration14to15_letsAnEventWearAColourAndLeavesTheRestInheriting() {
        helper.createDatabase(DB, 14).use { db ->
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, " +
                    "in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('e1', '', NULL, 'event', 'Design review', 'i', 0, 0, 0, 0, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO event (node_id, workspace_id, start_local, end_local, all_day, " +
                    "start_utc, end_utc, cancelled) VALUES " +
                    "('e1', '', '2026-09-13T14:00', '2026-09-13T15:00', 0, 1000, 2000, 0)"
            )
        }
        helper.runMigrationsAndValidate(DB, 15, true, *ALL).use { db ->
            assertEquals(
                "an event that existed before colours must still inherit",
                1, db.count("SELECT COUNT(*) FROM event WHERE node_id = 'e1' AND color IS NULL"),
            )
            db.execSQL("UPDATE event SET color = 'Teal' WHERE node_id = 'e1'")
            assertEquals(1, db.count("SELECT COUNT(*) FROM event WHERE color = 'Teal'"))
        }
    }

    @Test
    fun migration13to14_letsAnEventSayWhichTaskItIsFor() {
        helper.createDatabase(DB, 13).close()
        helper.runMigrationsAndValidate(DB, 14, true, *ALL).use { db ->
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, " +
                    "in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('t1', '', NULL, 'task', 'Write the deck', 'i', 0, 0, 0, 0, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, " +
                    "in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('s1', '', NULL, 'event', '', 'j', 0, 0, 0, 0, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO event (node_id, workspace_id, start_local, end_local, all_day, " +
                    "start_utc, end_utc, cancelled, for_node_id) VALUES " +
                    "('s1', '', '2026-09-12T14:00', '2026-09-12T16:00', 0, 1000, 2000, 0, 't1')"
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM event WHERE for_node_id = 't1'"))
            // No foreign key on purpose: a sitting outliving its task is a stale reference, not a
            // corrupt one, and it should leave a plain block behind rather than take the row down.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM node WHERE id = 't1'")
            assertEquals(1, db.count("SELECT COUNT(*) FROM event WHERE node_id = 's1'"))
        }
    }


    @Test
    fun migration11to12_addsAnEmptyEventTableWithoutDisturbingNodes() {
        // Nothing to backfill: an event is a line in a page, and the index is rebuilt from files on
        // the next open. What matters is that the table arrives, that it is keyed to node, and that
        // adding it does not cost anything already indexed.
        helper.createDatabase(DB, 11).use { db ->
            // Columns named in full: [insertNode] writes the v1 shape, and by 11 the table has
            // grown workspace_id, in_progress and indent, all NOT NULL.
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, " +
                    "in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('list-1', '', NULL, 'list', 'Inbox', 'i', 0, 0, 0, 0, 1000, 1000)"
            )
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, " +
                    "in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('task-1', '', 'list-1', 'task', 'A task', 'j', 0, 0, 0, 0, 1000, 1000)"
            )
        }
        helper.runMigrationsAndValidate(DB, 12, true, *ALL).use { db ->
            assertEquals(2, db.count("SELECT COUNT(*) FROM node"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM event"))
            // The cascade is what keeps a deleted page from leaving its events behind.
            db.execSQL(
                "INSERT INTO event (node_id, workspace_id, start_local, end_local, all_day, " +
                    "start_utc, end_utc, cancelled) VALUES " +
                    "('task-1', '', '2026-09-11T14:00', '2026-09-11T15:00', 0, 1000, 2000, 0)"
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM event"))
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM node WHERE id = 'task-1'")
            assertEquals(0, db.count("SELECT COUNT(*) FROM event"))
        }
    }


    @Test
    fun migration2to3_claimsTheOldestTodaySmartList() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, collapsed, created_at, updated_at) " +
                    "VALUES ('today-old', NULL, 'smart_list', 'Today', 'i', 0, 0, 100, 100)"
            )
            // A second one, made later — oldest must win, and the unique index forbids both.
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, collapsed, created_at, updated_at) " +
                    "VALUES ('today-new', NULL, 'smart_list', 'Today', 'j', 0, 0, 200, 200)"
            )
        }
        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_1_2, MIGRATION_2_3).use { db ->
            assertEquals("today-old", db.scalar("SELECT id FROM node WHERE system_key = '${SystemKey.TODAY}'"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM node WHERE system_key IS NOT NULL"))
        }
    }

    @Test
    fun migration2to3_isSafeWithNoTodayList() {
        helper.createDatabase(DB, 2).use { db -> db.insertNode("l", "list", "Groceries") }
        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_1_2, MIGRATION_2_3).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM node WHERE system_key IS NOT NULL"))
        }
    }

    @Test
    fun migration3to4_dropsTheReminderDefAndAddsDeadline() {
        helper.createDatabase(DB, 3).use { db -> db.insertNode("t", "task", "A task") }
        helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM property_def WHERE id = 'builtin-reminder'"))
            assertEquals(
                "Deadline",
                db.scalar("SELECT name FROM property_def WHERE id = 'builtin-deadline'"),
            )
        }
    }

    @Test
    fun migration3to4_foldsAReminderIntoATimedDue() {
        val dueDay = 1_767_225_600_000L        // 2026-01-01T00:00:00Z
        val reminderAt = dueDay + 9 * 3_600_000L
        helper.createDatabase(DB, 3).use { db ->
            db.insertNode("t", "task", "A task")
            db.execSQL(
                "INSERT INTO property_def (id, name, kind, config, is_built_in, created_at, updated_at) " +
                    "VALUES ('due-def', 'Due', 'date', NULL, 1, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO property_value (node_id, def_id, v_date, updated_at) VALUES ('t', 'due-def', $dueDay, 1)"
            )
            db.execSQL(
                "INSERT INTO property_value (node_id, def_id, v_date, updated_at) " +
                    "VALUES ('t', 'builtin-reminder', $reminderAt, 1)"
            )
        }
        helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4).use { db ->
            // The reminder instant became the Due, marked timed (v_bool = 1).
            assertEquals("1", db.scalar("SELECT v_bool FROM property_value WHERE node_id='t' AND def_id='due-def'"))
            assertEquals(
                reminderAt.toString(),
                db.scalar("SELECT v_date FROM property_value WHERE node_id='t' AND def_id='due-def'"),
            )
            // Nothing is left pointing at the deleted def.
            assertEquals(0, db.count("SELECT COUNT(*) FROM property_value WHERE def_id = 'builtin-reminder'"))
        }
    }

    @Test
    fun migration4to5_defaultsExistingBlocksToFlushLeft() {
        helper.createDatabase(DB, 4).use { db -> db.insertNode("b", "paragraph", "Some prose") }
        helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5).use { db ->
            assertEquals("0", db.scalar("SELECT indent FROM node WHERE id = 'b'"))
        }
    }

    @Test
    fun migration5to6_defaultsExistingTasksToNotStarted() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('t', NULL, 'task', 'A task', 'i', 0, 0, 0, 1000, 1000)"
            )
        }
        helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6).use { db ->
            assertEquals("0", db.scalar("SELECT in_progress FROM node WHERE id = 't'"))
        }
    }

    @Test
    fun migration6to7_claimsTheOldestInbox() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('inbox-old', NULL, 'list', 'Inbox', 'i', 0, 0, 0, 0, 100, 100)"
            )
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('inbox-dupe', NULL, 'list', 'Inbox', 'j', 0, 0, 0, 0, 200, 200)"
            )
        }
        helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7).use { db ->
            assertEquals("inbox-old", db.scalar("SELECT id FROM node WHERE system_key = '${SystemKey.INBOX}'"))
        }
    }

    @Test
    fun migration6to7_findsAGroupedInbox() {
        // The bug this key exists to fix: an Inbox moved into a group was invisible to the old
        // top-level-only lookup, so it must not be invisible to the backfill either.
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('g', NULL, 'group', 'Work', 'i', 0, 0, 0, 0, 50, 50)"
            )
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('inbox', 'g', 'list', 'Inbox', 'i', 0, 0, 0, 0, 100, 100)"
            )
        }
        helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7).use { db ->
            assertEquals("inbox", db.scalar("SELECT id FROM node WHERE system_key = '${SystemKey.INBOX}'"))
        }
    }

    @Test
    fun migration6to7_leavesARenamedInboxAlone() {
        // Nothing to claim: the repository adopts one at first capture instead.
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('capture', NULL, 'list', 'Capture', 'i', 0, 0, 0, 0, 100, 100)"
            )
        }
        helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7).use { db ->
            assertNull(db.scalar("SELECT id FROM node WHERE system_key = '${SystemKey.INBOX}'"))
        }
    }

    @Test
    fun migration6to7_neverStealsTheTodayKey() {
        // Both keys share one unique index; claiming Inbox must not disturb an existing Today.
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, system_key, created_at, updated_at) " +
                    "VALUES ('today', NULL, 'smart_list', 'Today', 'i', 0, 0, 0, 0, 'today', 100, 100)"
            )
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('inbox', NULL, 'list', 'Inbox', 'j', 0, 0, 0, 0, 100, 100)"
            )
        }
        helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7).use { db ->
            assertEquals("today", db.scalar("SELECT id FROM node WHERE system_key = 'today'"))
            assertEquals("inbox", db.scalar("SELECT id FROM node WHERE system_key = 'inbox'"))
        }
    }

    @Test
    fun migration7to8_coloursExistingLabelsFromThePalette() {
        // Every label ever made is NULL, because nothing ever set the column.
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL("INSERT INTO label (id, name, color, created_at, updated_at) VALUES ('l1', 'groceries', NULL, 1, 1)")
            db.execSQL("INSERT INTO label (id, name, color, created_at, updated_at) VALUES ('l2', 'work', NULL, 1, 1)")
        }
        helper.runMigrationsAndValidate(DB, 8, true, MIGRATION_7_8).use { db ->
            assertEquals(
                LabelPalette.defaultFor("groceries").toString(),
                db.scalar("SELECT color FROM label WHERE id = 'l1'"),
            )
            // A backfilled label must land where a freshly-typed one of the same name would.
            assertEquals(
                LabelPalette.defaultFor("work").toString(),
                db.scalar("SELECT color FROM label WHERE id = 'l2'"),
            )
            assertEquals(0, db.count("SELECT COUNT(*) FROM label WHERE color IS NULL"))
        }
    }

    @Test
    fun migration7to8_leavesAlreadyColouredLabelsAlone() {
        val chosen = LabelPalette.swatches.last().light
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL("INSERT INTO label (id, name, color, created_at, updated_at) VALUES ('l1', 'work', $chosen, 1, 1)")
        }
        helper.runMigrationsAndValidate(DB, 8, true, MIGRATION_7_8).use { db ->
            assertEquals(chosen.toString(), db.scalar("SELECT color FROM label WHERE id = 'l1'"))
        }
    }

    @Test
    fun migration7to8_isSafeWithNoLabels() {
        helper.createDatabase(DB, 7).close()
        helper.runMigrationsAndValidate(DB, 8, true, MIGRATION_7_8).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM label"))
        }
    }

    @Test
    fun migration8to9_stampsExistingRowsWithTheLocalWorkspace() {
        helper.createDatabase(DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('n1', NULL, 'list', 'Inbox', 'i', 0, 0, 0, 0, 1, 1)"
            )
            db.execSQL("INSERT INTO label (id, name, created_at, updated_at) VALUES ('l1', 'sync', 1, 1)")
        }
        helper.runMigrationsAndValidate(DB, 9, true, MIGRATION_8_9).use { db ->
            // Rows from before workspaces existed belong to the local one, which is the empty name.
            assertEquals("", db.scalar("SELECT workspace_id FROM node WHERE id = 'n1'"))
            assertEquals("", db.scalar("SELECT workspace_id FROM label WHERE id = 'l1'"))
        }
    }

    @Test
    fun migration8to9_letsTwoWorkspacesEachHaveAnInbox() {
        // The unique index on system_key was global, so the second workspace's Inbox was rejected
        // on insert — a failure that only appears when someone adds their second repo.
        helper.createDatabase(DB, 8).close()
        helper.runMigrationsAndValidate(DB, 9, true, MIGRATION_8_9).use { db ->
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, in_progress, collapsed, indent, system_key, created_at, updated_at) " +
                    "VALUES ('a', 'work', NULL, 'list', 'Inbox', 'i', 0, 0, 0, 0, 'inbox', 1, 1)"
            )
            db.execSQL(
                "INSERT INTO node (id, workspace_id, parent_id, type, title, rank, done, in_progress, collapsed, indent, system_key, created_at, updated_at) " +
                    "VALUES ('b', 'personal', NULL, 'list', 'Inbox', 'i', 0, 0, 0, 0, 'inbox', 1, 1)"
            )
            assertEquals(2, db.count("SELECT COUNT(*) FROM node WHERE system_key = 'inbox'"))
        }
    }

    @Test
    fun migration8to9_letsTwoWorkspacesUseTheSameTagName() {
        helper.createDatabase(DB, 8).close()
        helper.runMigrationsAndValidate(DB, 9, true, MIGRATION_8_9).use { db ->
            db.execSQL("INSERT INTO label (id, workspace_id, name, created_at, updated_at) VALUES ('a', 'work', 'sync', 1, 1)")
            db.execSQL("INSERT INTO label (id, workspace_id, name, created_at, updated_at) VALUES ('b', 'personal', 'sync', 1, 1)")
            assertEquals(2, db.count("SELECT COUNT(*) FROM label WHERE name = 'sync'"))
        }
    }

    @Test
    fun softDeleteReleasesTheSystemKey() {
        // The unique index would otherwise let a tombstone hold an identity forever.
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, created_at, updated_at) " +
                    "VALUES ('inbox', NULL, 'list', 'Inbox', 'i', 0, 0, 0, 0, 100, 100)"
            )
        }
        helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7).use { db ->
            db.execSQL("UPDATE node SET deleted_at = 1, system_key = NULL WHERE id = 'inbox'")
            db.execSQL(
                "INSERT INTO node (id, parent_id, type, title, rank, done, in_progress, collapsed, indent, system_key, created_at, updated_at) " +
                    "VALUES ('inbox-2', NULL, 'list', 'Inbox', 'j', 0, 0, 0, 0, 'inbox', 300, 300)"
            )
            assertTrue(db.count("SELECT COUNT(*) FROM node WHERE system_key = 'inbox'") == 1)
        }
    }

    @Test
    fun v10RecordsHowASessionEndedAndForgetsTheOldBoolean() {
        helper.createDatabase(DB, 9).use { db ->
            db.execSQL("INSERT INTO node (id, type, rank, done, in_progress, indent, collapsed, created_at, updated_at, workspace_id) VALUES ('n1','task','a',0,0,0,0,1,1,'')")
            db.execSQL(
                "INSERT INTO pomodoro_session (id, node_id, started_at, ended_at, planned_secs, actual_secs, completed, created_at, updated_at, workspace_id) " +
                    "VALUES ('s1','n1',1000,2000,1500,1500,1,1000,2000,'')"
            )
        }

        helper.runMigrationsAndValidate(DB, 10, true, MIGRATION_9_10).use { db ->
            // The table is derived from pomodoro/*.log inside the workspace, so recreating it loses
            // nothing that the next index rebuild does not put straight back. What matters is that
            // the new shape validates against the exported schema.
            db.query("SELECT outcome FROM pomodoro_session").use { c ->
                assertEquals(0, c.count)
            }
        }
    }

    @Test
    fun v11RenamesTheTableAndKeepsTheSessionsInIt() {
        helper.createDatabase(DB, 10).use { db ->
            db.execSQL("INSERT INTO node (id, type, rank, done, in_progress, indent, collapsed, created_at, updated_at, workspace_id) VALUES ('n1','task','a',0,0,0,0,1,1,'')")
            db.execSQL(
                "INSERT INTO pomodoro_session (id, node_id, started_at, ended_at, planned_secs, actual_secs, outcome, created_at, updated_at, workspace_id) " +
                    "VALUES ('s1','n1',1000,2000,1500,1500,'ran_out',1000,2000,'')"
            )
        }

        helper.runMigrationsAndValidate(DB, 11, true, MIGRATION_10_11).use { db ->
            // A rename, not a rebuild: unlike v10 there was nothing wrong with the shape, so the
            // rows come across. Losing them would be survivable — they rebuild from the logs — but
            // it would leave the history visibly empty on the first launch after updating.
            assertEquals("s1", db.scalar("SELECT id FROM focus_session"))
            assertEquals("ran_out", db.scalar("SELECT outcome FROM focus_session"))
            // Room compares index names as strictly as columns, and SQLite carries the old one
            // across a rename. This is the assertion that catches forgetting to recreate it.
            assertEquals(
                "idx_focus_node",
                db.scalar("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='focus_session' AND name NOT LIKE 'sqlite_%'"),
            )
        }
    }

    /**
     * v19 gives a list somewhere to keep its emoji, and keeps the rows it already had.
     *
     * An added nullable column is the safest migration there is, which is exactly why it is worth
     * one test: the risk is not the `ALTER TABLE`, it is forgetting to register the migration or
     * bumping the version without writing one, and both of those destroy an upgrading user's whole
     * database rather than one column. `runMigrationsAndValidate` catches the shape; the assertions
     * below catch the data.
     */
    @Test
    fun v19AddsTheListIconAndLosesNothing() {
        helper.createDatabase(DB, 18).use { db ->
            db.execSQL(
                "INSERT INTO node (id, type, rank, done, in_progress, indent, collapsed, " +
                    "created_at, updated_at, workspace_id, title, color) " +
                    "VALUES ('L1','list','a',0,0,0,0,1,1,'','Shopping','Teal')"
            )
        }

        helper.runMigrationsAndValidate(DB, 19, true, MIGRATION_18_19).use { db ->
            assertEquals("Shopping", db.scalar("SELECT title FROM node WHERE id = 'L1'"))
            // The colour is the neighbour this column was modelled on, and the one an ALTER that
            // rebuilt the table rather than extending it would quietly drop.
            assertEquals("Teal", db.scalar("SELECT color FROM node WHERE id = 'L1'"))
            // Null, not empty: a list that has never been given an emoji wears its drawn mark, and
            // the two are different states everywhere above here.
            assertNull(db.scalar("SELECT icon FROM node WHERE id = 'L1'"))
        }
    }
}
