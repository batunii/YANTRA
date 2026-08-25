package ie.napkin.supertasks.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class SubtreeTaskCount(
    val rootId: String,
    val total: Int,
    val doneCount: Int,
)

data class NodePomoCount(
    val nodeId: String,
    val count: Int,
    val totalSecs: Int,
)

data class ReminderRow(
    val nodeId: String,
    val atMillis: Long,
)

@Dao
interface NodeDao {

    @Query("SELECT * FROM node WHERE parent_id = :parentId AND deleted_at IS NULL ORDER BY rank")
    fun children(parentId: String): Flow<List<NodeEntity>>

    @Query("SELECT * FROM node WHERE parent_id IS NULL AND deleted_at IS NULL ORDER BY rank")
    fun topLevel(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM node WHERE type = 'list' AND deleted_at IS NULL ORDER BY rank")
    suspend fun allListsOnce(): List<NodeEntity>

    /** Every list & smart list at any nesting (top-level or inside a group), for the Home grid. */
    @Query("SELECT * FROM node WHERE type IN ('list','smart_list') AND deleted_at IS NULL ORDER BY rank")
    fun allLists(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM node WHERE parent_id = :parentId AND deleted_at IS NULL ORDER BY rank")
    suspend fun childrenOnce(parentId: String): List<NodeEntity>

    @Query("SELECT * FROM node WHERE id = :id")
    fun observe(id: String): Flow<NodeEntity?>

    @Query("SELECT * FROM node WHERE id = :id")
    suspend fun byId(id: String): NodeEntity?

    @Query("SELECT * FROM node WHERE system_key = :key AND deleted_at IS NULL LIMIT 1")
    suspend fun bySystemKey(key: String): NodeEntity?

    @Query("SELECT * FROM node WHERE type = :type AND title = :title AND deleted_at IS NULL ORDER BY created_at LIMIT 1")
    suspend fun byTypeAndTitle(type: String, title: String): NodeEntity?

    @Query("UPDATE node SET system_key = :key, updated_at = :now WHERE id = :id")
    suspend fun setSystemKey(id: String, key: String, now: Long)

    @Query("SELECT COUNT(*) FROM node")
    suspend fun countAll(): Int

    @Query("SELECT MAX(rank) FROM node WHERE parent_id = :parentId AND deleted_at IS NULL")
    suspend fun lastRank(parentId: String): String?

    @Query("SELECT MAX(rank) FROM node WHERE parent_id IS NULL AND deleted_at IS NULL")
    suspend fun lastRankTopLevel(): String?

    @Query("SELECT MIN(rank) FROM node WHERE parent_id = :parentId AND deleted_at IS NULL AND rank > :afterRank")
    suspend fun nextRank(parentId: String, afterRank: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(node: NodeEntity)

    // ---- index rebuild. The rows are derived from files, so replacing them wholesale is not
    // destructive; it is the only way to be sure the index says exactly what the workspace says.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<NodeEntity>)

    @Query("DELETE FROM node WHERE workspace_id = :ws")
    suspend fun clearNodes(ws: String)

    @Update
    suspend fun update(node: NodeEntity)

    @Query("UPDATE node SET title = :title, updated_at = :now WHERE id = :id")
    suspend fun setTitle(id: String, title: String?, now: Long)

    /**
     * Completing a task clears "in progress" in the same statement. The two are one fact seen from
     * two sides — a finished task is not still being worked on — and doing it here means no caller
     * can leave the pair inconsistent.
     */
    @Query("UPDATE node SET done = :done, in_progress = CASE WHEN :done THEN 0 ELSE in_progress END, updated_at = :now WHERE id = :id")
    suspend fun setDone(id: String, done: Boolean, now: Long)

    /** The middle state. Never set on a done task — see [setDone]. */
    @Query("UPDATE node SET in_progress = :inProgress, updated_at = :now WHERE id = :id AND done = 0")
    suspend fun setInProgress(id: String, inProgress: Boolean, now: Long)

    @Query("UPDATE node SET collapsed = :collapsed, updated_at = :now WHERE id = :id")
    suspend fun setCollapsed(id: String, collapsed: Boolean, now: Long)

    @Query("UPDATE node SET type = :type, updated_at = :now WHERE id = :id")
    suspend fun setType(id: String, type: String, now: Long)

    @Query("UPDATE node SET indent = :indent, updated_at = :now WHERE id = :id")
    suspend fun setIndent(id: String, indent: Int, now: Long)

    @Query("UPDATE node SET parent_id = :parentId, rank = :rank, updated_at = :now WHERE id = :id")
    suspend fun move(id: String, parentId: String?, rank: String, now: Long)

    @Query(
        """
        WITH RECURSIVE sub(id) AS (
            SELECT id FROM node WHERE id = :rootId
          UNION ALL
            SELECT n.id FROM node n JOIN sub s ON n.parent_id = s.id
        )
        SELECT id FROM sub
        """
    )
    suspend fun subtreeIds(rootId: String): List<String>

    // system_key is released on delete so the unique index never blocks a replacement node
    // from claiming the identity (tombstones otherwise hold the key forever).
    @Query("UPDATE node SET deleted_at = :now, updated_at = :now, system_key = NULL WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<String>, now: Long)

    /** Soft-deletes a node and its whole subtree (tombstones for sync). */
    @Transaction
    suspend fun softDeleteSubtree(rootId: String, now: Long) {
        softDelete(subtreeIds(rootId), now)
    }

    /** Task totals per list node (rooted at every list, so grouped lists count too). */
    @Query(
        """
        WITH RECURSIVE sub(rootId, id) AS (
            SELECT id, id FROM node WHERE type = 'list' AND deleted_at IS NULL
          UNION ALL
            SELECT s.rootId, n.id FROM node n JOIN sub s ON n.parent_id = s.id
             WHERE n.deleted_at IS NULL
        )
        SELECT s.rootId AS rootId,
               COUNT(*) AS total,
               COALESCE(SUM(n.done), 0) AS doneCount
          FROM sub s JOIN node n ON n.id = s.id
         WHERE n.type = 'task'
         GROUP BY s.rootId
        """
    )
    fun listTaskCounts(): Flow<List<SubtreeTaskCount>>

    /** How many live (direct) children each child of :parentId has — for chevrons/badges. */
    @Query(
        """
        SELECT n.parent_id AS rootId,
               COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN n.type = 'task' AND n.done = 1 THEN 1 ELSE 0 END), 0) AS doneCount
          FROM node n
         WHERE n.deleted_at IS NULL
           AND n.parent_id IN (SELECT id FROM node WHERE parent_id = :parentId AND deleted_at IS NULL)
         GROUP BY n.parent_id
        """
    )
    fun childCountsUnder(parentId: String): Flow<List<SubtreeTaskCount>>

    /**
     * The same counts for an arbitrary set of parents. A smart view gathers tasks from anywhere, so
     * there is no single parent to hang them off — but a task's chevron should say the same thing
     * wherever the task is shown.
     */
    @Query(
        """
        SELECT n.parent_id AS rootId,
               COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN n.type = 'task' AND n.done = 1 THEN 1 ELSE 0 END), 0) AS doneCount
          FROM node n
         WHERE n.deleted_at IS NULL
           AND n.parent_id IN (:parentIds)
         GROUP BY n.parent_id
        """
    )
    fun childCountsFor(parentIds: List<String>): Flow<List<SubtreeTaskCount>>

    @RawQuery(observedEntities = [NodeEntity::class, PropertyValueEntity::class, NodeLabelEntity::class])
    fun rawNodeQuery(query: SupportSQLiteQuery): Flow<List<NodeEntity>>
}

@Dao
interface PropertyDao {

    @Query("SELECT * FROM property_def WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE")
    fun defs(): Flow<List<PropertyDefEntity>>

    @Query("SELECT * FROM property_def WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE")
    suspend fun defsOnce(): List<PropertyDefEntity>

    /** Just the fixed Priority/Due fields — the only ones the pill row ever renders. */
    @Query("SELECT * FROM property_def WHERE deleted_at IS NULL AND is_built_in = 1 ORDER BY name COLLATE NOCASE")
    fun builtInDefs(): Flow<List<PropertyDefEntity>>

    @Query("SELECT * FROM property_def WHERE deleted_at IS NULL AND is_built_in = 1 ORDER BY name COLLATE NOCASE")
    suspend fun builtInDefsOnce(): List<PropertyDefEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDef(def: PropertyDefEntity)

    @Query("SELECT * FROM property_value WHERE node_id = :nodeId")
    fun valuesForNode(nodeId: String): Flow<List<PropertyValueEntity>>

    @Query("SELECT * FROM property_value WHERE node_id = :nodeId")
    suspend fun valuesForNodeOnce(nodeId: String): List<PropertyValueEntity>

    /**
     * Armed-reminder candidates on the Due def: rows with a reminder offset. Fire instant
     * computed in SQL — v_date minus offset minutes (negative offset = after the due
     * instant; see [BuiltIns]). The node join drops done/soft-deleted tasks automatically.
     */
    @Query(
        """
        SELECT pv.node_id AS nodeId,
               pv.v_date - CAST(pv.v_number AS INTEGER) * 60000 AS atMillis
          FROM property_value pv JOIN node n ON n.id = pv.node_id
         WHERE pv.def_id = :defId AND pv.v_date IS NOT NULL AND pv.v_number IS NOT NULL
           AND n.deleted_at IS NULL AND n.done = 0
        """
    )
    fun observeActiveReminders(defId: String): Flow<List<ReminderRow>>

    @Query(
        """
        SELECT pv.node_id AS nodeId,
               pv.v_date - CAST(pv.v_number AS INTEGER) * 60000 AS atMillis
          FROM property_value pv JOIN node n ON n.id = pv.node_id
         WHERE pv.def_id = :defId AND pv.v_date IS NOT NULL AND pv.v_number IS NOT NULL
           AND n.deleted_at IS NULL AND n.done = 0
        """
    )
    suspend fun activeRemindersOnce(defId: String): List<ReminderRow>

    @Query("SELECT id FROM property_def WHERE name = :name AND is_built_in = 1 AND deleted_at IS NULL LIMIT 1")
    suspend fun builtInDefIdByName(name: String): String?

    @Query("SELECT id FROM property_def WHERE name = :name AND is_built_in = 1 AND deleted_at IS NULL LIMIT 1")
    fun observeBuiltInDefIdByName(name: String): Flow<String?>

    /** Widget feed: only the built-in defs the widget renders, only the visible nodes. */
    @Query("SELECT * FROM property_value WHERE node_id IN (:ids) AND def_id IN (:defIds)")
    fun valuesForNodes(ids: List<String>, defIds: List<String>): Flow<List<PropertyValueEntity>>

    /** Values for every direct child of :parentId — one query feeds all chips on a page. */
    @Query(
        """
        SELECT pv.* FROM property_value pv
         WHERE pv.node_id IN (SELECT id FROM node WHERE parent_id = :parentId AND deleted_at IS NULL)
        """
    )
    fun valuesUnder(parentId: String): Flow<List<PropertyValueEntity>>

    @Query("SELECT * FROM property_value")
    fun allValues(): Flow<List<PropertyValueEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertValue(value: PropertyValueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertValues(values: List<PropertyValueEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDefs(defs: List<PropertyDefEntity>)

    @Query("DELETE FROM property_value WHERE workspace_id = :ws")
    suspend fun clearValues(ws: String)

    @Query("DELETE FROM property_def")
    suspend fun clearDefs()

    @Query("DELETE FROM property_value WHERE node_id = :nodeId AND def_id = :defId")
    suspend fun deleteValue(nodeId: String, defId: String)
}

@Dao
interface PomodoroDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: PomodoroSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<PomodoroSessionEntity>)

    @Query("DELETE FROM pomodoro_session WHERE workspace_id = :ws")
    suspend fun clearSessions(ws: String)

    @Update
    suspend fun update(session: PomodoroSessionEntity)

    @Query("SELECT * FROM pomodoro_session WHERE id = :id")
    suspend fun byId(id: String): PomodoroSessionEntity?

    @Query("SELECT * FROM pomodoro_session WHERE node_id = :nodeId ORDER BY started_at DESC")
    fun forNode(nodeId: String): Flow<List<PomodoroSessionEntity>>

    @Query("SELECT * FROM pomodoro_session ORDER BY started_at DESC")
    fun all(): Flow<List<PomodoroSessionEntity>>

    /** The one in-flight session, if any — ground truth for timer restore after process death. */
    @Query("SELECT * FROM pomodoro_session WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1")
    suspend fun openSession(): PomodoroSessionEntity?

    @Query("SELECT * FROM pomodoro_session ORDER BY started_at DESC LIMIT 1")
    suspend fun lastSession(): PomodoroSessionEntity?

    /** Completed-session counts per node — the little tomato badges on task rows. */
    @Query(
        """
        SELECT node_id AS nodeId, COUNT(*) AS count, COALESCE(SUM(actual_secs), 0) AS totalSecs
          FROM pomodoro_session WHERE completed = 1 GROUP BY node_id
        """
    )
    fun completedCounts(): Flow<List<NodePomoCount>>
}

@Dao
interface SmartListDao {

    @Query("SELECT * FROM smart_list_def WHERE node_id = :nodeId")
    fun observe(nodeId: String): Flow<SmartListDefEntity?>

    @Query("SELECT * FROM smart_list_def WHERE node_id = :nodeId")
    suspend fun byId(nodeId: String): SmartListDefEntity?

    @Query("SELECT * FROM smart_list_def")
    fun all(): Flow<List<SmartListDefEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(def: SmartListDefEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(defs: List<SmartListDefEntity>)

    @Query("DELETE FROM smart_list_def WHERE workspace_id = :ws")
    suspend fun clearSmartLists(ws: String)
}

@Dao
interface LabelDao {

    @Query("SELECT * FROM label ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM label ORDER BY name COLLATE NOCASE")
    suspend fun allOnce(): List<LabelEntity>

    @Query("SELECT * FROM label WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): LabelEntity?

    // IGNORE, not REPLACE: a name collision means "use the existing label", not
    // "silently overwrite its id/color" (see LabelRepository.getOrCreate).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsert(label: LabelEntity)

    @Query("SELECT * FROM node_label WHERE node_id = :nodeId")
    fun forNode(nodeId: String): Flow<List<NodeLabelEntity>>

    /** Labels for every direct child of :parentId — mirrors PropertyDao.valuesUnder. */
    @Query(
        """
        SELECT nl.* FROM node_label nl
         WHERE nl.node_id IN (SELECT id FROM node WHERE parent_id = :parentId AND deleted_at IS NULL)
        """
    )
    fun forChildrenOf(parentId: String): Flow<List<NodeLabelEntity>>

    @Query("SELECT * FROM node_label")
    fun allNodeLabels(): Flow<List<NodeLabelEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(nodeLabel: NodeLabelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(labels: List<LabelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun attachAll(links: List<NodeLabelEntity>)

    @Query("DELETE FROM label WHERE workspace_id = :ws")
    suspend fun clearLabels(ws: String)

    @Query("DELETE FROM node_label WHERE workspace_id = :ws")
    suspend fun clearNodeLabels(ws: String)

    @Query("DELETE FROM node_label WHERE node_id = :nodeId AND label_id = :labelId")
    suspend fun detach(nodeId: String, labelId: String)

    /** Recolour a label everywhere at once — the colour belongs to the tag, not to an attachment. */
    @Query("UPDATE label SET color = :color, updated_at = :now WHERE id = :id")
    suspend fun setColor(id: String, color: Long?, now: Long)
}

@Dao
interface InkDao {

    @Query("SELECT * FROM ink_stroke WHERE node_id = :nodeId AND deleted_at IS NULL ORDER BY rank")
    fun strokes(nodeId: String): Flow<List<InkStrokeEntity>>

    /** Strokes for every ink block that is a direct child of :parentId — page previews. */
    @Query(
        """
        SELECT s.* FROM ink_stroke s
         WHERE s.deleted_at IS NULL
           AND s.node_id IN (SELECT id FROM node WHERE parent_id = :parentId AND deleted_at IS NULL)
         ORDER BY s.rank
        """
    )
    fun strokesUnder(parentId: String): Flow<List<InkStrokeEntity>>

    @Query("SELECT MAX(rank) FROM ink_stroke WHERE node_id = :nodeId AND deleted_at IS NULL")
    suspend fun lastRank(nodeId: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(stroke: InkStrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(strokes: List<InkStrokeEntity>)

    @Query("DELETE FROM ink_stroke WHERE workspace_id = :ws")
    suspend fun clearStrokes(ws: String)

    @Query(
        """
        UPDATE ink_stroke SET deleted_at = :now, updated_at = :now
         WHERE id = (
            SELECT id FROM ink_stroke WHERE node_id = :nodeId AND deleted_at IS NULL
             ORDER BY rank DESC LIMIT 1
         )
        """
    )
    suspend fun softDeleteLast(nodeId: String, now: Long)

    @Query("UPDATE ink_stroke SET deleted_at = :now, updated_at = :now WHERE node_id = :nodeId AND deleted_at IS NULL")
    suspend fun softDeleteAll(nodeId: String, now: Long)

    @Query("UPDATE ink_stroke SET deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun softDeleteById(id: String, now: Long)
}
