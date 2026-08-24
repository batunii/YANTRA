package ie.napkin.supertasks.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NodeEntity::class,
        PropertyDefEntity::class,
        PropertyValueEntity::class,
        PomodoroSessionEntity::class,
        SmartListDefEntity::class,
        InkStrokeEntity::class,
        LabelEntity::class,
        NodeLabelEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun propertyDao(): PropertyDao
    abstract fun pomodoroDao(): PomodoroDao
    abstract fun smartListDao(): SmartListDao
    abstract fun inkDao(): InkDao
    abstract fun labelDao(): LabelDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "supertasks.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
