package com.pingwu.app.data.core

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Query("SELECT * FROM pw_item WHERE archived = 0 ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM pw_item WHERE id = :id")
    fun observeById(id: Long): Flow<ItemEntity?>

    @Query("SELECT * FROM pw_item WHERE archived = 0 ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<ItemEntity>

    /** 连删掉的（归档）一起拿：凭物页的「已删除」和历史记录要显示它们的原名。 */
    @Query("SELECT * FROM pw_item ORDER BY sortOrder ASC, id ASC")
    fun observeAllIncludingArchived(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM pw_item WHERE archived = 1 ORDER BY id ASC")
    suspend fun getArchived(): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM pw_item")
    suspend fun count(): Int

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Query("UPDATE pw_item SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM pw_reminder WHERE enabled = 1 ORDER BY itemId ASC, timesCsv ASC")
    fun observeEnabled(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM pw_reminder ORDER BY itemId ASC")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM pw_reminder WHERE itemId = :itemId")
    fun observeByItem(itemId: Long): Flow<List<ReminderEntity>>

    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("DELETE FROM pw_reminder WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)

    @Query("UPDATE pw_reminder SET windowMinutes = :minutes WHERE itemId = :itemId")
    suspend fun updateWindow(itemId: Long, minutes: Int)
}

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM pw_occurrence ORDER BY localDate DESC, timeLabel DESC")
    fun observeAll(): Flow<List<OccurrenceEntity>>

    @Query("SELECT * FROM pw_occurrence WHERE localDate = :date ORDER BY timeLabel ASC")
    fun observeByDate(date: String): Flow<List<OccurrenceEntity>>

    @Query("SELECT * FROM pw_occurrence WHERE itemId = :itemId ORDER BY localDate DESC, timeLabel DESC")
    fun observeByItem(itemId: Long): Flow<List<OccurrenceEntity>>

    @Query("SELECT * FROM pw_occurrence")
    suspend fun getAll(): List<OccurrenceEntity>

    @Query("SELECT * FROM pw_occurrence WHERE itemId = :itemId AND localDate = :date AND timeLabel = :time LIMIT 1")
    suspend fun find(itemId: Long, date: String, time: String): OccurrenceEntity?

    @Query("SELECT * FROM pw_occurrence WHERE id = :id")
    suspend fun getById(id: Long): OccurrenceEntity?

    @Query("SELECT * FROM pw_occurrence WHERE localDate = :date ORDER BY timeLabel ASC")
    suspend fun getByDate(date: String): List<OccurrenceEntity>

    @Insert
    suspend fun insert(occurrence: OccurrenceEntity): Long

    @Query("UPDATE pw_occurrence SET state = :state, verifiedAt = :verifiedAt, isLate = :isLate WHERE id = :id")
    suspend fun mark(id: Long, state: String, verifiedAt: Long?, isLate: Boolean)

    /** 推迟：把这一次的到点时间往后挪，重启 App 也还记得。 */
    @Query("UPDATE pw_occurrence SET scheduledAt = :scheduledAt WHERE id = :id")
    suspend fun reschedule(id: Long, scheduledAt: Long)

    @Query("UPDATE pw_occurrence SET windowMinutes = :minutes WHERE itemId = :itemId AND localDate = :date AND state = 'PENDING'")
    suspend fun updateWindowForPending(itemId: Long, date: String, minutes: Int)

    /** 改了提醒规则后，把今天还没拍的旧待办清掉，再按新规则补。 */
    @Query("DELETE FROM pw_occurrence WHERE itemId = :itemId AND localDate = :date AND state = 'PENDING'")
    suspend fun deletePendingOn(itemId: Long, date: String)

    /** 记下这一次已经认到了哪几件（还没认全，所以状态仍是 PENDING）。 */
    @Query("UPDATE pw_occurrence SET doneSubjectIds = :doneSubjectIds WHERE id = :id")
    suspend fun markSubjectsDone(id: Long, doneSubjectIds: String)

    /** 彻底删除：删掉待办，它挂着的凭物记录会被外键级联一起删掉。 */
    @Query("DELETE FROM pw_occurrence WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface SubjectDao {
    @Query("SELECT * FROM pw_subject ORDER BY itemId ASC, sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM pw_subject ORDER BY itemId ASC, sortOrder ASC, id ASC")
    suspend fun getAll(): List<SubjectEntity>

    @Query("SELECT * FROM pw_subject WHERE itemId = :itemId ORDER BY sortOrder ASC, id ASC")
    suspend fun forItem(itemId: Long): List<SubjectEntity>

    @Insert
    suspend fun insert(subject: SubjectEntity): Long

    @Query("DELETE FROM pw_subject WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)
}

@Dao
interface EvidenceDao {
    @Query("SELECT * FROM pw_evidence ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<EvidenceEntity>>

    @Query("SELECT * FROM pw_evidence ORDER BY capturedAt DESC")
    suspend fun getAll(): List<EvidenceEntity>

    @Query("SELECT * FROM pw_evidence WHERE itemId = :itemId ORDER BY capturedAt DESC")
    fun observeByItem(itemId: Long): Flow<List<EvidenceEntity>>

    @Query("SELECT COUNT(*) FROM pw_evidence")
    suspend fun count(): Int

    @Insert
    suspend fun insert(evidence: EvidenceEntity): Long

    @Query("DELETE FROM pw_evidence")
    suspend fun deleteAll()

    /** 这些待办名下已经拍过的照片（彻底删除前先拿到文件路径，好把照片一起删掉）。 */
    @Query("SELECT * FROM pw_evidence WHERE occurrenceId IN (:occurrenceIds)")
    suspend fun forOccurrences(occurrenceIds: List<Long>): List<EvidenceEntity>
}
