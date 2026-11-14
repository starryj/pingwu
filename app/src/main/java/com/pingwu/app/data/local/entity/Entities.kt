package com.pingwu.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 数据表定义与《02-技术方案》§3.1 一一对应。
 * 约定：所有枚举以 String 存库（便于迁移与可读性），时间统一 epoch ms（UTC），日期用 ISO yyyy-MM-dd。
 */

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Long = DEFAULT_ID,
    val displayName: String = "我",
    val birthYear: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object { const val DEFAULT_ID: Long = 1L }
}

@Entity(
    tableName = "drug",
    indices = [Index("profileId"), Index("barcode")],
    foreignKeys = [ForeignKey(
        entity = UserProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class DrugEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val spec: String? = null,
    val form: String? = null,
    val shapeHint: String? = null,
    val colorHint: String? = null,
    val presetShape: String? = null,
    val presetColor: String? = null,
    val presetColor2: String? = null,
    val profileId: Long = UserProfileEntity.DEFAULT_ID,
    val referenceMode: String = "NONE",
    val barcode: String? = null,
    val source: String = "MANUAL",
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Int = 0,
)

@Entity(
    tableName = "pill_template",
    indices = [Index("drugId")],
    foreignKeys = [ForeignKey(
        entity = DrugEntity::class,
        parentColumns = ["id"],
        childColumns = ["drugId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class PillTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val drugId: Long,
    val version: Int = 1,
    val batch: String? = null,
    /** 特征向量 JSON，见《02》§5.3；M1 先占位空对象 */
    val featuresJson: String = "{}",
    val aRefMm2: Double = 0.0,
    val tAccept: Double = 0.0,
    val tUnknown: Double = 0.0,
    /** 注册照片相对路径 JSON 数组 */
    val samplePhotosJson: String = "[]",
    val referenceMode: String = "NONE",
    val scaleRefMm: Double? = null,
    val active: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "schedule",
    indices = [Index("drugId")],
    foreignKeys = [ForeignKey(
        entity = DrugEntity::class,
        parentColumns = ["id"],
        childColumns = ["drugId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val drugId: Long,
    val countPerDose: Int = 1,
    /** JSON 数组，如 ["08:00","20:00"] */
    val timesJson: String = "[]",
    /** 位掩码，bit0=周一 … bit6=周日；0x7F=每天 */
    val daysOfWeek: Int = 0x7F,
    val windowMinutes: Int = 90,
    val snoozeIntervalMinutes: Int = 10,
    val snoozeLimit: Int = 3,
    val startDate: String? = null,
    val enabled: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "dose_group",
    indices = [Index(value = ["doseKey"], unique = true)],
)
data class DoseGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 本地日期+时刻，如 2026-11-09T20:00 */
    val doseKey: String,
    val scheduledAt: Long,
    val windowEndAt: Long,
    /** 有效窗口长度（分钟），延后时据此重算窗口 */
    val windowMinutes: Int = 90,
    val snoozeIntervalMinutes: Int = 10,
    val snoozeLimit: Int = 3,
    val state: String = "PENDING",
    val snoozeCount: Int = 0,
    val nextNotifyAt: Long? = null,
    val finalReminderAt: Long? = null,
    val verifyAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "dose_item",
    indices = [Index("doseGroupId"), Index("drugId")],
    foreignKeys = [ForeignKey(
        entity = DoseGroupEntity::class,
        parentColumns = ["id"],
        childColumns = ["doseGroupId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class DoseItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val doseGroupId: Long,
    val drugId: Long,
    val plannedCount: Int,
    val detectedCount: Int? = null,
)

@Entity(
    tableName = "evidence",
    indices = [Index("doseGroupId")],
    foreignKeys = [ForeignKey(
        entity = DoseGroupEntity::class,
        parentColumns = ["id"],
        childColumns = ["doseGroupId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class EvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val doseGroupId: Long,
    val photoPath: String? = null,
    /** 分药拍摄（docs/18 P2）时一张照片只拍一味药：这里存全部证据照片路径（JSON 数组）；单拍为 null。 */
    val photoPathsJson: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val detectJson: String = "{}",
    val verdict: String = "FAILED",
    val isLate: Int = 0,
)

@Entity(tableName = "drug_cache")
data class DrugCacheEntity(
    @PrimaryKey val barcode: String,
    val name: String? = null,
    val spec: String? = null,
    val form: String? = null,
    val source: String = "MANUAL",
    val isNegative: Int = 0,
    val fetchedAt: Long = System.currentTimeMillis(),
)
