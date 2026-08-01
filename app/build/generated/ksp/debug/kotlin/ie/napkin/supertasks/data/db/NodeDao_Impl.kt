package ie.napkin.supertasks.`data`.db

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.RoomRawQuery
import androidx.room.RoomSQLiteQuery
import androidx.room.coroutines.createFlow
import androidx.room.util.appendPlaceholders
import androidx.room.util.getColumnIndex
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performInTransactionSuspending
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteQuery
import javax.`annotation`.processing.Generated
import kotlin.Boolean
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
import kotlin.text.StringBuilder
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class NodeDao_Impl(
  __db: RoomDatabase,
) : NodeDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfNodeEntity: EntityInsertAdapter<NodeEntity>

  private val __updateAdapterOfNodeEntity: EntityDeleteOrUpdateAdapter<NodeEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfNodeEntity = object : EntityInsertAdapter<NodeEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `node` (`id`,`parent_id`,`type`,`title`,`rank`,`done`,`collapsed`,`canvas_x`,`canvas_y`,`canvas_w`,`canvas_h`,`created_at`,`updated_at`,`deleted_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: NodeEntity) {
        statement.bindText(1, entity.id)
        val _tmpParentId: String? = entity.parentId
        if (_tmpParentId == null) {
          statement.bindNull(2)
        } else {
          statement.bindText(2, _tmpParentId)
        }
        statement.bindText(3, entity.type)
        val _tmpTitle: String? = entity.title
        if (_tmpTitle == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpTitle)
        }
        statement.bindText(5, entity.rank)
        val _tmp: Int = if (entity.done) 1 else 0
        statement.bindLong(6, _tmp.toLong())
        val _tmp_1: Int = if (entity.collapsed) 1 else 0
        statement.bindLong(7, _tmp_1.toLong())
        val _tmpCanvasX: Double? = entity.canvasX
        if (_tmpCanvasX == null) {
          statement.bindNull(8)
        } else {
          statement.bindDouble(8, _tmpCanvasX)
        }
        val _tmpCanvasY: Double? = entity.canvasY
        if (_tmpCanvasY == null) {
          statement.bindNull(9)
        } else {
          statement.bindDouble(9, _tmpCanvasY)
        }
        val _tmpCanvasW: Double? = entity.canvasW
        if (_tmpCanvasW == null) {
          statement.bindNull(10)
        } else {
          statement.bindDouble(10, _tmpCanvasW)
        }
        val _tmpCanvasH: Double? = entity.canvasH
        if (_tmpCanvasH == null) {
          statement.bindNull(11)
        } else {
          statement.bindDouble(11, _tmpCanvasH)
        }
        statement.bindLong(12, entity.createdAt)
        statement.bindLong(13, entity.updatedAt)
        val _tmpDeletedAt: Long? = entity.deletedAt
        if (_tmpDeletedAt == null) {
          statement.bindNull(14)
        } else {
          statement.bindLong(14, _tmpDeletedAt)
        }
      }
    }
    this.__updateAdapterOfNodeEntity = object : EntityDeleteOrUpdateAdapter<NodeEntity>() {
      protected override fun createQuery(): String = "UPDATE OR ABORT `node` SET `id` = ?,`parent_id` = ?,`type` = ?,`title` = ?,`rank` = ?,`done` = ?,`collapsed` = ?,`canvas_x` = ?,`canvas_y` = ?,`canvas_w` = ?,`canvas_h` = ?,`created_at` = ?,`updated_at` = ?,`deleted_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: NodeEntity) {
        statement.bindText(1, entity.id)
        val _tmpParentId: String? = entity.parentId
        if (_tmpParentId == null) {
          statement.bindNull(2)
        } else {
          statement.bindText(2, _tmpParentId)
        }
        statement.bindText(3, entity.type)
        val _tmpTitle: String? = entity.title
        if (_tmpTitle == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpTitle)
        }
        statement.bindText(5, entity.rank)
        val _tmp: Int = if (entity.done) 1 else 0
        statement.bindLong(6, _tmp.toLong())
        val _tmp_1: Int = if (entity.collapsed) 1 else 0
        statement.bindLong(7, _tmp_1.toLong())
        val _tmpCanvasX: Double? = entity.canvasX
        if (_tmpCanvasX == null) {
          statement.bindNull(8)
        } else {
          statement.bindDouble(8, _tmpCanvasX)
        }
        val _tmpCanvasY: Double? = entity.canvasY
        if (_tmpCanvasY == null) {
          statement.bindNull(9)
        } else {
          statement.bindDouble(9, _tmpCanvasY)
        }
        val _tmpCanvasW: Double? = entity.canvasW
        if (_tmpCanvasW == null) {
          statement.bindNull(10)
        } else {
          statement.bindDouble(10, _tmpCanvasW)
        }
        val _tmpCanvasH: Double? = entity.canvasH
        if (_tmpCanvasH == null) {
          statement.bindNull(11)
        } else {
          statement.bindDouble(11, _tmpCanvasH)
        }
        statement.bindLong(12, entity.createdAt)
        statement.bindLong(13, entity.updatedAt)
        val _tmpDeletedAt: Long? = entity.deletedAt
        if (_tmpDeletedAt == null) {
          statement.bindNull(14)
        } else {
          statement.bindLong(14, _tmpDeletedAt)
        }
        statement.bindText(15, entity.id)
      }
    }
  }

  public override suspend fun insert(node: NodeEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfNodeEntity.insert(_connection, node)
  }

  public override suspend fun update(node: NodeEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __updateAdapterOfNodeEntity.handle(_connection, node)
  }

  public override suspend fun softDeleteSubtree(rootId: String, now: Long): Unit = performInTransactionSuspending(__db) {
    super@NodeDao_Impl.softDeleteSubtree(rootId, now)
  }

  public override fun children(parentId: String): Flow<List<NodeEntity>> {
    val _sql: String = "SELECT * FROM node WHERE parent_id = ? AND deleted_at IS NULL ORDER BY rank"
    return createFlow(__db, false, arrayOf("node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfParentId: Int = getColumnIndexOrThrow(_stmt, "parent_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfRank: Int = getColumnIndexOrThrow(_stmt, "rank")
        val _columnIndexOfDone: Int = getColumnIndexOrThrow(_stmt, "done")
        val _columnIndexOfCollapsed: Int = getColumnIndexOrThrow(_stmt, "collapsed")
        val _columnIndexOfCanvasX: Int = getColumnIndexOrThrow(_stmt, "canvas_x")
        val _columnIndexOfCanvasY: Int = getColumnIndexOrThrow(_stmt, "canvas_y")
        val _columnIndexOfCanvasW: Int = getColumnIndexOrThrow(_stmt, "canvas_w")
        val _columnIndexOfCanvasH: Int = getColumnIndexOrThrow(_stmt, "canvas_h")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<NodeEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: NodeEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpParentId: String?
          if (_stmt.isNull(_columnIndexOfParentId)) {
            _tmpParentId = null
          } else {
            _tmpParentId = _stmt.getText(_columnIndexOfParentId)
          }
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpRank: String
          _tmpRank = _stmt.getText(_columnIndexOfRank)
          val _tmpDone: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfDone).toInt()
          _tmpDone = _tmp != 0
          val _tmpCollapsed: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfCollapsed).toInt()
          _tmpCollapsed = _tmp_1 != 0
          val _tmpCanvasX: Double?
          if (_stmt.isNull(_columnIndexOfCanvasX)) {
            _tmpCanvasX = null
          } else {
            _tmpCanvasX = _stmt.getDouble(_columnIndexOfCanvasX)
          }
          val _tmpCanvasY: Double?
          if (_stmt.isNull(_columnIndexOfCanvasY)) {
            _tmpCanvasY = null
          } else {
            _tmpCanvasY = _stmt.getDouble(_columnIndexOfCanvasY)
          }
          val _tmpCanvasW: Double?
          if (_stmt.isNull(_columnIndexOfCanvasW)) {
            _tmpCanvasW = null
          } else {
            _tmpCanvasW = _stmt.getDouble(_columnIndexOfCanvasW)
          }
          val _tmpCanvasH: Double?
          if (_stmt.isNull(_columnIndexOfCanvasH)) {
            _tmpCanvasH = null
          } else {
            _tmpCanvasH = _stmt.getDouble(_columnIndexOfCanvasH)
          }
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
          _item = NodeEntity(_tmpId,_tmpParentId,_tmpType,_tmpTitle,_tmpRank,_tmpDone,_tmpCollapsed,_tmpCanvasX,_tmpCanvasY,_tmpCanvasW,_tmpCanvasH,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun topLevel(): Flow<List<NodeEntity>> {
    val _sql: String = "SELECT * FROM node WHERE parent_id IS NULL AND deleted_at IS NULL ORDER BY rank"
    return createFlow(__db, false, arrayOf("node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfParentId: Int = getColumnIndexOrThrow(_stmt, "parent_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfRank: Int = getColumnIndexOrThrow(_stmt, "rank")
        val _columnIndexOfDone: Int = getColumnIndexOrThrow(_stmt, "done")
        val _columnIndexOfCollapsed: Int = getColumnIndexOrThrow(_stmt, "collapsed")
        val _columnIndexOfCanvasX: Int = getColumnIndexOrThrow(_stmt, "canvas_x")
        val _columnIndexOfCanvasY: Int = getColumnIndexOrThrow(_stmt, "canvas_y")
        val _columnIndexOfCanvasW: Int = getColumnIndexOrThrow(_stmt, "canvas_w")
        val _columnIndexOfCanvasH: Int = getColumnIndexOrThrow(_stmt, "canvas_h")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<NodeEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: NodeEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpParentId: String?
          if (_stmt.isNull(_columnIndexOfParentId)) {
            _tmpParentId = null
          } else {
            _tmpParentId = _stmt.getText(_columnIndexOfParentId)
          }
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpRank: String
          _tmpRank = _stmt.getText(_columnIndexOfRank)
          val _tmpDone: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfDone).toInt()
          _tmpDone = _tmp != 0
          val _tmpCollapsed: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfCollapsed).toInt()
          _tmpCollapsed = _tmp_1 != 0
          val _tmpCanvasX: Double?
          if (_stmt.isNull(_columnIndexOfCanvasX)) {
            _tmpCanvasX = null
          } else {
            _tmpCanvasX = _stmt.getDouble(_columnIndexOfCanvasX)
          }
          val _tmpCanvasY: Double?
          if (_stmt.isNull(_columnIndexOfCanvasY)) {
            _tmpCanvasY = null
          } else {
            _tmpCanvasY = _stmt.getDouble(_columnIndexOfCanvasY)
          }
          val _tmpCanvasW: Double?
          if (_stmt.isNull(_columnIndexOfCanvasW)) {
            _tmpCanvasW = null
          } else {
            _tmpCanvasW = _stmt.getDouble(_columnIndexOfCanvasW)
          }
          val _tmpCanvasH: Double?
          if (_stmt.isNull(_columnIndexOfCanvasH)) {
            _tmpCanvasH = null
          } else {
            _tmpCanvasH = _stmt.getDouble(_columnIndexOfCanvasH)
          }
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
          _item = NodeEntity(_tmpId,_tmpParentId,_tmpType,_tmpTitle,_tmpRank,_tmpDone,_tmpCollapsed,_tmpCanvasX,_tmpCanvasY,_tmpCanvasW,_tmpCanvasH,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun allLists(): Flow<List<NodeEntity>> {
    val _sql: String = "SELECT * FROM node WHERE type IN ('list','smart_list') AND deleted_at IS NULL ORDER BY rank"
    return createFlow(__db, false, arrayOf("node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfParentId: Int = getColumnIndexOrThrow(_stmt, "parent_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfRank: Int = getColumnIndexOrThrow(_stmt, "rank")
        val _columnIndexOfDone: Int = getColumnIndexOrThrow(_stmt, "done")
        val _columnIndexOfCollapsed: Int = getColumnIndexOrThrow(_stmt, "collapsed")
        val _columnIndexOfCanvasX: Int = getColumnIndexOrThrow(_stmt, "canvas_x")
        val _columnIndexOfCanvasY: Int = getColumnIndexOrThrow(_stmt, "canvas_y")
        val _columnIndexOfCanvasW: Int = getColumnIndexOrThrow(_stmt, "canvas_w")
        val _columnIndexOfCanvasH: Int = getColumnIndexOrThrow(_stmt, "canvas_h")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<NodeEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: NodeEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpParentId: String?
          if (_stmt.isNull(_columnIndexOfParentId)) {
            _tmpParentId = null
          } else {
            _tmpParentId = _stmt.getText(_columnIndexOfParentId)
          }
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpRank: String
          _tmpRank = _stmt.getText(_columnIndexOfRank)
          val _tmpDone: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfDone).toInt()
          _tmpDone = _tmp != 0
          val _tmpCollapsed: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfCollapsed).toInt()
          _tmpCollapsed = _tmp_1 != 0
          val _tmpCanvasX: Double?
          if (_stmt.isNull(_columnIndexOfCanvasX)) {
            _tmpCanvasX = null
          } else {
            _tmpCanvasX = _stmt.getDouble(_columnIndexOfCanvasX)
          }
          val _tmpCanvasY: Double?
          if (_stmt.isNull(_columnIndexOfCanvasY)) {
            _tmpCanvasY = null
          } else {
            _tmpCanvasY = _stmt.getDouble(_columnIndexOfCanvasY)
          }
          val _tmpCanvasW: Double?
          if (_stmt.isNull(_columnIndexOfCanvasW)) {
            _tmpCanvasW = null
          } else {
            _tmpCanvasW = _stmt.getDouble(_columnIndexOfCanvasW)
          }
          val _tmpCanvasH: Double?
          if (_stmt.isNull(_columnIndexOfCanvasH)) {
            _tmpCanvasH = null
          } else {
            _tmpCanvasH = _stmt.getDouble(_columnIndexOfCanvasH)
          }
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
          _item = NodeEntity(_tmpId,_tmpParentId,_tmpType,_tmpTitle,_tmpRank,_tmpDone,_tmpCollapsed,_tmpCanvasX,_tmpCanvasY,_tmpCanvasW,_tmpCanvasH,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun childrenOnce(parentId: String): List<NodeEntity> {
    val _sql: String = "SELECT * FROM node WHERE parent_id = ? AND deleted_at IS NULL ORDER BY rank"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfParentId: Int = getColumnIndexOrThrow(_stmt, "parent_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfRank: Int = getColumnIndexOrThrow(_stmt, "rank")
        val _columnIndexOfDone: Int = getColumnIndexOrThrow(_stmt, "done")
        val _columnIndexOfCollapsed: Int = getColumnIndexOrThrow(_stmt, "collapsed")
        val _columnIndexOfCanvasX: Int = getColumnIndexOrThrow(_stmt, "canvas_x")
        val _columnIndexOfCanvasY: Int = getColumnIndexOrThrow(_stmt, "canvas_y")
        val _columnIndexOfCanvasW: Int = getColumnIndexOrThrow(_stmt, "canvas_w")
        val _columnIndexOfCanvasH: Int = getColumnIndexOrThrow(_stmt, "canvas_h")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<NodeEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: NodeEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpParentId: String?
          if (_stmt.isNull(_columnIndexOfParentId)) {
            _tmpParentId = null
          } else {
            _tmpParentId = _stmt.getText(_columnIndexOfParentId)
          }
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpRank: String
          _tmpRank = _stmt.getText(_columnIndexOfRank)
          val _tmpDone: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfDone).toInt()
          _tmpDone = _tmp != 0
          val _tmpCollapsed: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfCollapsed).toInt()
          _tmpCollapsed = _tmp_1 != 0
          val _tmpCanvasX: Double?
          if (_stmt.isNull(_columnIndexOfCanvasX)) {
            _tmpCanvasX = null
          } else {
            _tmpCanvasX = _stmt.getDouble(_columnIndexOfCanvasX)
          }
          val _tmpCanvasY: Double?
          if (_stmt.isNull(_columnIndexOfCanvasY)) {
            _tmpCanvasY = null
          } else {
            _tmpCanvasY = _stmt.getDouble(_columnIndexOfCanvasY)
          }
          val _tmpCanvasW: Double?
          if (_stmt.isNull(_columnIndexOfCanvasW)) {
            _tmpCanvasW = null
          } else {
            _tmpCanvasW = _stmt.getDouble(_columnIndexOfCanvasW)
          }
          val _tmpCanvasH: Double?
          if (_stmt.isNull(_columnIndexOfCanvasH)) {
            _tmpCanvasH = null
          } else {
            _tmpCanvasH = _stmt.getDouble(_columnIndexOfCanvasH)
          }
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
          _item = NodeEntity(_tmpId,_tmpParentId,_tmpType,_tmpTitle,_tmpRank,_tmpDone,_tmpCollapsed,_tmpCanvasX,_tmpCanvasY,_tmpCanvasW,_tmpCanvasH,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observe(id: String): Flow<NodeEntity?> {
    val _sql: String = "SELECT * FROM node WHERE id = ?"
    return createFlow(__db, false, arrayOf("node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfParentId: Int = getColumnIndexOrThrow(_stmt, "parent_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfRank: Int = getColumnIndexOrThrow(_stmt, "rank")
        val _columnIndexOfDone: Int = getColumnIndexOrThrow(_stmt, "done")
        val _columnIndexOfCollapsed: Int = getColumnIndexOrThrow(_stmt, "collapsed")
        val _columnIndexOfCanvasX: Int = getColumnIndexOrThrow(_stmt, "canvas_x")
        val _columnIndexOfCanvasY: Int = getColumnIndexOrThrow(_stmt, "canvas_y")
        val _columnIndexOfCanvasW: Int = getColumnIndexOrThrow(_stmt, "canvas_w")
        val _columnIndexOfCanvasH: Int = getColumnIndexOrThrow(_stmt, "canvas_h")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: NodeEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpParentId: String?
          if (_stmt.isNull(_columnIndexOfParentId)) {
            _tmpParentId = null
          } else {
            _tmpParentId = _stmt.getText(_columnIndexOfParentId)
          }
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpRank: String
          _tmpRank = _stmt.getText(_columnIndexOfRank)
          val _tmpDone: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfDone).toInt()
          _tmpDone = _tmp != 0
          val _tmpCollapsed: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfCollapsed).toInt()
          _tmpCollapsed = _tmp_1 != 0
          val _tmpCanvasX: Double?
          if (_stmt.isNull(_columnIndexOfCanvasX)) {
            _tmpCanvasX = null
          } else {
            _tmpCanvasX = _stmt.getDouble(_columnIndexOfCanvasX)
          }
          val _tmpCanvasY: Double?
          if (_stmt.isNull(_columnIndexOfCanvasY)) {
            _tmpCanvasY = null
          } else {
            _tmpCanvasY = _stmt.getDouble(_columnIndexOfCanvasY)
          }
          val _tmpCanvasW: Double?
          if (_stmt.isNull(_columnIndexOfCanvasW)) {
            _tmpCanvasW = null
          } else {
            _tmpCanvasW = _stmt.getDouble(_columnIndexOfCanvasW)
          }
          val _tmpCanvasH: Double?
          if (_stmt.isNull(_columnIndexOfCanvasH)) {
            _tmpCanvasH = null
          } else {
            _tmpCanvasH = _stmt.getDouble(_columnIndexOfCanvasH)
          }
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
          _result = NodeEntity(_tmpId,_tmpParentId,_tmpType,_tmpTitle,_tmpRank,_tmpDone,_tmpCollapsed,_tmpCanvasX,_tmpCanvasY,_tmpCanvasW,_tmpCanvasH,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun byId(id: String): NodeEntity? {
    val _sql: String = "SELECT * FROM node WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfParentId: Int = getColumnIndexOrThrow(_stmt, "parent_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfRank: Int = getColumnIndexOrThrow(_stmt, "rank")
        val _columnIndexOfDone: Int = getColumnIndexOrThrow(_stmt, "done")
        val _columnIndexOfCollapsed: Int = getColumnIndexOrThrow(_stmt, "collapsed")
        val _columnIndexOfCanvasX: Int = getColumnIndexOrThrow(_stmt, "canvas_x")
        val _columnIndexOfCanvasY: Int = getColumnIndexOrThrow(_stmt, "canvas_y")
        val _columnIndexOfCanvasW: Int = getColumnIndexOrThrow(_stmt, "canvas_w")
        val _columnIndexOfCanvasH: Int = getColumnIndexOrThrow(_stmt, "canvas_h")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: NodeEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpParentId: String?
          if (_stmt.isNull(_columnIndexOfParentId)) {
            _tmpParentId = null
          } else {
            _tmpParentId = _stmt.getText(_columnIndexOfParentId)
          }
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpRank: String
          _tmpRank = _stmt.getText(_columnIndexOfRank)
          val _tmpDone: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfDone).toInt()
          _tmpDone = _tmp != 0
          val _tmpCollapsed: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfCollapsed).toInt()
          _tmpCollapsed = _tmp_1 != 0
          val _tmpCanvasX: Double?
          if (_stmt.isNull(_columnIndexOfCanvasX)) {
            _tmpCanvasX = null
          } else {
            _tmpCanvasX = _stmt.getDouble(_columnIndexOfCanvasX)
          }
          val _tmpCanvasY: Double?
          if (_stmt.isNull(_columnIndexOfCanvasY)) {
            _tmpCanvasY = null
          } else {
            _tmpCanvasY = _stmt.getDouble(_columnIndexOfCanvasY)
          }
          val _tmpCanvasW: Double?
          if (_stmt.isNull(_columnIndexOfCanvasW)) {
            _tmpCanvasW = null
          } else {
            _tmpCanvasW = _stmt.getDouble(_columnIndexOfCanvasW)
          }
          val _tmpCanvasH: Double?
          if (_stmt.isNull(_columnIndexOfCanvasH)) {
            _tmpCanvasH = null
          } else {
            _tmpCanvasH = _stmt.getDouble(_columnIndexOfCanvasH)
          }
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
          _result = NodeEntity(_tmpId,_tmpParentId,_tmpType,_tmpTitle,_tmpRank,_tmpDone,_tmpCollapsed,_tmpCanvasX,_tmpCanvasY,_tmpCanvasW,_tmpCanvasH,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun countAll(): Int {
    val _sql: String = "SELECT COUNT(*) FROM node"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun lastRank(parentId: String): String? {
    val _sql: String = "SELECT MAX(rank) FROM node WHERE parent_id = ? AND deleted_at IS NULL"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
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

  public override suspend fun lastRankTopLevel(): String? {
    val _sql: String = "SELECT MAX(rank) FROM node WHERE parent_id IS NULL AND deleted_at IS NULL"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
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

  public override suspend fun nextRank(parentId: String, afterRank: String): String? {
    val _sql: String = "SELECT MIN(rank) FROM node WHERE parent_id = ? AND deleted_at IS NULL AND rank > ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
        _argIndex = 2
        _stmt.bindText(_argIndex, afterRank)
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

  public override suspend fun subtreeIds(rootId: String): List<String> {
    val _sql: String = """
        |
        |        WITH RECURSIVE sub(id) AS (
        |            SELECT id FROM node WHERE id = ?
        |          UNION ALL
        |            SELECT n.id FROM node n JOIN sub s ON n.parent_id = s.id
        |        )
        |        SELECT id FROM sub
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, rootId)
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun childCount(parentId: String): Int {
    val _sql: String = "SELECT COUNT(*) FROM node WHERE parent_id = ? AND deleted_at IS NULL"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun hasChildren(parentId: String): Flow<Boolean> {
    val _sql: String = """
        |
        |        SELECT EXISTS(
        |            SELECT 1 FROM node WHERE parent_id = ? AND deleted_at IS NULL LIMIT 1
        |        )
        |        
        """.trimMargin()
    return createFlow(__db, false, arrayOf("node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
        val _result: Boolean
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp != 0
        } else {
          _result = false
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun topLevelTaskCounts(): Flow<List<SubtreeTaskCount>> {
    val _sql: String = """
        |
        |        WITH RECURSIVE sub(rootId, id) AS (
        |            SELECT id, id FROM node WHERE parent_id IS NULL AND deleted_at IS NULL
        |          UNION ALL
        |            SELECT s.rootId, n.id FROM node n JOIN sub s ON n.parent_id = s.id
        |             WHERE n.deleted_at IS NULL
        |        )
        |        SELECT s.rootId AS rootId,
        |               COUNT(*) AS total,
        |               COALESCE(SUM(n.done), 0) AS doneCount
        |          FROM sub s JOIN node n ON n.id = s.id
        |         WHERE n.type = 'task'
        |         GROUP BY s.rootId
        |        
        """.trimMargin()
    return createFlow(__db, false, arrayOf("node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfRootId: Int = 0
        val _columnIndexOfTotal: Int = 1
        val _columnIndexOfDoneCount: Int = 2
        val _result: MutableList<SubtreeTaskCount> = mutableListOf()
        while (_stmt.step()) {
          val _item: SubtreeTaskCount
          val _tmpRootId: String
          _tmpRootId = _stmt.getText(_columnIndexOfRootId)
          val _tmpTotal: Int
          _tmpTotal = _stmt.getLong(_columnIndexOfTotal).toInt()
          val _tmpDoneCount: Int
          _tmpDoneCount = _stmt.getLong(_columnIndexOfDoneCount).toInt()
          _item = SubtreeTaskCount(_tmpRootId,_tmpTotal,_tmpDoneCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun listTaskCounts(): Flow<List<SubtreeTaskCount>> {
    val _sql: String = """
        |
        |        WITH RECURSIVE sub(rootId, id) AS (
        |            SELECT id, id FROM node WHERE type = 'list' AND deleted_at IS NULL
        |          UNION ALL
        |            SELECT s.rootId, n.id FROM node n JOIN sub s ON n.parent_id = s.id
        |             WHERE n.deleted_at IS NULL
        |        )
        |        SELECT s.rootId AS rootId,
        |               COUNT(*) AS total,
        |               COALESCE(SUM(n.done), 0) AS doneCount
        |          FROM sub s JOIN node n ON n.id = s.id
        |         WHERE n.type = 'task'
        |         GROUP BY s.rootId
        |        
        """.trimMargin()
    return createFlow(__db, false, arrayOf("node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfRootId: Int = 0
        val _columnIndexOfTotal: Int = 1
        val _columnIndexOfDoneCount: Int = 2
        val _result: MutableList<SubtreeTaskCount> = mutableListOf()
        while (_stmt.step()) {
          val _item: SubtreeTaskCount
          val _tmpRootId: String
          _tmpRootId = _stmt.getText(_columnIndexOfRootId)
          val _tmpTotal: Int
          _tmpTotal = _stmt.getLong(_columnIndexOfTotal).toInt()
          val _tmpDoneCount: Int
          _tmpDoneCount = _stmt.getLong(_columnIndexOfDoneCount).toInt()
          _item = SubtreeTaskCount(_tmpRootId,_tmpTotal,_tmpDoneCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun childCountsUnder(parentId: String): Flow<List<SubtreeTaskCount>> {
    val _sql: String = """
        |
        |        SELECT n.parent_id AS rootId,
        |               COUNT(*) AS total,
        |               COALESCE(SUM(CASE WHEN n.type = 'task' AND n.done = 1 THEN 1 ELSE 0 END), 0) AS doneCount
        |          FROM node n
        |         WHERE n.deleted_at IS NULL
        |           AND n.parent_id IN (SELECT id FROM node WHERE parent_id = ? AND deleted_at IS NULL)
        |         GROUP BY n.parent_id
        |        
        """.trimMargin()
    return createFlow(__db, false, arrayOf("node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
        val _columnIndexOfRootId: Int = 0
        val _columnIndexOfTotal: Int = 1
        val _columnIndexOfDoneCount: Int = 2
        val _result: MutableList<SubtreeTaskCount> = mutableListOf()
        while (_stmt.step()) {
          val _item: SubtreeTaskCount
          val _tmpRootId: String
          _tmpRootId = _stmt.getText(_columnIndexOfRootId)
          val _tmpTotal: Int
          _tmpTotal = _stmt.getLong(_columnIndexOfTotal).toInt()
          val _tmpDoneCount: Int
          _tmpDoneCount = _stmt.getLong(_columnIndexOfDoneCount).toInt()
          _item = SubtreeTaskCount(_tmpRootId,_tmpTotal,_tmpDoneCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun setTitle(
    id: String,
    title: String?,
    now: Long,
  ) {
    val _sql: String = "UPDATE node SET title = ?, updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        if (title == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, title)
        }
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

  public override suspend fun setDone(
    id: String,
    done: Boolean,
    now: Long,
  ) {
    val _sql: String = "UPDATE node SET done = ?, updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (done) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
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

  public override suspend fun setCollapsed(
    id: String,
    collapsed: Boolean,
    now: Long,
  ) {
    val _sql: String = "UPDATE node SET collapsed = ?, updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (collapsed) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
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

  public override suspend fun setType(
    id: String,
    type: String,
    now: Long,
  ) {
    val _sql: String = "UPDATE node SET type = ?, updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, type)
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

  public override suspend fun move(
    id: String,
    parentId: String?,
    rank: String,
    now: Long,
  ) {
    val _sql: String = "UPDATE node SET parent_id = ?, rank = ?, updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        if (parentId == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, parentId)
        }
        _argIndex = 2
        _stmt.bindText(_argIndex, rank)
        _argIndex = 3
        _stmt.bindLong(_argIndex, now)
        _argIndex = 4
        _stmt.bindText(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun softDelete(ids: List<String>, now: Long) {
    val _stringBuilder: StringBuilder = StringBuilder()
    _stringBuilder.append("UPDATE node SET deleted_at = ")
    _stringBuilder.append("?")
    _stringBuilder.append(", updated_at = ")
    _stringBuilder.append("?")
    _stringBuilder.append(" WHERE id IN (")
    val _inputSize: Int = ids.size
    appendPlaceholders(_stringBuilder, _inputSize)
    _stringBuilder.append(")")
    val _sql: String = _stringBuilder.toString()
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, now)
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        for (_item: String in ids) {
          _stmt.bindText(_argIndex, _item)
          _argIndex++
        }
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun rawNodeQuery(query: SupportSQLiteQuery): Flow<List<NodeEntity>> {
    val _rawQuery: RoomRawQuery = RoomSQLiteQuery.copyFrom(query).toRoomRawQuery()
    val _sql: String = _rawQuery.sql
    return createFlow(__db, false, arrayOf("node", "property_value", "node_label")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _rawQuery.getBindingFunction().invoke(_stmt)
        val _result: MutableList<NodeEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: NodeEntity
          _item = __entityStatementConverter_ieNapkinSupertasksDataDbNodeEntity(_stmt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  private fun __entityStatementConverter_ieNapkinSupertasksDataDbNodeEntity(statement: SQLiteStatement): NodeEntity {
    val _entity: NodeEntity
    val _columnIndexOfId: Int = getColumnIndex(statement, "id")
    val _columnIndexOfParentId: Int = getColumnIndex(statement, "parent_id")
    val _columnIndexOfType: Int = getColumnIndex(statement, "type")
    val _columnIndexOfTitle: Int = getColumnIndex(statement, "title")
    val _columnIndexOfRank: Int = getColumnIndex(statement, "rank")
    val _columnIndexOfDone: Int = getColumnIndex(statement, "done")
    val _columnIndexOfCollapsed: Int = getColumnIndex(statement, "collapsed")
    val _columnIndexOfCanvasX: Int = getColumnIndex(statement, "canvas_x")
    val _columnIndexOfCanvasY: Int = getColumnIndex(statement, "canvas_y")
    val _columnIndexOfCanvasW: Int = getColumnIndex(statement, "canvas_w")
    val _columnIndexOfCanvasH: Int = getColumnIndex(statement, "canvas_h")
    val _columnIndexOfCreatedAt: Int = getColumnIndex(statement, "created_at")
    val _columnIndexOfUpdatedAt: Int = getColumnIndex(statement, "updated_at")
    val _columnIndexOfDeletedAt: Int = getColumnIndex(statement, "deleted_at")
    val _tmpId: String
    if (_columnIndexOfId == -1) {
      error("Missing column 'id' for a NON-NULL value, column not found in result.")
    } else {
      _tmpId = statement.getText(_columnIndexOfId)
    }
    val _tmpParentId: String?
    if (_columnIndexOfParentId == -1) {
      _tmpParentId = null
    } else {
      if (statement.isNull(_columnIndexOfParentId)) {
        _tmpParentId = null
      } else {
        _tmpParentId = statement.getText(_columnIndexOfParentId)
      }
    }
    val _tmpType: String
    if (_columnIndexOfType == -1) {
      error("Missing column 'type' for a NON-NULL value, column not found in result.")
    } else {
      _tmpType = statement.getText(_columnIndexOfType)
    }
    val _tmpTitle: String?
    if (_columnIndexOfTitle == -1) {
      _tmpTitle = null
    } else {
      if (statement.isNull(_columnIndexOfTitle)) {
        _tmpTitle = null
      } else {
        _tmpTitle = statement.getText(_columnIndexOfTitle)
      }
    }
    val _tmpRank: String
    if (_columnIndexOfRank == -1) {
      error("Missing column 'rank' for a NON-NULL value, column not found in result.")
    } else {
      _tmpRank = statement.getText(_columnIndexOfRank)
    }
    val _tmpDone: Boolean
    if (_columnIndexOfDone == -1) {
      _tmpDone = false
    } else {
      val _tmp: Int
      _tmp = statement.getLong(_columnIndexOfDone).toInt()
      _tmpDone = _tmp != 0
    }
    val _tmpCollapsed: Boolean
    if (_columnIndexOfCollapsed == -1) {
      _tmpCollapsed = false
    } else {
      val _tmp_1: Int
      _tmp_1 = statement.getLong(_columnIndexOfCollapsed).toInt()
      _tmpCollapsed = _tmp_1 != 0
    }
    val _tmpCanvasX: Double?
    if (_columnIndexOfCanvasX == -1) {
      _tmpCanvasX = null
    } else {
      if (statement.isNull(_columnIndexOfCanvasX)) {
        _tmpCanvasX = null
      } else {
        _tmpCanvasX = statement.getDouble(_columnIndexOfCanvasX)
      }
    }
    val _tmpCanvasY: Double?
    if (_columnIndexOfCanvasY == -1) {
      _tmpCanvasY = null
    } else {
      if (statement.isNull(_columnIndexOfCanvasY)) {
        _tmpCanvasY = null
      } else {
        _tmpCanvasY = statement.getDouble(_columnIndexOfCanvasY)
      }
    }
    val _tmpCanvasW: Double?
    if (_columnIndexOfCanvasW == -1) {
      _tmpCanvasW = null
    } else {
      if (statement.isNull(_columnIndexOfCanvasW)) {
        _tmpCanvasW = null
      } else {
        _tmpCanvasW = statement.getDouble(_columnIndexOfCanvasW)
      }
    }
    val _tmpCanvasH: Double?
    if (_columnIndexOfCanvasH == -1) {
      _tmpCanvasH = null
    } else {
      if (statement.isNull(_columnIndexOfCanvasH)) {
        _tmpCanvasH = null
      } else {
        _tmpCanvasH = statement.getDouble(_columnIndexOfCanvasH)
      }
    }
    val _tmpCreatedAt: Long
    if (_columnIndexOfCreatedAt == -1) {
      _tmpCreatedAt = 0
    } else {
      _tmpCreatedAt = statement.getLong(_columnIndexOfCreatedAt)
    }
    val _tmpUpdatedAt: Long
    if (_columnIndexOfUpdatedAt == -1) {
      _tmpUpdatedAt = 0
    } else {
      _tmpUpdatedAt = statement.getLong(_columnIndexOfUpdatedAt)
    }
    val _tmpDeletedAt: Long?
    if (_columnIndexOfDeletedAt == -1) {
      _tmpDeletedAt = null
    } else {
      if (statement.isNull(_columnIndexOfDeletedAt)) {
        _tmpDeletedAt = null
      } else {
        _tmpDeletedAt = statement.getLong(_columnIndexOfDeletedAt)
      }
    }
    _entity = NodeEntity(_tmpId,_tmpParentId,_tmpType,_tmpTitle,_tmpRank,_tmpDone,_tmpCollapsed,_tmpCanvasX,_tmpCanvasY,_tmpCanvasW,_tmpCanvasH,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
    return _entity
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
