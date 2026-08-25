package ie.napkin.supertasks

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.MIGRATION_1_2
import ie.napkin.supertasks.data.db.MIGRATION_2_3
import ie.napkin.supertasks.data.db.MIGRATION_3_4
import ie.napkin.supertasks.data.db.MIGRATION_4_5
import ie.napkin.supertasks.data.db.MIGRATION_5_6
import ie.napkin.supertasks.data.db.MIGRATION_6_7
import ie.napkin.supertasks.data.db.MIGRATION_7_8
import ie.napkin.supertasks.data.db.MIGRATION_8_9
import ie.napkin.supertasks.data.db.MIGRATION_9_10
import ie.napkin.supertasks.data.label.LabelPalette
import ie.napkin.supertasks.data.db.SystemKey
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
        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
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
        const val LATEST = 10
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
}
