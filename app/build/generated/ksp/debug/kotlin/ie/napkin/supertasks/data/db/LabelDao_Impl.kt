package ie.napkin.supertasks.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
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
public class LabelDao_Impl(
  __db: RoomDatabase,
) : LabelDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfLabelEntity: EntityInsertAdapter<LabelEntity>

  private val __insertAdapterOfNodeLabelEntity: EntityInsertAdapter<NodeLabelEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfLabelEntity = object : EntityInsertAdapter<LabelEntity>() {
      protected override fun createQuery(): String = "INSERT OR IGNORE INTO `label` (`id`,`name`,`color`,`created_at`,`updated_at`) VALUES (?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: LabelEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.name)
        val _tmpColor: Long? = entity.color
        if (_tmpColor == null) {
          statement.bindNull(3)
        } else {
          statement.bindLong(3, _tmpColor)
        }
        statement.bindLong(4, entity.createdAt)
        statement.bindLong(5, entity.updatedAt)
      }
    }
    this.__insertAdapterOfNodeLabelEntity = object : EntityInsertAdapter<NodeLabelEntity>() {
      protected override fun createQuery(): String = "INSERT OR IGNORE INTO `node_label` (`node_id`,`label_id`,`created_at`) VALUES (?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: NodeLabelEntity) {
        statement.bindText(1, entity.nodeId)
        statement.bindText(2, entity.labelId)
        statement.bindLong(3, entity.createdAt)
      }
    }
  }

  public override suspend fun upsert(label: LabelEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfLabelEntity.insert(_connection, label)
  }

  public override suspend fun attach(nodeLabel: NodeLabelEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfNodeLabelEntity.insert(_connection, nodeLabel)
  }

  public override fun all(): Flow<List<LabelEntity>> {
    val _sql: String = "SELECT * FROM label ORDER BY name COLLATE NOCASE"
    return createFlow(__db, false, arrayOf("label")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfColor: Int = getColumnIndexOrThrow(_stmt, "color")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<LabelEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: LabelEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpColor: Long?
          if (_stmt.isNull(_columnIndexOfColor)) {
            _tmpColor = null
          } else {
            _tmpColor = _stmt.getLong(_columnIndexOfColor)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = LabelEntity(_tmpId,_tmpName,_tmpColor,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun allOnce(): List<LabelEntity> {
    val _sql: String = "SELECT * FROM label ORDER BY name COLLATE NOCASE"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfColor: Int = getColumnIndexOrThrow(_stmt, "color")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<LabelEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: LabelEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpColor: Long?
          if (_stmt.isNull(_columnIndexOfColor)) {
            _tmpColor = null
          } else {
            _tmpColor = _stmt.getLong(_columnIndexOfColor)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = LabelEntity(_tmpId,_tmpName,_tmpColor,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun byName(name: String): LabelEntity? {
    val _sql: String = "SELECT * FROM label WHERE name = ? COLLATE NOCASE LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, name)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfColor: Int = getColumnIndexOrThrow(_stmt, "color")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: LabelEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpColor: Long?
          if (_stmt.isNull(_columnIndexOfColor)) {
            _tmpColor = null
          } else {
            _tmpColor = _stmt.getLong(_columnIndexOfColor)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = LabelEntity(_tmpId,_tmpName,_tmpColor,_tmpCreatedAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun forNode(nodeId: String): Flow<List<NodeLabelEntity>> {
    val _sql: String = "SELECT * FROM node_label WHERE node_id = ?"
    return createFlow(__db, false, arrayOf("node_label")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nodeId)
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfLabelId: Int = getColumnIndexOrThrow(_stmt, "label_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: MutableList<NodeLabelEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: NodeLabelEntity
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpLabelId: String
          _tmpLabelId = _stmt.getText(_columnIndexOfLabelId)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = NodeLabelEntity(_tmpNodeId,_tmpLabelId,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun forChildrenOf(parentId: String): Flow<List<NodeLabelEntity>> {
    val _sql: String = """
        |
        |        SELECT nl.* FROM node_label nl
        |         WHERE nl.node_id IN (SELECT id FROM node WHERE parent_id = ? AND deleted_at IS NULL)
        |        
        """.trimMargin()
    return createFlow(__db, false, arrayOf("node_label", "node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfLabelId: Int = getColumnIndexOrThrow(_stmt, "label_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: MutableList<NodeLabelEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: NodeLabelEntity
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpLabelId: String
          _tmpLabelId = _stmt.getText(_columnIndexOfLabelId)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = NodeLabelEntity(_tmpNodeId,_tmpLabelId,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun allNodeLabels(): Flow<List<NodeLabelEntity>> {
    val _sql: String = "SELECT * FROM node_label"
    return createFlow(__db, false, arrayOf("node_label")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfLabelId: Int = getColumnIndexOrThrow(_stmt, "label_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: MutableList<NodeLabelEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: NodeLabelEntity
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpLabelId: String
          _tmpLabelId = _stmt.getText(_columnIndexOfLabelId)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = NodeLabelEntity(_tmpNodeId,_tmpLabelId,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun delete(id: String) {
    val _sql: String = "DELETE FROM label WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun detach(nodeId: String, labelId: String) {
    val _sql: String = "DELETE FROM node_label WHERE node_id = ? AND label_id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nodeId)
        _argIndex = 2
        _stmt.bindText(_argIndex, labelId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
