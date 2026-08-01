package ie.napkin.supertasks.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
