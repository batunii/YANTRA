package ie.napkin.supertasks.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class SmartListDao_Impl(
  __db: RoomDatabase,
) : SmartListDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfSmartListDefEntity: EntityInsertAdapter<SmartListDefEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfSmartListDefEntity = object : EntityInsertAdapter<SmartListDefEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `smart_list_def` (`node_id`,`scope_root_id`,`filter_json`,`sort_json`,`home_parent_id`,`apply_on_create_json`) VALUES (?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SmartListDefEntity) {
        statement.bindText(1, entity.nodeId)
        val _tmpScopeRootId: String? = entity.scopeRootId
        if (_tmpScopeRootId == null) {
          statement.bindNull(2)
        } else {
          statement.bindText(2, _tmpScopeRootId)
        }
        statement.bindText(3, entity.filterJson)
        val _tmpSortJson: String? = entity.sortJson
        if (_tmpSortJson == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpSortJson)
        }
        val _tmpHomeParentId: String? = entity.homeParentId
        if (_tmpHomeParentId == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpHomeParentId)
        }
        val _tmpApplyOnCreateJson: String? = entity.applyOnCreateJson
        if (_tmpApplyOnCreateJson == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpApplyOnCreateJson)
        }
      }
    }
  }

  public override suspend fun upsert(def: SmartListDefEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfSmartListDefEntity.insert(_connection, def)
  }

  public override fun observe(nodeId: String): Flow<SmartListDefEntity?> {
    val _sql: String = "SELECT * FROM smart_list_def WHERE node_id = ?"
    return createFlow(__db, false, arrayOf("smart_list_def")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nodeId)
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfScopeRootId: Int = getColumnIndexOrThrow(_stmt, "scope_root_id")
        val _columnIndexOfFilterJson: Int = getColumnIndexOrThrow(_stmt, "filter_json")
        val _columnIndexOfSortJson: Int = getColumnIndexOrThrow(_stmt, "sort_json")
        val _columnIndexOfHomeParentId: Int = getColumnIndexOrThrow(_stmt, "home_parent_id")
        val _columnIndexOfApplyOnCreateJson: Int = getColumnIndexOrThrow(_stmt, "apply_on_create_json")
        val _result: SmartListDefEntity?
        if (_stmt.step()) {
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpScopeRootId: String?
          if (_stmt.isNull(_columnIndexOfScopeRootId)) {
            _tmpScopeRootId = null
          } else {
            _tmpScopeRootId = _stmt.getText(_columnIndexOfScopeRootId)
          }
          val _tmpFilterJson: String
          _tmpFilterJson = _stmt.getText(_columnIndexOfFilterJson)
          val _tmpSortJson: String?
          if (_stmt.isNull(_columnIndexOfSortJson)) {
            _tmpSortJson = null
          } else {
            _tmpSortJson = _stmt.getText(_columnIndexOfSortJson)
          }
          val _tmpHomeParentId: String?
          if (_stmt.isNull(_columnIndexOfHomeParentId)) {
            _tmpHomeParentId = null
          } else {
            _tmpHomeParentId = _stmt.getText(_columnIndexOfHomeParentId)
          }
          val _tmpApplyOnCreateJson: String?
          if (_stmt.isNull(_columnIndexOfApplyOnCreateJson)) {
            _tmpApplyOnCreateJson = null
          } else {
            _tmpApplyOnCreateJson = _stmt.getText(_columnIndexOfApplyOnCreateJson)
          }
          _result = SmartListDefEntity(_tmpNodeId,_tmpScopeRootId,_tmpFilterJson,_tmpSortJson,_tmpHomeParentId,_tmpApplyOnCreateJson)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun byId(nodeId: String): SmartListDefEntity? {
    val _sql: String = "SELECT * FROM smart_list_def WHERE node_id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nodeId)
        val _columnIndexOfNodeId: Int = getColumnIndexOrThrow(_stmt, "node_id")
        val _columnIndexOfScopeRootId: Int = getColumnIndexOrThrow(_stmt, "scope_root_id")
        val _columnIndexOfFilterJson: Int = getColumnIndexOrThrow(_stmt, "filter_json")
        val _columnIndexOfSortJson: Int = getColumnIndexOrThrow(_stmt, "sort_json")
        val _columnIndexOfHomeParentId: Int = getColumnIndexOrThrow(_stmt, "home_parent_id")
        val _columnIndexOfApplyOnCreateJson: Int = getColumnIndexOrThrow(_stmt, "apply_on_create_json")
        val _result: SmartListDefEntity?
        if (_stmt.step()) {
          val _tmpNodeId: String
          _tmpNodeId = _stmt.getText(_columnIndexOfNodeId)
          val _tmpScopeRootId: String?
          if (_stmt.isNull(_columnIndexOfScopeRootId)) {
            _tmpScopeRootId = null
          } else {
            _tmpScopeRootId = _stmt.getText(_columnIndexOfScopeRootId)
          }
          val _tmpFilterJson: String
          _tmpFilterJson = _stmt.getText(_columnIndexOfFilterJson)
          val _tmpSortJson: String?
          if (_stmt.isNull(_columnIndexOfSortJson)) {
            _tmpSortJson = null
          } else {
            _tmpSortJson = _stmt.getText(_columnIndexOfSortJson)
          }
          val _tmpHomeParentId: String?
          if (_stmt.isNull(_columnIndexOfHomeParentId)) {
            _tmpHomeParentId = null
          } else {
            _tmpHomeParentId = _stmt.getText(_columnIndexOfHomeParentId)
          }
          val _tmpApplyOnCreateJson: String?
          if (_stmt.isNull(_columnIndexOfApplyOnCreateJson)) {
            _tmpApplyOnCreateJson = null
          } else {
            _tmpApplyOnCreateJson = _stmt.getText(_columnIndexOfApplyOnCreateJson)
          }
          _result = SmartListDefEntity(_tmpNodeId,_tmpScopeRootId,_tmpFilterJson,_tmpSortJson,_tmpHomeParentId,_tmpApplyOnCreateJson)
        } else {
          _result = null
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
