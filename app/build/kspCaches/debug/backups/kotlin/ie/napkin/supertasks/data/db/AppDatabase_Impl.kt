package ie.napkin.supertasks.`data`.db

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AppDatabase_Impl : AppDatabase() {
  private val _nodeDao: Lazy<NodeDao> = lazy {
    NodeDao_Impl(this)
  }

  private val _propertyDao: Lazy<PropertyDao> = lazy {
    PropertyDao_Impl(this)
  }

  private val _pomodoroDao: Lazy<PomodoroDao> = lazy {
    PomodoroDao_Impl(this)
  }

  private val _smartListDao: Lazy<SmartListDao> = lazy {
    SmartListDao_Impl(this)
  }

  private val _inkDao: Lazy<InkDao> = lazy {
    InkDao_Impl(this)
  }

  private val _labelDao: Lazy<LabelDao> = lazy {
    LabelDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(2, "5b6e2363cc86353cc3e193296dbc8017", "024eb68220f3dfab2da4cb2c89d5f735") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `node` (`id` TEXT NOT NULL, `parent_id` TEXT, `type` TEXT NOT NULL, `title` TEXT, `rank` TEXT NOT NULL, `done` INTEGER NOT NULL, `collapsed` INTEGER NOT NULL, `canvas_x` REAL, `canvas_y` REAL, `canvas_w` REAL, `canvas_h` REAL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`parent_id`) REFERENCES `node`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `idx_node_parent` ON `node` (`parent_id`, `rank`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `property_def` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `config` TEXT, `is_built_in` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `property_value` (`node_id` TEXT NOT NULL, `def_id` TEXT NOT NULL, `v_text` TEXT, `v_number` REAL, `v_date` INTEGER, `v_bool` INTEGER, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`node_id`, `def_id`), FOREIGN KEY(`node_id`) REFERENCES `node`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`def_id`) REFERENCES `property_def`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `idx_pv_def` ON `property_value` (`def_id`, `v_text`, `v_number`, `v_date`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `pomodoro_session` (`id` TEXT NOT NULL, `node_id` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `ended_at` INTEGER, `planned_secs` INTEGER NOT NULL, `actual_secs` INTEGER, `completed` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`node_id`) REFERENCES `node`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `idx_pomo_node` ON `pomodoro_session` (`node_id`, `started_at`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `smart_list_def` (`node_id` TEXT NOT NULL, `scope_root_id` TEXT, `filter_json` TEXT NOT NULL, `sort_json` TEXT, `home_parent_id` TEXT, `apply_on_create_json` TEXT, PRIMARY KEY(`node_id`), FOREIGN KEY(`node_id`) REFERENCES `node`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `ink_stroke` (`id` TEXT NOT NULL, `node_id` TEXT NOT NULL, `data` BLOB NOT NULL, `bbox_x` REAL, `bbox_y` REAL, `bbox_w` REAL, `bbox_h` REAL, `rank` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`node_id`) REFERENCES `node`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `idx_stroke_node` ON `ink_stroke` (`node_id`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `label` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_label_name` ON `label` (`name`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `node_label` (`node_id` TEXT NOT NULL, `label_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`node_id`, `label_id`), FOREIGN KEY(`node_id`) REFERENCES `node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`label_id`) REFERENCES `label`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `idx_node_label_label` ON `node_label` (`label_id`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '5b6e2363cc86353cc3e193296dbc8017')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `node`")
        connection.execSQL("DROP TABLE IF EXISTS `property_def`")
        connection.execSQL("DROP TABLE IF EXISTS `property_value`")
        connection.execSQL("DROP TABLE IF EXISTS `pomodoro_session`")
        connection.execSQL("DROP TABLE IF EXISTS `smart_list_def`")
        connection.execSQL("DROP TABLE IF EXISTS `ink_stroke`")
        connection.execSQL("DROP TABLE IF EXISTS `label`")
        connection.execSQL("DROP TABLE IF EXISTS `node_label`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA foreign_keys = ON")
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
        val _columnsNode: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsNode.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("parent_id", TableInfo.Column("parent_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("type", TableInfo.Column("type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("title", TableInfo.Column("title", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("rank", TableInfo.Column("rank", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("done", TableInfo.Column("done", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("collapsed", TableInfo.Column("collapsed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("canvas_x", TableInfo.Column("canvas_x", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("canvas_y", TableInfo.Column("canvas_y", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("canvas_w", TableInfo.Column("canvas_w", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("canvas_h", TableInfo.Column("canvas_h", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNode.put("deleted_at", TableInfo.Column("deleted_at", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysNode: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysNode.add(TableInfo.ForeignKey("node", "NO ACTION", "NO ACTION", listOf("parent_id"), listOf("id")))
        val _indicesNode: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesNode.add(TableInfo.Index("idx_node_parent", false, listOf("parent_id", "rank"), listOf("ASC", "ASC")))
        val _infoNode: TableInfo = TableInfo("node", _columnsNode, _foreignKeysNode, _indicesNode)
        val _existingNode: TableInfo = read(connection, "node")
        if (!_infoNode.equals(_existingNode)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |node(ie.napkin.supertasks.data.db.NodeEntity).
              | Expected:
              |""".trimMargin() + _infoNode + """
              |
              | Found:
              |""".trimMargin() + _existingNode)
        }
        val _columnsPropertyDef: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPropertyDef.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyDef.put("name", TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyDef.put("kind", TableInfo.Column("kind", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyDef.put("config", TableInfo.Column("config", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyDef.put("is_built_in", TableInfo.Column("is_built_in", "INTEGER", true, 0, "0", TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyDef.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyDef.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyDef.put("deleted_at", TableInfo.Column("deleted_at", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPropertyDef: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesPropertyDef: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoPropertyDef: TableInfo = TableInfo("property_def", _columnsPropertyDef, _foreignKeysPropertyDef, _indicesPropertyDef)
        val _existingPropertyDef: TableInfo = read(connection, "property_def")
        if (!_infoPropertyDef.equals(_existingPropertyDef)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |property_def(ie.napkin.supertasks.data.db.PropertyDefEntity).
              | Expected:
              |""".trimMargin() + _infoPropertyDef + """
              |
              | Found:
              |""".trimMargin() + _existingPropertyDef)
        }
        val _columnsPropertyValue: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPropertyValue.put("node_id", TableInfo.Column("node_id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyValue.put("def_id", TableInfo.Column("def_id", "TEXT", true, 2, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyValue.put("v_text", TableInfo.Column("v_text", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyValue.put("v_number", TableInfo.Column("v_number", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyValue.put("v_date", TableInfo.Column("v_date", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyValue.put("v_bool", TableInfo.Column("v_bool", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPropertyValue.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPropertyValue: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysPropertyValue.add(TableInfo.ForeignKey("node", "NO ACTION", "NO ACTION", listOf("node_id"), listOf("id")))
        _foreignKeysPropertyValue.add(TableInfo.ForeignKey("property_def", "NO ACTION", "NO ACTION", listOf("def_id"), listOf("id")))
        val _indicesPropertyValue: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesPropertyValue.add(TableInfo.Index("idx_pv_def", false, listOf("def_id", "v_text", "v_number", "v_date"), listOf("ASC", "ASC", "ASC", "ASC")))
        val _infoPropertyValue: TableInfo = TableInfo("property_value", _columnsPropertyValue, _foreignKeysPropertyValue, _indicesPropertyValue)
        val _existingPropertyValue: TableInfo = read(connection, "property_value")
        if (!_infoPropertyValue.equals(_existingPropertyValue)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |property_value(ie.napkin.supertasks.data.db.PropertyValueEntity).
              | Expected:
              |""".trimMargin() + _infoPropertyValue + """
              |
              | Found:
              |""".trimMargin() + _existingPropertyValue)
        }
        val _columnsPomodoroSession: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPomodoroSession.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPomodoroSession.put("node_id", TableInfo.Column("node_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPomodoroSession.put("started_at", TableInfo.Column("started_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPomodoroSession.put("ended_at", TableInfo.Column("ended_at", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPomodoroSession.put("planned_secs", TableInfo.Column("planned_secs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPomodoroSession.put("actual_secs", TableInfo.Column("actual_secs", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPomodoroSession.put("completed", TableInfo.Column("completed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPomodoroSession.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPomodoroSession.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPomodoroSession: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysPomodoroSession.add(TableInfo.ForeignKey("node", "NO ACTION", "NO ACTION", listOf("node_id"), listOf("id")))
        val _indicesPomodoroSession: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesPomodoroSession.add(TableInfo.Index("idx_pomo_node", false, listOf("node_id", "started_at"), listOf("ASC", "ASC")))
        val _infoPomodoroSession: TableInfo = TableInfo("pomodoro_session", _columnsPomodoroSession, _foreignKeysPomodoroSession, _indicesPomodoroSession)
        val _existingPomodoroSession: TableInfo = read(connection, "pomodoro_session")
        if (!_infoPomodoroSession.equals(_existingPomodoroSession)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |pomodoro_session(ie.napkin.supertasks.data.db.PomodoroSessionEntity).
              | Expected:
              |""".trimMargin() + _infoPomodoroSession + """
              |
              | Found:
              |""".trimMargin() + _existingPomodoroSession)
        }
        val _columnsSmartListDef: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsSmartListDef.put("node_id", TableInfo.Column("node_id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSmartListDef.put("scope_root_id", TableInfo.Column("scope_root_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSmartListDef.put("filter_json", TableInfo.Column("filter_json", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSmartListDef.put("sort_json", TableInfo.Column("sort_json", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSmartListDef.put("home_parent_id", TableInfo.Column("home_parent_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSmartListDef.put("apply_on_create_json", TableInfo.Column("apply_on_create_json", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSmartListDef: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysSmartListDef.add(TableInfo.ForeignKey("node", "NO ACTION", "NO ACTION", listOf("node_id"), listOf("id")))
        val _indicesSmartListDef: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoSmartListDef: TableInfo = TableInfo("smart_list_def", _columnsSmartListDef, _foreignKeysSmartListDef, _indicesSmartListDef)
        val _existingSmartListDef: TableInfo = read(connection, "smart_list_def")
        if (!_infoSmartListDef.equals(_existingSmartListDef)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |smart_list_def(ie.napkin.supertasks.data.db.SmartListDefEntity).
              | Expected:
              |""".trimMargin() + _infoSmartListDef + """
              |
              | Found:
              |""".trimMargin() + _existingSmartListDef)
        }
        val _columnsInkStroke: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsInkStroke.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("node_id", TableInfo.Column("node_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("data", TableInfo.Column("data", "BLOB", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("bbox_x", TableInfo.Column("bbox_x", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("bbox_y", TableInfo.Column("bbox_y", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("bbox_w", TableInfo.Column("bbox_w", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("bbox_h", TableInfo.Column("bbox_h", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("rank", TableInfo.Column("rank", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInkStroke.put("deleted_at", TableInfo.Column("deleted_at", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysInkStroke: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysInkStroke.add(TableInfo.ForeignKey("node", "NO ACTION", "NO ACTION", listOf("node_id"), listOf("id")))
        val _indicesInkStroke: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesInkStroke.add(TableInfo.Index("idx_stroke_node", false, listOf("node_id"), listOf("ASC")))
        val _infoInkStroke: TableInfo = TableInfo("ink_stroke", _columnsInkStroke, _foreignKeysInkStroke, _indicesInkStroke)
        val _existingInkStroke: TableInfo = read(connection, "ink_stroke")
        if (!_infoInkStroke.equals(_existingInkStroke)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |ink_stroke(ie.napkin.supertasks.data.db.InkStrokeEntity).
              | Expected:
              |""".trimMargin() + _infoInkStroke + """
              |
              | Found:
              |""".trimMargin() + _existingInkStroke)
        }
        val _columnsLabel: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsLabel.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsLabel.put("name", TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsLabel.put("color", TableInfo.Column("color", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsLabel.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsLabel.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysLabel: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesLabel: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesLabel.add(TableInfo.Index("idx_label_name", true, listOf("name"), listOf("ASC")))
        val _infoLabel: TableInfo = TableInfo("label", _columnsLabel, _foreignKeysLabel, _indicesLabel)
        val _existingLabel: TableInfo = read(connection, "label")
        if (!_infoLabel.equals(_existingLabel)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |label(ie.napkin.supertasks.data.db.LabelEntity).
              | Expected:
              |""".trimMargin() + _infoLabel + """
              |
              | Found:
              |""".trimMargin() + _existingLabel)
        }
        val _columnsNodeLabel: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsNodeLabel.put("node_id", TableInfo.Column("node_id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNodeLabel.put("label_id", TableInfo.Column("label_id", "TEXT", true, 2, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsNodeLabel.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysNodeLabel: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysNodeLabel.add(TableInfo.ForeignKey("node", "CASCADE", "NO ACTION", listOf("node_id"), listOf("id")))
        _foreignKeysNodeLabel.add(TableInfo.ForeignKey("label", "CASCADE", "NO ACTION", listOf("label_id"), listOf("id")))
        val _indicesNodeLabel: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesNodeLabel.add(TableInfo.Index("idx_node_label_label", false, listOf("label_id"), listOf("ASC")))
        val _infoNodeLabel: TableInfo = TableInfo("node_label", _columnsNodeLabel, _foreignKeysNodeLabel, _indicesNodeLabel)
        val _existingNodeLabel: TableInfo = read(connection, "node_label")
        if (!_infoNodeLabel.equals(_existingNodeLabel)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |node_label(ie.napkin.supertasks.data.db.NodeLabelEntity).
              | Expected:
              |""".trimMargin() + _infoNodeLabel + """
              |
              | Found:
              |""".trimMargin() + _existingNodeLabel)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "node", "property_def", "property_value", "pomodoro_session", "smart_list_def", "ink_stroke", "label", "node_label")
  }

  public override fun clearAllTables() {
    super.performClear(true, "property_value", "pomodoro_session", "smart_list_def", "node", "property_def", "ink_stroke", "label", "node_label")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(NodeDao::class, NodeDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(PropertyDao::class, PropertyDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(PomodoroDao::class, PomodoroDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(SmartListDao::class, SmartListDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(InkDao::class, InkDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(LabelDao::class, LabelDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>): List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun nodeDao(): NodeDao = _nodeDao.value

  public override fun propertyDao(): PropertyDao = _propertyDao.value

  public override fun pomodoroDao(): PomodoroDao = _pomodoroDao.value

  public override fun smartListDao(): SmartListDao = _smartListDao.value

  public override fun inkDao(): InkDao = _inkDao.value

  public override fun labelDao(): LabelDao = _labelDao.value
}
