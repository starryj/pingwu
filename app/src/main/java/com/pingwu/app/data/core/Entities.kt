package com.pingwu.app.data.core

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 真实数据模型（M2 起，M9 通用化）。
 *
 * 物品 = 一件看得见的东西（钥匙、钱包、证件、快递……都算）。
 * 提醒 = 这件物品什么时候该拍一次（每天 / 每周 / 每月 / 只一次）。
 * 待办 = 某一天某个时刻的一次「该拍」。
 * 凭物 = 真正拍下来、并核验通过的照片证据。
 *
 * 时间约定：scheduledAt / capturedAt 用 epoch ms；localDate 用 yyyy-MM-dd；
 * 枚举一律存 String，方便以后迁移。
 */

@Entity(tableName = "pw_item", indices = [Index("sortOrder")])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 备注：放在哪儿、什么牌子……都可以，可为空 */
    val note: String = "",
    /** 每次要拍几件 */
    val countPerDose: Int = 1,
    /** MEDICINE / KEY / DOC / OTHER */
    val category: String = "OTHER",
    val templateReady: Boolean = false,
    /** 封面照片 key（pw_photo_1…）或本机文件绝对路径 */
    val coverPhotoKey: String = "pw_photo_1",
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 一件物品「认得的样子」：一张照片里裁出来的一块。
 *
 * 一件东西可以登记好几块（相机、香水瓶、水杯……），到点时全部认到才算完成；
 * 可以先拍一部分、记下进度，剩下的晚点再拍。
 */
@Entity(
    tableName = "pw_subject",
    indices = [Index("itemId")],
    foreignKeys = [ForeignKey(
        entity = ItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    /** 显示名，可为空（空则显示「第 N 件」） */
    val name: String = "",
    /** 这一块的照片绝对路径 */
    val coverPhotoKey: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "pw_reminder",
    indices = [Index("itemId")],
    foreignKeys = [ForeignKey(
        entity = ItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    /** DAILY / WEEKLY / MONTHLY / ONCE */
    val rule: String = "DAILY",
    /** WEEKLY 存星期（1=周一…7=周日）；MONTHLY 存几号；用逗号分隔 */
    val daysCsv: String = "",
    /** 用逗号分隔的 HH:mm，如 "08:00,21:00" */
    val timesCsv: String = "08:00",
    /** ONCE 用的本地日期 yyyy-MM-dd；其它规则为空 */
    val onceDate: String = "",
    /** 验证窗口（分钟），「我的」里可改 */
    val windowMinutes: Int = 30,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "pw_occurrence",
    indices = [Index("itemId"), Index(value = ["itemId", "localDate", "timeLabel"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = ItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class OccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    /** yyyy-MM-dd（本地日期） */
    val localDate: String,
    /** HH:mm */
    val timeLabel: String,
    val scheduledAt: Long,
    /** PENDING / VERIFIED / MISSED / SKIPPED */
    val state: String = "PENDING",
    val verifiedAt: Long? = null,
    val isLate: Boolean = false,
    val windowMinutes: Int = 30,
    /** 这次已经认到的「认得的样子」id，逗号分隔；全部认到才算完成 */
    val doneSubjectIds: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "pw_evidence",
    indices = [Index("occurrenceId"), Index("itemId")],
    foreignKeys = [ForeignKey(
        entity = OccurrenceEntity::class,
        parentColumns = ["id"],
        childColumns = ["occurrenceId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class EvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceId: Long,
    val itemId: Long,
    /** 本机相册文件绝对路径，或工程内资源名 */
    val photoKey: String,
    val capturedAt: Long = System.currentTimeMillis(),
    /** LOCAL / ONLINE_API（在线识别只是辅助，不改「照片存本机」的事实） */
    val source: String = "LOCAL",
)

/**
 * 新增 / 编辑物品时，界面交给仓储层的一份草稿。
 * 提醒先按「一件物品一条提醒」处理，够用且界面简单。
 */
data class ItemDraft(
    val name: String,
    val note: String = "",
    val count: Int = 1,
    val category: String = "OTHER",
    val rule: String = ReminderRule.DAILY,
    val daysCsv: String = "",
    val times: List<String> = listOf("08:00"),
    val onceDate: String = "",
    val windowMinutes: Int = 30,
)
