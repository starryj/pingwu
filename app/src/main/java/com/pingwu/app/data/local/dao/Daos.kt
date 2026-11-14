package com.pingwu.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pingwu.app.data.local.entity.DoseGroupEntity
import com.pingwu.app.data.local.entity.DoseItemEntity
import com.pingwu.app.data.local.entity.DrugCacheEntity
import com.pingwu.app.data.local.entity.DrugEntity
import com.pingwu.app.data.local.entity.EvidenceEntity
import com.pingwu.app.data.local.entity.PillTemplateEntity
import com.pingwu.app.data.local.entity.ScheduleEntity
import com.pingwu.app.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun get(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)
}

@Dao
interface DrugDao {
    @Query("SELECT * FROM drug WHERE archived = 0 ORDER BY createdAt ASC")
    fun observeActive(): Flow<List<DrugEntity>>

    @Query("SELECT * FROM drug WHERE id = :id")
    suspend fun getById(id: Long): DrugEntity?

    @Query("SELECT * FROM drug WHERE archived = 0 ORDER BY createdAt ASC")
    suspend fun getActive(): List<DrugEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(drug: DrugEntity): Long

    @Update
    suspend fun update(drug: DrugEntity)

    @Query("UPDATE drug SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("UPDATE drug SET archived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)

    @Query("SELECT COUNT(*) FROM drug WHERE archived = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM drug ORDER BY createdAt ASC")
    fun observeAllIncludingArchived(): Flow<List<DrugEntity>>
}

@Dao
interface PillTemplateDao {
    @Query("SELECT * FROM pill_template WHERE drugId = :drugId AND active = 1 ORDER BY version DESC")
    fun observeActiveForDrug(drugId: Long): Flow<List<PillTemplateEntity>>

    @Query("SELECT * FROM pill_template WHERE drugId = :drugId AND active = 1 ORDER BY version DESC LIMIT 1")
    suspend fun getActive(drugId: Long): PillTemplateEntity?

    @Query("SELECT * FROM pill_template WHERE drugId = :drugId ORDER BY version DESC")
    suspend fun getAllForDrug(drugId: Long): List<PillTemplateEntity>

    @Query("SELECT MAX(version) FROM pill_template WHERE drugId = :drugId")
    suspend fun maxVersion(drugId: Long): Int?

    @Query("UPDATE pill_template SET active = 0 WHERE drugId = :drugId")
    suspend fun deactivateAll(drugId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: PillTemplateEntity): Long

    @Query("SELECT COUNT(*) FROM pill_template WHERE drugId = :drugId AND active = 1")
    suspend fun countActive(drugId: Long): Int
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ScheduleEntity>>

    /** 只取「启用且药品未归档」的计划：归档药即使计划没停，也不能再生成剂量。 */
    @Query(
        "SELECT s.* FROM schedule s INNER JOIN drug d ON d.id = s.drugId " +
            "WHERE s.enabled = 1 AND d.archived = 0"
    )
    suspend fun getEnabled(): List<ScheduleEntity>

    @Query("SELECT * FROM schedule WHERE drugId = :drugId")
    suspend fun getForDrug(drugId: Long): List<ScheduleEntity>

    @Query("SELECT * FROM schedule WHERE id = :id")
    suspend fun getById(id: Long): ScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schedule: ScheduleEntity): Long

    @Query("UPDATE schedule SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Int)

    @Query("DELETE FROM schedule WHERE id = :id")
    suspend fun delete(id: Long)

    /** 药品归档时停用它的全部计划（历史剂量组保留，只影响未来）。 */
    @Query("UPDATE schedule SET enabled = 0 WHERE drugId = :drugId")
    suspend fun disableForDrug(drugId: Long)
}

@Dao
interface DoseGroupDao {
    @Query("SELECT * FROM dose_group WHERE doseKey = :doseKey")
    suspend fun getByKey(doseKey: String): DoseGroupEntity?

    @Query("SELECT * FROM dose_group WHERE id = :id")
    suspend fun getById(id: Long): DoseGroupEntity?

    @Query("SELECT * FROM dose_group ORDER BY scheduledAt ASC")
    fun observeAll(): Flow<List<DoseGroupEntity>>

    @Query("SELECT * FROM dose_group WHERE scheduledAt >= :from AND scheduledAt < :to ORDER BY scheduledAt ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<DoseGroupEntity>>

    @Query("SELECT * FROM dose_group WHERE scheduledAt >= :from AND scheduledAt < :to ORDER BY scheduledAt ASC")
    suspend fun getBetween(from: Long, to: Long): List<DoseGroupEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(group: DoseGroupEntity): Long

    @Update
    suspend fun update(group: DoseGroupEntity)

    // 计划变更（新增/停用）后重建未来未到点的剂量组：先删再按当前启用计划展开，避免旧组残留或同刻多药漏合并。
    @Query("DELETE FROM dose_group WHERE state = 'PENDING' AND scheduledAt > :after")
    suspend fun deleteFuturePending(after: Long)

    @Query("DELETE FROM dose_group WHERE scheduledAt < :before AND state IN ('PENDING','MISSED','SKIPPED')")
    suspend fun deleteOldFinished(before: Long)

    // 延后期间（SNOOZED 且还没到下一次提醒）不算阻塞：否则用户点了「延后」仍会被立刻弹回提醒页，延后等于失效。
    // 到下一次提醒时间（nextNotifyAt）后重新阻塞，或提醒触发后状态变 REMINDING 时阻塞。
    @Query(
        "SELECT * FROM dose_group WHERE state = 'REMINDING' " +
            "OR (state = 'SNOOZED' AND nextNotifyAt IS NOT NULL AND nextNotifyAt <= :now) " +
            "ORDER BY scheduledAt ASC LIMIT 1"
    )
    suspend fun getBlockingDose(now: Long): DoseGroupEntity?

    @Query("SELECT * FROM dose_group WHERE state IN ('PENDING','REMINDING','SNOOZED') AND windowEndAt <= :now")
    suspend fun getExpired(now: Long): List<DoseGroupEntity>

    @Query("SELECT * FROM dose_group WHERE state IN ('PENDING','REMINDING','SNOOZED') AND scheduledAt <= :now")
    suspend fun getStarted(now: Long): List<DoseGroupEntity>

    @Query("SELECT * FROM dose_group WHERE scheduledAt >= :from AND scheduledAt < :to ORDER BY scheduledAt ASC")
    suspend fun getUpcoming(from: Long, to: Long): List<DoseGroupEntity>

    @Query("SELECT * FROM dose_group WHERE state = 'MISSED' AND finalReminderAt IS NOT NULL AND finalReminderAt > :now ORDER BY finalReminderAt ASC")
    suspend fun getFinalReminders(now: Long): List<DoseGroupEntity>

    @Query("DELETE FROM dose_group WHERE scheduledAt < :before AND state IN ('MISSED','SKIPPED')")
    suspend fun deleteOlderFinished(before: Long)

    // 历史页只看已经到点的记录：展开范围有 14 天，不能把未来的 PENDING 也列出来。
    @Query("SELECT * FROM dose_group WHERE scheduledAt >= :since AND scheduledAt <= :until ORDER BY scheduledAt DESC LIMIT :limit")
    fun observeRecent(since: Long, until: Long, limit: Int = 200): Flow<List<DoseGroupEntity>>
}

/** 剂量明细 + 药名（只读投影，用于验证页/提醒页） */
data class DoseItemWithName(
    @androidx.room.Embedded val item: DoseItemEntity,
    val drugName: String,
)

@Dao
interface DoseItemDao {
    @Query(
        "SELECT di.*, d.name AS drugName FROM dose_item di " +
            "JOIN drug d ON d.id = di.drugId WHERE di.doseGroupId = :doseGroupId ORDER BY di.drugId ASC"
    )
    suspend fun getWithDrugName(doseGroupId: Long): List<DoseItemWithName>

    @Query("SELECT * FROM dose_item WHERE doseGroupId = :doseGroupId ORDER BY drugId ASC")
    fun observeForGroup(doseGroupId: Long): Flow<List<DoseItemEntity>>

    @Query("SELECT * FROM dose_item WHERE doseGroupId = :doseGroupId ORDER BY drugId ASC")
    suspend fun getForGroup(doseGroupId: Long): List<DoseItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DoseItemEntity>)

    @Query("SELECT * FROM dose_item WHERE doseGroupId = :doseGroupId")
    suspend fun listForGroup(doseGroupId: Long): List<DoseItemEntity>

    @Query("UPDATE dose_item SET detectedCount = :count WHERE doseGroupId = :doseGroupId AND drugId = :drugId")
    suspend fun updateDetectedCount(doseGroupId: Long, drugId: Long, count: Int)

    @Query("SELECT * FROM dose_item WHERE doseGroupId IN (:groupIds) ORDER BY drugId ASC")
    suspend fun getForGroups(groupIds: List<Long>): List<DoseItemEntity>
}

@Dao
interface EvidenceDao {
    @Query("SELECT * FROM evidence WHERE doseGroupId = :doseGroupId ORDER BY capturedAt DESC")
    suspend fun getForGroup(doseGroupId: Long): List<EvidenceEntity>

    @Query("SELECT * FROM evidence ORDER BY capturedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<EvidenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(evidence: EvidenceEntity): Long

    @Query("SELECT * FROM evidence WHERE doseGroupId IN (:groupIds) ORDER BY capturedAt DESC")
    suspend fun getForGroups(groupIds: List<Long>): List<EvidenceEntity>
}

@Dao
interface DrugCacheDao {
    @Query("SELECT * FROM drug_cache WHERE barcode = :barcode")
    suspend fun get(barcode: String): DrugCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: DrugCacheEntity)

    @Query("DELETE FROM drug_cache WHERE isNegative = 1 AND fetchedAt < :before")
    suspend fun deleteExpiredNegative(before: Long)
}
