package ie.napkin.supertasks.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
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
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class PropertyDao_Impl(
  __db: RoomDatabase,
) : PropertyDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfPropertyDefEntity: EntityInsertAdapter<PropertyDefEntity>

  private val __insertAdapterOfPropertyValueEntity: EntityInsertAdapter<PropertyValueEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfPropertyDefEntity = object : EntityInsertAdapter<PropertyDefEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `property_def` (`id`,`name`,`kind`,`config`,`is_built_in`,`created_at`,`updated_at`,`deleted_at`) VALUES (?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: PropertyDefEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.kind)
        val _tmpConfig: String? = entity.config
        if (_tmpConfig == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpConfig)
        }
        val _tmp: Int = if (entity.isBuiltIn) 1 else 0
        statement.bindLong(5, _tmp.toLong())
        statement.bindLong(6, entity.createdAt)
        statement.bindLong(7, entity.updatedAt)
        val _tmpDeletedAt: Long? = entity.deletedAt
        if (_tmpDeletedAt == null) {
          statement.bindNull(8)
        } else {
          statement.bindLong(8, _tmpDeletedAt)
        }
      }
    }
    this.__insertAdapterOfPropertyValueEntity = object : EntityInsertAdapter<PropertyValueEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `property_value` (`node_id`,`def_id`,`v_text`,`v_number`,`v_date`,`v_bool`,`updated_at`) VALUES (?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: PropertyValueEntity) {
        statement.bindText(1, entity.nodeId)
        statement.bindText(2, entity.defId)
        val _tmpVText: String? = entity.vText
        if (_tmpVText == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpVText)
        }
        val _tmpVNumber: Double? = entity.vNumber
        if (_tmpVNumber == null) {
          statement.bindNull(4)
        } else {
          statement.bindDouble(4, _tmpVNumber)
        }
        val _tmpVDate: Long? = entity.vDate
        if (_tmpVDate == null) {
          statement.bindNull(5)
        } else {
          statement.bindLong(5, _tmpVDate)
        }
        val _tmpVBool: Boolean? = entity.vBool
        val _tmp: Int? = _tmpVBool?.let { if (it) 1 else 0 }
        if (_tmp == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmp.toLong())
        }
        statement.bindLong(7, entity.updatedAt)
      }
    }
  }

  public override suspend fun upsertDef(def: PropertyDefEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfPropertyDefEntity.insert(_connection, def)
  }

  public override suspend fun upsertValue(`value`: PropertyValueEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfPropertyValueEntity.insert(_connection, value)
  }

  public override fun defs(): Flow<List<PropertyDefEntity>> {
    val _sql: String = "SELECT * FROM property_def WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE"
    return createFlow(__db, false, arrayOf("property_def")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfKind: Int = getColumnIndexOrThrow(_stmt, "kind")
        val _columnIndexOfConfig: Int = getColumnIndexOrThrow(_stmt, "config")
        val _columnIndexOfIsBuiltIn: Int = getColumnIndexOrThrow(_stmt, "is_built_in")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<PropertyDefEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PropertyDefEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpKind: String
          _tmpKind = _stmt.getText(_columnIndexOfKind)
          val _tmpConfig: String?
          if (_stmt.isNull(_columnIndexOfConfig)) {
            _tmpConfig = null
          } else {
            _tmpConfig = _stmt.getText(_columnIndexOfConfig)
          }
          val _tmpIsBuiltIn: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsBuiltIn).toInt()
          _tmpIsBuiltIn = _tmp != 0
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
          _item = PropertyDefEntity(_tmpId,_tmpName,_tmpKind,_tmpConfig,_tmpIsBuiltIn,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun defsOnce(): List<PropertyDefEntity> {
    val _sql: String = "SELECT * FROM property_def WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfKind: Int = getColumnIndexOrThrow(_stmt, "kind")
        val _columnIndexOfConfig: Int = getColumnIndexOrThrow(_stmt, "config")
        val _columnIndexOfIsBuiltIn: Int = getColumnIndexOrThrow(_stmt, "is_built_in")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<PropertyDefEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PropertyDefEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpKind: String
          _tmpKind = _stmt.getText(_columnIndexOfKind)
          val _tmpConfig: String?
          if (_stmt.isNull(_columnIndexOfConfig)) {
            _tmpConfig = null
          } else {
            _tmpConfig = _stmt.getText(_columnIndexOfConfig)
          }
          val _tmpIsBuiltIn: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsBuiltIn).toInt()
          _tmpIsBuiltIn = _tmp != 0
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
          _item = PropertyDefEntity(_tmpId,_tmpName,_tmpKind,_tmpConfig,_tmpIsBuiltIn,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun builtInDefs(): Flow<List<PropertyDefEntity>> {
    val _sql: String = "SELECT * FROM property_def WHERE deleted_at IS NULL AND is_built_in = 1 ORDER BY name COLLATE NOCASE"
    return createFlow(__db, false, arrayOf("property_def")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfKind: Int = getColumnIndexOrThrow(_stmt, "kind")
        val _columnIndexOfConfig: Int = getColumnIndexOrThrow(_stmt, "config")
        val _columnIndexOfIsBuiltIn: Int = getColumnIndexOrThrow(_stmt, "is_built_in")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<PropertyDefEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PropertyDefEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpKind: String
          _tmpKind = _stmt.getText(_columnIndexOfKind)
          val _tmpConfig: String?
          if (_stmt.isNull(_columnIndexOfConfig)) {
            _tmpConfig = null
          } else {
            _tmpConfig = _stmt.getText(_columnIndexOfConfig)
          }
          val _tmpIsBuiltIn: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsBuiltIn).toInt()
          _tmpIsBuiltIn = _tmp != 0
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
          _item = PropertyDefEntity(_tmpId,_tmpName,_tmpKind,_tmpConfig,_tmpIsBuiltIn,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun builtInDefsOnce(): List<PropertyDefEntity> {
    val _sql: String = "SELECT * FROM property_def WHERE deleted_at IS NULL AND is_built_in = 1 ORDER BY name COLLATE NOCASE"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfKind: Int = getColumnIndexOrThrow(_stmt, "kind")
        val _columnIndexOfConfig: Int = getColumnIndexOrThrow(_stmt, "config")
        val _columnIndexOfIsBuiltIn: Int = getColumnIndexOrThrow(_stmt, "is_built_in")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: MutableList<PropertyDefEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PropertyDefEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpKind: String
          _tmpKind = _stmt.getText(_columnIndexOfKind)
          val _tmpConfig: String?
          if (_stmt.isNull(_columnIndexOfConfig)) {
            _tmpConfig = null
          } else {
            _tmpConfig = _stmt.getText(_columnIndexOfConfig)
          }
          val _tmpIsBuiltIn: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsBuiltIn).toInt()
          _tmpIsBuiltIn = _tmp != 0
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
          _item = PropertyDefEntity(_tmpId,_tmpName,_tmpKind,_tmpConfig,_tmpIsBuiltIn,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun defById(id: String): PropertyDefEntity? {
    val _sql: String = "SELECT * FROM property_def WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfKind: Int = getColumnIndexOrThrow(_stmt, "kind")
        val _columnIndexOfConfig: Int = getColumnIndexOrThrow(_stmt, "config")
        val _columnIndexOfIsBuiltIn: Int = getColumnIndexOrThrow(_stmt, "is_built_in")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfDeletedAt: Int = getColumnIndexOrThrow(_stmt, "deleted_at")
        val _result: PropertyDefEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpKind: String
          _tmpKind = _stmt.getText(_columnIndexOfKind)
          val _tmpConfig: String?
          if (_stmt.isNull(_columnIndexOfConfig)) {
            _tmpConfig = null
          } else {
            _tmpConfig = _stmt.getText(_columnIndexOfConfig)
          }
          val _tmpIsBuiltIn: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsBuiltIn).toInt()
          _tmpIsBuiltIn = _tmp != 0
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
          _result = PropertyDefEntity(_tmpId,_tmpName,_tmpKind,_tmpConfig,_tmpIsBuiltIn,_tmpCreatedAt,_tmpUpdatedAt,_tmpDeletedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun valuesForNode(nodeId: String): Flow<List<PropertyValueEntity>> {
    val _sql: String = "SELECT * FROM property_value WHERE node_id = ?"
    return createFlow(__db, false, arrayOf("property_value")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nodeId)
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfDefId: Int = getColumnIndexOrThrow(_stmt, "def_id")
        val _columnIndexOfVText: Int = getColumnIndexOrThrow(_stmt, "v_text")
        val _columnIndexOfVNumber: Int = getColumnIndexOrThrow(_stmt, "v_number")
        val _columnIndexOfVDate: Int = getColumnIndexOrThrow(_stmt, "v_date")
        val _columnIndexOfVBool: Int = getColumnIndexOrThrow(_stmt, "v_bool")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<PropertyValueEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PropertyValueEntity
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpDefId: String
          _tmpDefId = _stmt.getText(_columnIndexOfDefId)
          val _tmpVText: String?
          if (_stmt.isNull(_columnIndexOfVText)) {
            _tmpVText = null
          } else {
            _tmpVText = _stmt.getText(_columnIndexOfVText)
          }
          val _tmpVNumber: Double?
          if (_stmt.isNull(_columnIndexOfVNumber)) {
            _tmpVNumber = null
          } else {
            _tmpVNumber = _stmt.getDouble(_columnIndexOfVNumber)
          }
          val _tmpVDate: Long?
          if (_stmt.isNull(_columnIndexOfVDate)) {
            _tmpVDate = null
          } else {
            _tmpVDate = _stmt.getLong(_columnIndexOfVDate)
          }
          val _tmpVBool: Boolean?
          val _tmp: Int?
          if (_stmt.isNull(_columnIndexOfVBool)) {
            _tmp = null
          } else {
            _tmp = _stmt.getLong(_columnIndexOfVBool).toInt()
          }
          _tmpVBool = _tmp?.let { it != 0 }
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = PropertyValueEntity(_tmpNodeId,_tmpDefId,_tmpVText,_tmpVNumber,_tmpVDate,_tmpVBool,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun valuesUnder(parentId: String): Flow<List<PropertyValueEntity>> {
    val _sql: String = """
        |
        |        SELECT pv.* FROM property_value pv
        |         WHERE pv.node_id IN (SELECT id FROM node WHERE parent_id = ? AND deleted_at IS NULL)
        |        
        """.trimMargin()
    return createFlow(__db, false, arrayOf("property_value", "node")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentId)
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfDefId: Int = getColumnIndexOrThrow(_stmt, "def_id")
        val _columnIndexOfVText: Int = getColumnIndexOrThrow(_stmt, "v_text")
        val _columnIndexOfVNumber: Int = getColumnIndexOrThrow(_stmt, "v_number")
        val _columnIndexOfVDate: Int = getColumnIndexOrThrow(_stmt, "v_date")
        val _columnIndexOfVBool: Int = getColumnIndexOrThrow(_stmt, "v_bool")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<PropertyValueEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PropertyValueEntity
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpDefId: String
          _tmpDefId = _stmt.getText(_columnIndexOfDefId)
          val _tmpVText: String?
          if (_stmt.isNull(_columnIndexOfVText)) {
            _tmpVText = null
          } else {
            _tmpVText = _stmt.getText(_columnIndexOfVText)
          }
          val _tmpVNumber: Double?
          if (_stmt.isNull(_columnIndexOfVNumber)) {
            _tmpVNumber = null
          } else {
            _tmpVNumber = _stmt.getDouble(_columnIndexOfVNumber)
          }
          val _tmpVDate: Long?
          if (_stmt.isNull(_columnIndexOfVDate)) {
            _tmpVDate = null
          } else {
            _tmpVDate = _stmt.getLong(_columnIndexOfVDate)
          }
          val _tmpVBool: Boolean?
          val _tmp: Int?
          if (_stmt.isNull(_columnIndexOfVBool)) {
            _tmp = null
          } else {
            _tmp = _stmt.getLong(_columnIndexOfVBool).toInt()
          }
          _tmpVBool = _tmp?.let { it != 0 }
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = PropertyValueEntity(_tmpNodeId,_tmpDefId,_tmpVText,_tmpVNumber,_tmpVDate,_tmpVBool,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun allValues(): Flow<List<PropertyValueEntity>> {
    val _sql: String = "SELECT * FROM property_value"
    return createFlow(__db, false, arrayOf("property_value")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfDefId: Int = getColumnIndexOrThrow(_stmt, "def_id")
        val _columnIndexOfVText: Int = getColumnIndexOrThrow(_stmt, "v_text")
        val _columnIndexOfVNumber: Int = getColumnIndexOrThrow(_stmt, "v_number")
        val _columnIndexOfVDate: Int = getColumnIndexOrThrow(_stmt, "v_date")
        val _columnIndexOfVBool: Int = getColumnIndexOrThrow(_stmt, "v_bool")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<PropertyValueEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PropertyValueEntity
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpDefId: String
          _tmpDefId = _stmt.getText(_columnIndexOfDefId)
          val _tmpVText: String?
          if (_stmt.isNull(_columnIndexOfVText)) {
            _tmpVText = null
          } else {
            _tmpVText = _stmt.getText(_columnIndexOfVText)
          }
          val _tmpVNumber: Double?
          if (_stmt.isNull(_columnIndexOfVNumber)) {
            _tmpVNumber = null
          } else {
            _tmpVNumber = _stmt.getDouble(_columnIndexOfVNumber)
          }
          val _tmpVDate: Long?
          if (_stmt.isNull(_columnIndexOfVDate)) {
            _tmpVDate = null
          } else {
            _tmpVDate = _stmt.getLong(_columnIndexOfVDate)
          }
          val _tmpVBool: Boolean?
          val _tmp: Int?
          if (_stmt.isNull(_columnIndexOfVBool)) {
            _tmp = null
          } else {
            _tmp = _stmt.getLong(_columnIndexOfVBool).toInt()
          }
          _tmpVBool = _tmp?.let { it != 0 }
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = PropertyValueEntity(_tmpNodeId,_tmpDefId,_tmpVText,_tmpVNumber,_tmpVDate,_tmpVBool,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteValue(nodeId: String, defId: String) {
    val _sql: String = "DELETE FROM property_value WHERE node_id = ? AND def_id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nodeId)
        _argIndex = 2
        _stmt.bindText(_argIndex, defId)
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
