package ie.napkin.supertasks.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object NodeType {
    const val LIST = "list"
    const val TASK = "task"
    const val PARAGRAPH = "paragraph"
    const val HEADING = "heading"
    const val BULLET = "bullet"
    const val NUMBERED = "numbered"
    const val INK = "ink"
    const val IMAGE = "image"
    const val SMART_LIST = "smart_list"
    const val GROUP = "group"   // a Home banner grouping lists & smart lists (organizational only)

    /**
     * The block types that are "a line of text with something in front of it" — a checkbox, a
     * bullet, a number, or nothing. They share one row composable and convert freely between each
     * other, so anything that asks "is this an editable line?" asks this.
     *
     * [type] is a plain string column, so adding members here needs no migration; older rows
     * simply never carry the new values.
     */
    val TEXTUAL: Set<String> = setOf(TASK, PARAGRAPH, HEADING, BULLET, NUMBERED)
}

/**
 * Stable identities for app-created nodes that features must find again (seeded ids are random
 * UUIDs). Unique-indexed; NULL for everything user-created.
 */
object SystemKey {
    const val TODAY = "today"
}

@Entity(
    tableName = "node",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
        )
    ],
    indices = [
        Index(value = ["parent_id", "rank"], name = "idx_node_parent"),
        // SQLite unique indexes allow multiple NULLs — only system-keyed nodes are constrained.
        Index(value = ["system_key"], unique = true, name = "idx_node_system_key"),
    ]
)
data class NodeEntity(
    @PrimaryKey val id: String,                              // client-generated UUID (sync-ready)
    @ColumnInfo(name = "parent_id") val parentId: String?,   // NULL = top-level list
    val type: String,
    val title: String?,
    val rank: String,                                        // fractional index for sibling order
    val done: Boolean = false,
    /**
     * Started but not finished — the middle state of the task glyph (open → in progress → done).
     *
     * Stored rather than derived from a running focus session on purpose: the design has you
     * long-press a task to say "I am on this", which is a claim about intent, not about whether a
     * timer happens to be ticking. A task stays in progress across app restarts and between
     * sessions, and a session on a task you never marked does not silently move it.
     *
     * Meaningless when [done] is true; completion supersedes it, and the repository clears it.
     */
    @ColumnInfo(name = "in_progress") val inProgress: Boolean = false,
    val collapsed: Boolean = false,
    /**
     * How far this block is indented on its page, purely visually. Deliberately *not* parentage:
     * indenting a block under a task must not move the block into that task, so how a line is laid
     * out and where it lives are two separate facts. Nesting is what a task's own page is for.
     */
    val indent: Int = 0,
    // canvas-later (ignored by linear render):
    @ColumnInfo(name = "canvas_x") val canvasX: Double? = null,
    @ColumnInfo(name = "canvas_y") val canvasY: Double? = null,
    @ColumnInfo(name = "canvas_w") val canvasW: Double? = null,
    @ColumnInfo(name = "canvas_h") val canvasH: Double? = null,
    @ColumnInfo(name = "system_key") val systemKey: String? = null,  // see [SystemKey]
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,    // LWW clock for sync
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

object PropertyKind {
    const val SELECT = "select"
    const val TEXT = "text"
    const val NUMBER = "number"
    const val DATE = "date"
    const val DATETIME = "datetime"   // exact instant (epoch millis in v_date), e.g. Reminder
    const val CHECKBOX = "checkbox"
}

@Entity(tableName = "property_def")
data class PropertyDefEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,                                        // select | text | number | date | checkbox
    val config: String?,                                     // JSON (e.g. select options + colors)
    // true only for the fixed Priority/Due fields seeded on first run — the UI never lets
    // users create more of these; everything else open-ended goes through LabelEntity instead.
    @ColumnInfo(name = "is_built_in", defaultValue = "0") val isBuiltIn: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "property_value",
    primaryKeys = ["node_id", "def_id"],
    foreignKeys = [
        ForeignKey(entity = NodeEntity::class, parentColumns = ["id"], childColumns = ["node_id"]),
        ForeignKey(entity = PropertyDefEntity::class, parentColumns = ["id"], childColumns = ["def_id"]),
    ],
    indices = [Index(value = ["def_id", "v_text", "v_number", "v_date"], name = "idx_pv_def")]
)
data class PropertyValueEntity(
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "def_id") val defId: String,
    @ColumnInfo(name = "v_text") val vText: String? = null,  // populate the column matching def.kind
    @ColumnInfo(name = "v_number") val vNumber: Double? = null,
    @ColumnInfo(name = "v_date") val vDate: Long? = null,    // epoch millis: comparable + indexable
    @ColumnInfo(name = "v_bool") val vBool: Boolean? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "pomodoro_session",
    foreignKeys = [
        ForeignKey(entity = NodeEntity::class, parentColumns = ["id"], childColumns = ["node_id"])
    ],
    indices = [Index(value = ["node_id", "started_at"], name = "idx_pomo_node")]
)
data class PomodoroSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "node_id") val nodeId: String,        // pomodoro is always attached to a node
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "planned_secs") val plannedSecs: Int,
    @ColumnInfo(name = "actual_secs") val actualSecs: Int? = null,
    val completed: Boolean = false,                          // finished vs abandoned
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "smart_list_def",
    foreignKeys = [
        ForeignKey(entity = NodeEntity::class, parentColumns = ["id"], childColumns = ["node_id"]),
    ]
)
data class SmartListDefEntity(
    @PrimaryKey @ColumnInfo(name = "node_id") val nodeId: String,   // the smart_list node itself
    @ColumnInfo(name = "scope_root_id") val scopeRootId: String?,   // NULL = whole workspace
    @ColumnInfo(name = "filter_json") val filterJson: String,       // read side: what qualifies
    @ColumnInfo(name = "sort_json") val sortJson: String?,
    @ColumnInfo(name = "home_parent_id") val homeParentId: String?, // write side: where added tasks live
    @ColumnInfo(name = "apply_on_create_json") val applyOnCreateJson: String?,
)

@Entity(
    tableName = "ink_stroke",
    foreignKeys = [
        ForeignKey(entity = NodeEntity::class, parentColumns = ["id"], childColumns = ["node_id"])
    ],
    indices = [Index(value = ["node_id"], name = "idx_stroke_node")]
)
data class InkStrokeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "node_id") val nodeId: String,        // the 'ink' block this stroke belongs to
    val data: ByteArray,                                     // serialized Ink StrokeInputBatch + brush envelope
    @ColumnInfo(name = "bbox_x") val bboxX: Double? = null,  // future canvas culling / erase hit-test
    @ColumnInfo(name = "bbox_y") val bboxY: Double? = null,
    @ColumnInfo(name = "bbox_w") val bboxW: Double? = null,
    @ColumnInfo(name = "bbox_h") val bboxH: Double? = null,
    val rank: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean = other is InkStrokeEntity && other.id == id && other.updatedAt == updatedAt && other.deletedAt == deletedAt
    override fun hashCode(): Int = id.hashCode() * 31 + updatedAt.hashCode()
}

/**
 * A user-created tag: freely create, attach/detach per task, and delete — no schema ceremony.
 * This is the one open-ended, user-extensible mechanism; [PropertyDefEntity] is reserved for the
 * fixed built-in fields (Priority/Due) and is never user-extended.
 */
@Entity(tableName = "label", indices = [Index(value = ["name"], unique = true, name = "idx_label_name")])
data class LabelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Many-to-many join between nodes and labels. Deleting a label cascades the attachment away. */
@Entity(
    tableName = "node_label",
    primaryKeys = ["node_id", "label_id"],
    foreignKeys = [
        ForeignKey(entity = NodeEntity::class, parentColumns = ["id"], childColumns = ["node_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LabelEntity::class, parentColumns = ["id"], childColumns = ["label_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["label_id"], name = "idx_node_label_label")]
)
data class NodeLabelEntity(
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "label_id") val labelId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

