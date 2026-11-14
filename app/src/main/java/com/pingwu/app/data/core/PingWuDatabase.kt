package com.pingwu.app.data.core

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 用户数据只在手机里。
 *
 * 版本 3（0.13.0）加了两样东西：
 * - pw_subject：一件物品「认得的样子」，一件东西可以有好几块；
 * - pw_occurrence.doneSubjectIds：这一次已经认到哪几块（分批拍时记进度）。
 * 这里写了 2→3 的真迁移，老用户的物品和凭物都留着；下面的破坏式迁移只是兜底。
 */
@Database(
    entities = [
        ItemEntity::class,
        SubjectEntity::class,
        ReminderEntity::class,
        OccurrenceEntity::class,
        EvidenceEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class PingWuDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun subjectDao(): SubjectDao
    abstract fun reminderDao(): ReminderDao
    abstract fun occurrenceDao(): OccurrenceDao
    abstract fun evidenceDao(): EvidenceDao

    companion object {
        /** 与旧工程的 AppDatabase 分开，避免两个库抢同一个文件名。 */
        private const val DB_NAME = "pingwu_core.db"

        /** 2→3：加「认得的样子」表 + 待办的已认清单；把旧封面迁成第一条。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pw_subject` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`itemId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`coverPhotoKey` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`itemId`) REFERENCES `pw_item`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pw_subject_itemId` ON `pw_subject` (`itemId`)",
                )
                db.execSQL(
                    "ALTER TABLE `pw_occurrence` ADD COLUMN `doneSubjectIds` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "INSERT INTO `pw_subject` (`itemId`,`name`,`coverPhotoKey`,`sortOrder`,`createdAt`) " +
                        "SELECT `id`, `name`, `coverPhotoKey`, 0, `createdAt` FROM `pw_item` " +
                        "WHERE `templateReady` = 1 AND `coverPhotoKey` != ''",
                )
            }
        }

        @Volatile
        private var instance: PingWuDatabase? = null

        fun get(context: Context): PingWuDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PingWuDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
