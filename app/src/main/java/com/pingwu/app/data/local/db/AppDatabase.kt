package com.pingwu.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pingwu.app.data.local.dao.DoseGroupDao
import com.pingwu.app.data.local.dao.DoseItemDao
import com.pingwu.app.data.local.dao.DrugCacheDao
import com.pingwu.app.data.local.dao.DrugDao
import com.pingwu.app.data.local.dao.EvidenceDao
import com.pingwu.app.data.local.dao.PillTemplateDao
import com.pingwu.app.data.local.dao.ScheduleDao
import com.pingwu.app.data.local.dao.UserProfileDao
import com.pingwu.app.data.local.entity.DoseGroupEntity
import com.pingwu.app.data.local.entity.DoseItemEntity
import com.pingwu.app.data.local.entity.DrugCacheEntity
import com.pingwu.app.data.local.entity.DrugEntity
import com.pingwu.app.data.local.entity.EvidenceEntity
import com.pingwu.app.data.local.entity.PillTemplateEntity
import com.pingwu.app.data.local.entity.ScheduleEntity
import com.pingwu.app.data.local.entity.UserProfileEntity

@Database(
    entities = [
        UserProfileEntity::class,
        DrugEntity::class,
        PillTemplateEntity::class,
        ScheduleEntity::class,
        DoseGroupEntity::class,
        DoseItemEntity::class,
        EvidenceEntity::class,
        DrugCacheEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun drugDao(): DrugDao
    abstract fun pillTemplateDao(): PillTemplateDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun doseGroupDao(): DoseGroupDao
    abstract fun doseItemDao(): DoseItemDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun drugCacheDao(): DrugCacheDao

    companion object {
        private const val DB_NAME = "pingwu.db"

        /** v2：分药拍摄的多张证据照片（docs/18 P2）。老库只有单张 photoPath，升级时补一列即可。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE evidence ADD COLUMN photoPathsJson TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // v1 只有默认档案；字段保留给后续多档案扩展
                        db.execSQL(
                            "INSERT OR IGNORE INTO user_profile (id, displayName, birthYear, createdAt) " +
                                "VALUES (1, '我', NULL, " + System.currentTimeMillis() + ")"
                        )
                    }
                })
                .build()
    }
}
