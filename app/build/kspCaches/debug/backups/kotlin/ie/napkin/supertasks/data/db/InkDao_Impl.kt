package ie.napkin.supertasks.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.ByteArray
import kotlin.Double
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
public class InkDao_Impl(
  __db: RoomDatabase,
) : InkDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfInkStrokeEntity: EntityInsertAdapter<InkStrokeEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfInkStrokeEntity = object : EntityInsertAdapter<InkStrokeEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `ink_stroke` (`id`,`node_id`,`data`,`bbox_x`,`bbox_y`,`bbox_w`,`bbox_h`,`rank`,`created_at`,`updated_at`,`deleted_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: InkStrokeEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.nodeId)
        statement.bindBlob(3, entity.data)
        val _tmpBboxX: Double? = entity.bboxX
        if (_tmpBboxX == null) {
          statement.bindNull(4)
        } else {
          statement.bindDouble(4, _tmpBboxX)
        }
        val _tmpBboxY: Double? = entity.bboxY
        if (_tmpBboxY == null) {
          statement.bindNull(5)
        } else {
          statement.bindDouble(5, _tmpBboxY)
        }
        val _tmpBboxW: Double? = entity.bboxW
        if (_tmpBboxW == null) {
          statement.bindNull(6)
        } else {
          statement.bindDouble(6, _tmpBboxW)
        }
        val _tmpBboxH: Double? = entity.bboxH
        if (_tmpBboxH == null) {
          statement.bindNull(7)
        } else {
          statement.bindDouble(7, _tmpBboxH)
        }
        statement.bindText(8, entity.rank)
        statement.bindLong(9, entity.createdAt)
        statement.bindLong(10, entity.updatedAt)
        val _tmpDeletedAt: Long? = entity.deletedAt
        if (_tmpDeletedAt == null) {
          statement.bindNull(11)
        } else {
          statement.bindLong(11, _tmpDeletedAt)
        }
      }
    }
  }

  public override suspend fun insert(stroke: InkStrokeEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfInkStrokeEntity.insert(_connection, stroke)
  }

  public override fun strokes(nodeId: String): Flow<List<InkStrokeEntity>> {
    val _sql: String = "SELECT * FROM ink_stroke WHERE node_id = ? AND deleted_at IS NULL ORDER BY rank"
    return createFlow(__db, false, arrayOf("ink_stroke")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nodeId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfData: Int = getColumnIndexOrThrow(_stmt, "data")
        val _columnIndexOfBboxX: Int = getColumnIndexOrThrow(_stmt, "bbox_x")
        val _columnIndexOfBboxY: Int = getColumnIndexOrThrow(_stmt, "bbox_y")
        val _columnIndexOfBboxW: Int = getColumnIndexOrThrow(_stmt, "bbox_w")
        val _columnIndexOfBboxH: Int = getColumnIndexOrThrow(_stmt, "bbox_h")
        val _columnIndexOfRank: Int = getColumnIndexOrThrow(_stmt, "rank")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<InkStrokeEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: InkStrokeEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpData: ByteArray
          _tmpData = _stmt.getBlob(_columnIndexOfData)
          val _tmpBboxX: Double?
          if (_stmt.isNull(_columnIndexOfBboxX)) {
            _tmpBboxX = null
          } else {
            _tmpBboxX = _stmt.getDouble(_columnIndexOfBboxX)
          }
          val _tmpBboxY: Double?
          if (_stmt.isNull(_columnIndexOfBboxY)) {
            _tmpBboxY = null
          } else {
            _tmpBboxY = _stmt.getDouble(_columnIndexOfBboxY)
          }
          val _tmpBboxW: Double?
          if (_stmt.isNull(_columnIndexOfBboxW)) {
            _tmpBboxW = null
          } else {
            _tmpBboxW = _stmt.getDouble(_columnIndexOfBboxW)
          }
          val _tmpBboxH: Double?
          if (_stmt.isNull(_columnIndexOfBboxH)) {
            _tmpBboxH = null
          } else {
            _tmpBboxH = _stmt.getDouble(_columnIndexOfBboxH)
          }
          val _tmpRank: String
          _tmpRank = _stmt.getText(_columnIndexOfRank)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpDeletedAt: Long?
          if (_stmt.isNull(_columnIndexOfDeletedAt)) {
            _tmpDeletedAt = null
          } else {
            _tmpDeletedAt = _stmt.getLong(_columnIndexOfDeletedAt)
          }
          _item = InkStrokeEntity(_tmpId,_tmpNodeId,_tmpData,_tmpBboxX,_tmpBboxY,_tmpBboxW,_tmpBboxH,_tmpRank,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun strokesUnder(parentId: String): Flow<List<InkStrokeEntity>> {
    val _sql: String = """
        |
        |        SELECT s.* FROM ink_stroke s
        |         WHERE s.deleted_at IS NULL
        |           AND s.node_id IN (SELECT id FROM node WHERE parent_id = ? AND deleted_at IS NULL)
        |         ORDER BY s.rank
        |        
        """.trimMargin()
    return createFlow(__db, false, arrayOf("ink_stroke", "node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfData: Int = getColumnIndexOrThrow(_stmt, "data")
        val _columnIndexOfBboxX: Int = getColumnIndexOrThrow(_stmt, "bbox_x")
        val _columnIndexOfBboxY: Int = getColumnIndexOrThrow(_stmt, "bbox_y")
        val _columnIndexOfBboxW: Int = getColumnIndexOrThrow(_stmt, "bbox_w")
        val _columnIndexOfBboxH: Int = getColumnIndexOrThrow(_stmt, "bbox_h")
        val _columnIndexOfRank: Int = getColumnIndexOrThrow(_stmt, "rank")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<InkStrokeEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: InkStrokeEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpData: ByteArray
          _tmpData = _stmt.getBlob(_columnIndexOfData)
          val _tmpBboxX: Double?
          if (_stmt.isNull(_columnIndexOfBboxX)) {
            _tmpBboxX = null
          } else {
            _tmpBboxX = _stmt.getDouble(_columnIndexOfBboxX)
          }
          val _tmpBboxY: Double?
          if (_stmt.isNull(_columnIndexOfBboxY)) {
            _tmpBboxY = null
          } else {
            _tmpBboxY = _stmt.getDouble(_columnIndexOfBboxY)
          }
          val _tmpBboxW: Double?
          if (_stmt.isNull(_columnIndexOfBboxW)) {
            _tmpBboxW = null
          } else {
            _tmpBboxW = _stmt.getDouble(_columnIndexOfBboxW)
          }
          val _tmpBboxH: Double?
          if (_stmt.isNull(_columnIndexOfBboxH)) {
            _tmpBboxH = null
          } else {
            _tmpBboxH = _stmt.getDouble(_columnIndexOfBboxH)
          }
          val _tmpRank: String
          _tmpRank = _stmt.getText(_columnIndexOfRank)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpDeletedAt: Long?
          if (_stmt.isNull(_columnIndexOfDeletedAt)) {
            _tmpDeletedAt = null
          } else {
            _tmpDeletedAt = _stmt.getLong(_columnIndexOfDeletedAt)
          }
          _item = InkStrokeEntity(_tmpId,_tmpNodeId,_tmpData,_tmpBboxX,_tmpBboxY,_tmpBboxW,_tmpBboxH,_tmpRank,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun lastRank(nodeId: String): String? {
    val _sql: String = "SELECT MAX(rank) FROM ink_stroke WHERE node_id = ? AND deleted_at IS NULL"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nodeId)
        val _result: String?
        if (_stmt.step()) {
          val _tmp: String?
          if (_stmt.isNull(0)) {
            _tmp = null
          } else {
            _tmp = _stmt.getText(0)
          }
          _result = _tmp
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun softDeleteLast(nodeId: String, now: Long) {
    val _sql: String = """
        |
        |        UPDATE ink_stroke SET deleted_at = ?, updated_at = ?
        |         WHERE id = (
        |            SELECT id FROM ink_stroke WHERE node_id = ? AND deleted_at IS NULL
        |             ORDER BY rank DESC LIMIT 1
        |         )
        |        
        """.trimMargin()
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, now)
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, nodeId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun softDeleteAll(nodeId: String, now: Long) {
    val _sql: String = "UPDATE ink_stroke SET deleted_at = ?, updated_at = ? WHERE node_id = ? AND deleted_at IS NULL"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, now)
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, nodeId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun softDeleteById(id: String, now: Long) {
    val _sql: String = "UPDATE ink_stroke SET deleted_at = ?, updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, now)
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, id)
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
