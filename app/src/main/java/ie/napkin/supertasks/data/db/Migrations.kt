package ie.napkin.supertasks.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ie.napkin.supertasks.data.filter.ApplyOnCreate
import ie.napkin.supertasks.data.filter.DateRel
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.filter.Op
import ie.napkin.supertasks.data.filter.SortBy
import ie.napkin.supertasks.data.filter.SortSpec
import ie.napkin.supertasks.data.filter.deriveApplyOnCreate
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * Adds the label/tag system (label, node_label) and flags the two fixed built-in property
 * defs (Priority/Due) so they can be told apart from any other historical custom-property
 * data. Purely additive — safe for real on-device task data, no destructive fallback needed.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE property_def ADD COLUMN is_built_in INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE property_def SET is_built_in = 1 WHERE name IN ('Priority', 'Due')")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS label (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                color INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_label_name ON label (name)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS node_label (
                node_id TEXT NOT NULL,
                label_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (node_id, label_id),
                FOREIGN KEY (node_id) REFERENCES node(id) ON DELETE CASCADE,
                FOREIGN KEY (label_id) REFERENCES label(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_node_label_label ON node_label (label_id)")
    }
}

/**
 * Adds node.system_key (stable identity for app-created nodes — the Today smart list gets
 * 'today') and the built-in Reminder property def. Purely additive; the Seeder only runs on
 * empty databases, so existing installs get both rows here. Literals must match
 * [SystemKey.TODAY] / Reminders.DEF_ID / [PropertyKind.DATETIME].
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE node ADD COLUMN system_key TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_node_system_key ON node (system_key)")
        // Claim exactly one existing "Today" smart list (oldest wins) for already-seeded installs.
        db.execSQL(
            """
            UPDATE node SET system_key = 'today' WHERE id = (
                SELECT id FROM node
                 WHERE type = 'smart_list' AND title = 'Today' AND deleted_at IS NULL
                 ORDER BY created_at LIMIT 1
            )
            """.trimIndent()
        )
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT INTO property_def (id, name, kind, config, is_built_in, created_at, updated_at)
            SELECT 'builtin-reminder', 'Reminder', 'datetime', NULL, 1, $now, $now
            WHERE NOT EXISTS (SELECT 1 FROM property_def WHERE id = 'builtin-reminder')
            """.trimIndent()
        )
    }
}

/**
 * Todoist-style merge, data-only (schema 4 is identical to 3):
 *  1. new built-in Deadline def;
 *  2. Due values normalized from the pre-v4 UTC-midnight bug to local-midnight instants and
 *     marked all-day (encoding on [BuiltIns]);
 *  3. the separate Reminder property is folded in — its instant becomes a timed Due with an
 *     on-time reminder, and a different-day all-day Due it displaces becomes the Deadline;
 *  4. the Reminder def and rows are deleted, dead references stripped from smart lists;
 *  5. the Today smart list gains the deadline disjunct + apply-on-create (via the real
 *     serializers — migrations are app code).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()

        // (1) Deadline def, idempotent.
        db.execSQL(
            """
            INSERT INTO property_def (id, name, kind, config, is_built_in, created_at, updated_at)
            SELECT '${BuiltIns.DEADLINE_DEF_ID}', '${BuiltIns.DEADLINE_NAME}', 'date', NULL, 1, $now, $now
            WHERE NOT EXISTS (SELECT 1 FROM property_def WHERE id = '${BuiltIns.DEADLINE_DEF_ID}')
            """.trimIndent()
        )

        // (2) Resolve the Due def (random per-install UUID, found by name — the convention
        // since MIGRATION_1_2). Create it if a broken install lacks one but has reminders.
        var dueId: String? = db.query(
            "SELECT id FROM property_def WHERE name = 'Due' AND is_built_in = 1 AND deleted_at IS NULL LIMIT 1"
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        if (dueId == null) {
            val hasReminders = db.query(
                "SELECT 1 FROM property_value WHERE def_id = 'builtin-reminder' LIMIT 1"
            ).use { it.moveToFirst() }
            if (hasReminders) {
                dueId = UUID.randomUUID().toString()
                db.execSQL(
                    "INSERT INTO property_def (id, name, kind, config, is_built_in, created_at, updated_at) " +
                        "VALUES (?, 'Due', 'date', NULL, 1, $now, $now)",
                    arrayOf(dueId)
                )
            }
        }

        // (3) Normalize existing Due values (UTC-midnight bug → local midnight) + mark all-day.
        if (dueId != null) {
            val dueRows = ArrayList<Pair<String, Long>>()
            db.query(
                "SELECT node_id, v_date FROM property_value WHERE def_id = ? AND v_date IS NOT NULL",
                arrayOf(dueId)
            ).use { c -> while (c.moveToNext()) dueRows.add(c.getString(0) to c.getLong(1)) }
            dueRows.forEach { (nodeId, v) ->
                // v_number = NULL guards against any stray pre-v4 value being reinterpreted
                // as a reminder offset (which would silently arm alarms).
                db.execSQL(
                    "UPDATE property_value SET v_date = ?, v_bool = 0, v_number = NULL, updated_at = ? " +
                        "WHERE node_id = ? AND def_id = ?",
                    arrayOf(DueMigrationLogic.normalizeDueDate(v), now, nodeId, dueId)
                )
            }

            // (4) Fold reminder rows in (Due rows above are already normalized).
            val reminderRows = ArrayList<Pair<String, Long>>()
            db.query(
                "SELECT node_id, v_date FROM property_value WHERE def_id = 'builtin-reminder' AND v_date IS NOT NULL"
            ).use { c -> while (c.moveToNext()) reminderRows.add(c.getString(0) to c.getLong(1)) }
            reminderRows.forEach { (nodeId, reminderAt) ->
                val oldDue: Long? = db.query(
                    "SELECT v_date FROM property_value WHERE node_id = ? AND def_id = ? AND v_date IS NOT NULL",
                    arrayOf(nodeId, dueId)
                ).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (oldDue != null && DueMigrationLogic.oldDueBecomesDeadline(oldDue, reminderAt)) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO property_value (node_id, def_id, v_text, v_number, v_date, v_bool, updated_at) " +
                            "VALUES (?, '${BuiltIns.DEADLINE_DEF_ID}', NULL, NULL, ?, NULL, ?)",
                        arrayOf(nodeId, oldDue, now)
                    )
                }
                db.execSQL(
                    "INSERT OR REPLACE INTO property_value (node_id, def_id, v_text, v_number, v_date, v_bool, updated_at) " +
                        "VALUES (?, ?, NULL, 0.0, ?, 1, ?)",
                    arrayOf(nodeId, dueId, reminderAt, now)
                )
            }
        }

        // (5) Remove the Reminder property entirely (values first — FK on def_id).
        db.execSQL("DELETE FROM property_value WHERE def_id = 'builtin-reminder'")
        db.execSQL("DELETE FROM property_def WHERE id = 'builtin-reminder'")

        // (6) Rewrite the Today smart list to the OR-with-deadline shape.
        val todayNodeId: String? = db.query(
            "SELECT s.node_id FROM smart_list_def s JOIN node n ON n.id = s.node_id WHERE n.system_key = 'today'"
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        if (todayNodeId != null && dueId != null) {
            val filter = Filter.All(
                listOf(
                    Filter.Type(NodeType.TASK),
                    Filter.Done(false),
                    Filter.AnyOf(
                        listOf(
                            Filter.Prop(defId = dueId, op = Op.LTE, dateRel = DateRel.TODAY_END),
                            Filter.Prop(defId = BuiltIns.DEADLINE_DEF_ID, op = Op.LTE, dateRel = DateRel.TODAY_END),
                        )
                    ),
                )
            )
            val sort = listOf(SortSpec(by = SortBy.PROP_DATE, defId = dueId))
            val apply = deriveApplyOnCreate(filter)
            db.execSQL(
                "UPDATE smart_list_def SET filter_json = ?, sort_json = ?, apply_on_create_json = ? WHERE node_id = ?",
                arrayOf(
                    FilterJson.encodeToString(Filter.serializer(), filter),
                    FilterJson.encodeToString(ListSerializer(SortSpec.serializer()), sort),
                    apply.takeIf { it.isNotEmpty() }
                        ?.let { FilterJson.encodeToString(ListSerializer(ApplyOnCreate.serializer()), it) },
                    todayNodeId,
                )
            )
        }

        // Strip dead builtin-reminder clauses from every smart list. The rewritten Today
        // filter contains none, so stripping it is an identity no-op — no exclusion needed
        // (and a Today list the rewrite skipped still gets cleaned).
        val others = ArrayList<Pair<String, String>>()
        db.query(
            "SELECT node_id, filter_json FROM smart_list_def"
        ).use { c -> while (c.moveToNext()) others.add(c.getString(0) to c.getString(1)) }
        others.forEach { (nodeId, json) ->
            val decoded = runCatching { FilterJson.decodeFromString(Filter.serializer(), json) }.getOrNull()
                ?: return@forEach
            val stripped = DueMigrationLogic.stripDef(decoded, "builtin-reminder")
            if (stripped !== decoded) {
                val newJson = FilterJson.encodeToString(
                    Filter.serializer(),
                    stripped ?: Filter.All(emptyList()),
                )
                db.execSQL(
                    "UPDATE smart_list_def SET filter_json = ? WHERE node_id = ?",
                    arrayOf(newJson, nodeId)
                )
            }
        }
    }
}

/**
 * Adds node.indent — a block's visual indentation on its page.
 *
 * Separate from parent_id on purpose: indenting a line must not re-home it. Existing rows are flush
 * left, which is what they were.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE node ADD COLUMN indent INTEGER NOT NULL DEFAULT 0")
    }
}
