package ie.napkin.supertasks.`data`.db

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class PomodoroDao_Impl(
  __db: RoomDatabase,
) : PomodoroDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfPomodoroSessionEntity: EntityInsertAdapter<PomodoroSessionEntity>

  private val __updateAdapterOfPomodoroSessionEntity:
      EntityDeleteOrUpdateAdapter<PomodoroSessionEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfPomodoroSessionEntity = object : EntityInsertAdapter<PomodoroSessionEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `pomodoro_session` (`id`,`node_id`,`started_at`,`ended_at`,`planned_secs`,`actual_secs`,`completed`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: PomodoroSessionEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.nodeId)
        statement.bindLong(3, entity.startedAt)
        val _tmpEndedAt: Long? = entity.endedAt
        if (_tmpEndedAt == null) {
          statement.bindNull(4)
        } else {
          statement.bindLong(4, _tmpEndedAt)
        }
        statement.bindLong(5, entity.plannedSecs.toLong())
        val _tmpActualSecs: Int? = entity.actualSecs
        if (_tmpActualSecs == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmpActualSecs.toLong())
        }
        val _tmp: Int = if (entity.completed) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        statement.bindLong(8, entity.createdAt)
        statement.bindLong(9, entity.updatedAt)
      }
    }
    this.__updateAdapterOfPomodoroSessionEntity = object : EntityDeleteOrUpdateAdapter<PomodoroSessionEntity>() {
      protected override fun createQuery(): String = "UPDATE OR ABORT `pomodoro_session` SET `id` = ?,`node_id` = ?,`started_at` = ?,`ended_at` = ?,`planned_secs` = ?,`actual_secs` = ?,`completed` = ?,`created_at` = ?,`updated_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: PomodoroSessionEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.nodeId)
        statement.bindLong(3, entity.startedAt)
        val _tmpEndedAt: Long? = entity.endedAt
        if (_tmpEndedAt == null) {
          statement.bindNull(4)
        } else {
          statement.bindLong(4, _tmpEndedAt)
        }
        statement.bindLong(5, entity.plannedSecs.toLong())
        val _tmpActualSecs: Int? = entity.actualSecs
        if (_tmpActualSecs == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmpActualSecs.toLong())
        }
        val _tmp: Int = if (entity.completed) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        statement.bindLong(8, entity.createdAt)
        statement.bindLong(9, entity.updatedAt)
        statement.bindText(10, entity.id)
      }
    }
  }

  public override suspend fun insert(session: PomodoroSessionEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfPomodoroSessionEntity.insert(_connection, session)
  }

  public override suspend fun update(session: PomodoroSessionEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __updateAdapterOfPomodoroSessionEntity.handle(_connection, session)
  }

  public override suspend fun byId(id: String): PomodoroSessionEntity? {
    val _sql: String = "SELECT * FROM pomodoro_session WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfStartedAt: Int = getColumnIndexOrThrow(_stmt, "started_at")
        val _columnIndexOfEndedAt: Int = getColumnIndexOrThrow(_stmt, "ended_at")
        val _columnIndexOfPlannedSecs: Int = getColumnIndexOrThrow(_stmt, "planned_secs")
        val _columnIndexOfActualSecs: Int = getColumnIndexOrThrow(_stmt, "actual_secs")
        val _columnIndexOfCompleted: Int = getColumnIndexOrThrow(_stmt, "completed")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: PomodoroSessionEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpStartedAt: Long
          _tmpStartedAt = _stmt.getLong(_columnIndexOfStartedAt)
          val _tmpEndedAt: Long?
          if (_stmt.isNull(_columnIndexOfEndedAt)) {
            _tmpEndedAt = null
          } else {
            _tmpEndedAt = _stmt.getLong(_columnIndexOfEndedAt)
          }
          val _tmpPlannedSecs: Int
          _tmpPlannedSecs = _stmt.getLong(_columnIndexOfPlannedSecs).toInt()
          val _tmpActualSecs: Int?
          if (_stmt.isNull(_columnIndexOfActualSecs)) {
            _tmpActualSecs = null
          } else {
            _tmpActualSecs = _stmt.getLong(_columnIndexOfActualSecs).toInt()
          }
          val _tmpCompleted: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfCompleted).toInt()
          _tmpCompleted = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = PomodoroSessionEntity(_tmpId,_tmpNodeId,_tmpStartedAt,_tmpEndedAt,_tmpPlannedSecs,_tmpActualSecs,_tmpCompleted,_tmpCreatedAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun forNode(nodeId: String): Flow<List<PomodoroSessionEntity>> {
    val _sql: String = "SELECT * FROM pomodoro_session WHERE node_id = ? ORDER BY started_at DESC"
    return createFlow(__db, false, arrayOf("pomodoro_session")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nodeId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfStartedAt: Int = getColumnIndexOrThrow(_stmt, "started_at")
        val _columnIndexOfEndedAt: Int = getColumnIndexOrThrow(_stmt, "ended_at")
        val _columnIndexOfPlannedSecs: Int = getColumnIndexOrThrow(_stmt, "planned_secs")
        val _columnIndexOfActualSecs: Int = getColumnIndexOrThrow(_stmt, "actual_secs")
        val _columnIndexOfCompleted: Int = getColumnIndexOrThrow(_stmt, "completed")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<PomodoroSessionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PomodoroSessionEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpStartedAt: Long
          _tmpStartedAt = _stmt.getLong(_columnIndexOfStartedAt)
          val _tmpEndedAt: Long?
          if (_stmt.isNull(_columnIndexOfEndedAt)) {
            _tmpEndedAt = null
          } else {
            _tmpEndedAt = _stmt.getLong(_columnIndexOfEndedAt)
          }
          val _tmpPlannedSecs: Int
          _tmpPlannedSecs = _stmt.getLong(_columnIndexOfPlannedSecs).toInt()
          val _tmpActualSecs: Int?
          if (_stmt.isNull(_columnIndexOfActualSecs)) {
            _tmpActualSecs = null
          } else {
            _tmpActualSecs = _stmt.getLong(_columnIndexOfActualSecs).toInt()
          }
          val _tmpCompleted: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfCompleted).toInt()
          _tmpCompleted = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = PomodoroSessionEntity(_tmpId,_tmpNodeId,_tmpStartedAt,_tmpEndedAt,_tmpPlannedSecs,_tmpActualSecs,_tmpCompleted,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun all(): Flow<List<PomodoroSessionEntity>> {
    val _sql: String = "SELECT * FROM pomodoro_session ORDER BY started_at DESC"
    return createFlow(__db, false, arrayOf("pomodoro_session")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfStartedAt: Int = getColumnIndexOrThrow(_stmt, "started_at")
        val _columnIndexOfEndedAt: Int = getColumnIndexOrThrow(_stmt, "ended_at")
        val _columnIndexOfPlannedSecs: Int = getColumnIndexOrThrow(_stmt, "planned_secs")
        val _columnIndexOfActualSecs: Int = getColumnIndexOrThrow(_stmt, "actual_secs")
        val _columnIndexOfCompleted: Int = getColumnIndexOrThrow(_stmt, "completed")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<PomodoroSessionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PomodoroSessionEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpStartedAt: Long
          _tmpStartedAt = _stmt.getLong(_columnIndexOfStartedAt)
          val _tmpEndedAt: Long?
          if (_stmt.isNull(_columnIndexOfEndedAt)) {
            _tmpEndedAt = null
          } else {
            _tmpEndedAt = _stmt.getLong(_columnIndexOfEndedAt)
          }
          val _tmpPlannedSecs: Int
          _tmpPlannedSecs = _stmt.getLong(_columnIndexOfPlannedSecs).toInt()
          val _tmpActualSecs: Int?
          if (_stmt.isNull(_columnIndexOfActualSecs)) {
            _tmpActualSecs = null
          } else {
            _tmpActualSecs = _stmt.getLong(_columnIndexOfActualSecs).toInt()
          }
          val _tmpCompleted: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfCompleted).toInt()
          _tmpCompleted = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = PomodoroSessionEntity(_tmpId,_tmpNodeId,_tmpStartedAt,_tmpEndedAt,_tmpPlannedSecs,_tmpActualSecs,_tmpCompleted,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun completedCounts(): Flow<List<NodePomoCount>> {
    val _sql: String = """
        |
        |        SELECT node_id AS nodeId, COUNT(*) AS count, COALESCE(SUM(actual_secs), 0) AS totalSecs
        |          FROM pomodoro_session WHERE completed = 1 GROUP BY node_id
        |        
        """.trimMargin()
    return createFlow(__db, false, arrayOf("pomodoro_session")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfNodeId: Int = 0
        val _columnIndexOfCount: Int = 1
        val _columnIndexOfTotalSecs: Int = 2
        val _result: MutableList<NodePomoCount> = mutableListOf()
        while (_stmt.step()) {
          val _item: NodePomoCount
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpCount: Int
          _tmpCount = _stmt.getLong(_columnIndexOfCount).toInt()
          val _tmpTotalSecs: Int
          _tmpTotalSecs = _stmt.getLong(_columnIndexOfTotalSecs).toInt()
          _item = NodePomoCount(_tmpNodeId,_tmpCount,_tmpTotalSecs)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
