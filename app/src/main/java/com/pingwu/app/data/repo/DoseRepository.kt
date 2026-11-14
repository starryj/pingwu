package com.pingwu.app.data.repo

import com.pingwu.app.data.local.dao.DoseGroupDao
import com.pingwu.app.data.local.dao.DoseItemDao
import com.pingwu.app.data.local.dao.DoseItemWithName
import com.pingwu.app.data.local.dao.EvidenceDao
import com.pingwu.app.data.local.dao.ScheduleDao
import com.pingwu.app.data.local.entity.DoseGroupEntity
import com.pingwu.app.data.local.entity.DoseItemEntity
import com.pingwu.app.data.model.DoseState
import com.pingwu.app.data.repo.ScheduleRepository.Companion.toPlanInput
import com.pingwu.app.domain.plan.PlanEngine
import com.pingwu.app.domain.reminder.ReminderPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 验证页/提醒页需要的剂量详情 */
data class DoseDetail(
    val group: DoseGroupEntity,
    val items: List<DoseItemWithName>,
)

/**
 * 剂量组仓库：把「计划」展开成「剂量组」落库，并维护提醒窗口状态机。
 *
 * 状态机见《04》§3 / 《02》§3.2：
 * PENDING → REMINDING → SNOOZED（可多次，上限 snoozeLimit）→ VERIFIED/MISSED/SKIPPED；
 * 窗口结束自动 MISSED 并解开阻塞，窗口结束 +10 分钟弹「最终提醒」，此时仍可补拍（迟到）或记未服。
 */
class DoseRepository(
    private val doseGroupDao: DoseGroupDao,
    private val doseItemDao: DoseItemDao,
    private val scheduleDao: ScheduleDao,
    private val evidenceDao: EvidenceDao,
) {

    companion object {
        /** 一次展开的天数：覆盖每周固定几天、隔周等计划，App 长期不打开也不会漏排闹钟。 */
        const val HORIZON_DAYS = 14

        /** 排闹钟时向后看的范围，比展开范围多留 2 天余量（含边界与延后）。 */
        const val ALARM_LOOKAHEAD_DAYS = HORIZON_DAYS + 2
    }

    /** 当前应阻塞主界面的剂量组；延后等待期内不阻塞（见 DAO 查询注释） */
    suspend fun getBlocking(now: Long = System.currentTimeMillis()): DoseGroupEntity? =
        doseGroupDao.getBlockingDose(now)

    suspend fun getById(id: Long): DoseGroupEntity? = doseGroupDao.getById(id)

    suspend fun detail(id: Long): DoseDetail? {
        val group = doseGroupDao.getById(id) ?: return null
        return DoseDetail(group, doseItemDao.getWithDrugName(id))
    }

    suspend fun getItemCounts(groupId: Long): Map<Long, Int> =
        doseItemDao.getForGroup(groupId).associate { it.drugId to it.plannedCount }

    /**
     * 把未来 [horizonDays] 天的计划展开并落库；同时推进今日已到点的状态。
     * 可重复调用（doseKey 唯一，幂等）。
     */
    suspend fun sync(now: Long = System.currentTimeMillis(), horizonDays: Int = HORIZON_DAYS) {
        val zone = ZoneId.systemDefault()
        val todayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val schedules = scheduleDao.getEnabled()
        if (schedules.isNotEmpty()) {
            val byId = schedules.associateBy { it.id }
            val planned = PlanEngine.generate(todayStart, horizonDays, schedules.map { it.toPlanInput() }, zone)
            for (dose in planned) {
                val existing = doseGroupDao.getByKey(dose.doseKey)
                if (existing != null) continue
                // 计划是后来才建的（例：晚上才给「每天 08:00」建计划），不能给建计划之前的时间补记漏服。
                // 只保留「剂量时刻不早于计划创建时刻」的条目；全部被过滤就整组不建。
                val effectiveItems = dose.items.filter { item ->
                    val schedule = byId[item.scheduleId] ?: return@filter false
                    schedule.createdAt <= dose.scheduledAt
                }
                if (effectiveItems.isEmpty()) continue
                val related = effectiveItems.mapNotNull { byId[it.scheduleId] }
                val windowMinutes = related.maxOfOrNull { it.windowMinutes } ?: 90
                val snoozeInterval = related.minOfOrNull { it.snoozeIntervalMinutes } ?: 10
                val snoozeLimit = related.maxOfOrNull { it.snoozeLimit } ?: 3
                val windowEnd = ReminderPolicy.windowEndAt(dose.scheduledAt, windowMinutes)
                val state = ReminderPolicy.stateAt(now, dose.scheduledAt, windowEnd)
                val group = DoseGroupEntity(
                    doseKey = dose.doseKey,
                    scheduledAt = dose.scheduledAt,
                    windowEndAt = windowEnd,
                    windowMinutes = windowMinutes,
                    snoozeIntervalMinutes = snoozeInterval,
                    snoozeLimit = snoozeLimit,
                    state = state.name,
                    nextNotifyAt = when (state) {
                        DoseState.PENDING -> dose.scheduledAt
                        DoseState.REMINDING -> now
                        else -> null
                    },
                    finalReminderAt = if (state == DoseState.MISSED) {
                        ReminderPolicy.finalReminderAt(windowEnd)
                    } else null,
                )
                val id = doseGroupDao.insertIgnore(group)
                val groupId = if (id > 0) id else doseGroupDao.getByKey(dose.doseKey)?.id ?: continue
                val items = effectiveItems.map {
                    DoseItemEntity(doseGroupId = groupId, drugId = it.drugId, plannedCount = it.plannedCount)
                }
                if (items.isNotEmpty()) doseItemDao.insertAll(items)
            }
        }
        advanceStarted(now)
        markExpired(now)
        cleanup(now)
    }

    /**
     * 计划变更后重建「未来未到点」的剂量组：先删再按当前启用计划展开。
     *
     * 必须在新增/停用/改时间后调用：sync() 遇到同 doseKey 会跳过，
     * 不重建的话「先建 A 药 20:00，再给 B 药也加 20:00」B 永远排不进去，
     * 停用计划后旧组也会继续提醒。
     */
    suspend fun rebuildPending(now: Long = System.currentTimeMillis()) {
        doseGroupDao.deleteFuturePending(now)
        sync(now, horizonDays = HORIZON_DAYS)
    }

    /** 已到点但还没进入提醒的组：PENDING → REMINDING。闹钟已错过时由 App 启动的阻塞页接管，不再补发通知。 */
    suspend fun advanceStarted(now: Long) {
        val started = doseGroupDao.getStarted(now)
        for (group in started) {
            if (group.state != DoseState.PENDING.name) continue
            if (now >= group.windowEndAt) continue
            doseGroupDao.update(
                group.copy(state = DoseState.REMINDING.name, nextNotifyAt = now)
            )
        }
    }

    /** 窗口结束还没验证：记 MISSED（解阻塞），并安排窗口结束后 10 分钟的最终提醒 */
    suspend fun markExpired(now: Long) {
        val expired = doseGroupDao.getExpired(now)
        for (group in expired) {
            doseGroupDao.update(
                group.copy(
                    state = DoseState.MISSED.name,
                    nextNotifyAt = null,
                    finalReminderAt = ReminderPolicy.finalReminderAt(group.windowEndAt),
                )
            )
        }
    }

    /**
     * 延后：只在有效窗口内允许，次数上限内重算窗口。
     * @return 更新后的剂量组；不允许时为 null
     */
    suspend fun snooze(id: Long, now: Long = System.currentTimeMillis()): DoseGroupEntity? {
        val group = doseGroupDao.getById(id) ?: return null
        val state = runCatching { DoseState.valueOf(group.state) }.getOrDefault(DoseState.PENDING)
        if (!ReminderPolicy.isBlocking(state)) return null
        if (now >= group.windowEndAt) return null
        if (!ReminderPolicy.canSnooze(group.snoozeCount, group.snoozeLimit)) return null
        val snooze = ReminderPolicy.computeSnooze(now, group.snoozeIntervalMinutes, group.windowMinutes)
        val updated = group.copy(
            state = DoseState.SNOOZED.name,
            snoozeCount = group.snoozeCount + 1,
            nextNotifyAt = snooze.nextNotifyAt,
            windowEndAt = snooze.windowEndAt,
            finalReminderAt = null,
        )
        doseGroupDao.update(updated)
        return updated
    }

    /** 「本次未服」出口（用户主动选择，不是漏服） */
    suspend fun skip(id: Long): Boolean {
        val group = doseGroupDao.getById(id) ?: return false
        if (group.state == DoseState.VERIFIED.name) return false
        doseGroupDao.update(
            group.copy(
                state = DoseState.SKIPPED.name,
                nextNotifyAt = null,
                finalReminderAt = null,
                verifyAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 闹钟/通知触发时推进状态；返回更新后的组（若已无需处理返回 null） */
    suspend fun onReminderFired(id: Long, now: Long = System.currentTimeMillis()): DoseGroupEntity? {
        val group = doseGroupDao.getById(id) ?: return null
        val state = runCatching { DoseState.valueOf(group.state) }.getOrDefault(DoseState.PENDING)
        if (state == DoseState.MISSED) {
            // 最终提醒已弹过就消费掉，避免重复
            if (group.finalReminderAt != null && now >= group.finalReminderAt) {
                doseGroupDao.update(group.copy(finalReminderAt = null))
            }
            return group.copy(finalReminderAt = null)
        }
        if (state != DoseState.PENDING && state != DoseState.REMINDING && state != DoseState.SNOOZED) return null
        val updated = if (now >= group.windowEndAt) {
            group.copy(
                state = DoseState.MISSED.name,
                nextNotifyAt = null,
                finalReminderAt = ReminderPolicy.finalReminderAt(group.windowEndAt),
            )
        } else {
            group.copy(state = DoseState.REMINDING.name, nextNotifyAt = null)
        }
        doseGroupDao.update(updated)
        return updated
    }

    /** 下一个需要响的剂量组（用于排闹钟） */
    suspend fun nextAlarm(now: Long = System.currentTimeMillis()): Pair<DoseGroupEntity, Long>? {
        val to = now + ALARM_LOOKAHEAD_DAYS * 24L * 60 * 60 * 1000
        val candidates = doseGroupDao.getUpcoming(now - 24 * 60 * 60 * 1000L, to) +
            doseGroupDao.getFinalReminders(now)
        return candidates.mapNotNull { group ->
            val state = runCatching { DoseState.valueOf(group.state) }.getOrDefault(DoseState.PENDING)
            val at = when (state) {
                DoseState.PENDING -> group.scheduledAt.coerceAtLeast(now + 1_000L)
                DoseState.REMINDING, DoseState.SNOOZED -> listOfNotNull(
                    group.nextNotifyAt?.takeIf { it > now },
                    group.windowEndAt.takeIf { it > now },
                ).minOrNull()
                DoseState.MISSED -> group.finalReminderAt?.takeIf { it > now }
                DoseState.VERIFIED, DoseState.SKIPPED -> null
            }
            if (at != null && at > 0) group to at else null
        }.minByOrNull { it.second }
    }

    /** 闹钟到点后的处理结果 */
    enum class AlarmOutcome { REMIND, FINAL_REMIND, NONE }

    /** 闹钟到点：要么进入/维持提醒，要么窗口结束记漏服，要么弹最终提醒。 */
    suspend fun processAlarm(id: Long, now: Long = System.currentTimeMillis()): AlarmOutcome {
        val group = doseGroupDao.getById(id) ?: return AlarmOutcome.NONE
        val state = runCatching { DoseState.valueOf(group.state) }.getOrDefault(DoseState.PENDING)
        if (state == DoseState.MISSED) {
            val finalAt = group.finalReminderAt ?: return AlarmOutcome.NONE
            if (now < finalAt) return AlarmOutcome.NONE
            doseGroupDao.update(group.copy(finalReminderAt = null))
            return AlarmOutcome.FINAL_REMIND
        }
        if (state == DoseState.VERIFIED || state == DoseState.SKIPPED) return AlarmOutcome.NONE
        if (now >= group.windowEndAt) {
            doseGroupDao.update(
                group.copy(
                    state = DoseState.MISSED.name,
                    nextNotifyAt = null,
                    finalReminderAt = ReminderPolicy.finalReminderAt(group.windowEndAt),
                )
            )
            return AlarmOutcome.NONE
        }
        if (state == DoseState.PENDING) {
            doseGroupDao.update(group.copy(state = DoseState.REMINDING.name, nextNotifyAt = null))
            return AlarmOutcome.REMIND
        }
        // REMINDING / SNOOZED：nextNotifyAt 到点，继续提醒；状态回到 REMINDING 才会继续阻塞主界面
        doseGroupDao.update(group.copy(state = DoseState.REMINDING.name, nextNotifyAt = null))
        return AlarmOutcome.REMIND
    }

    /** 近 [days] 天的剂量组（历史页），按时间倒序 */
    fun observeRecent(now: Long = System.currentTimeMillis(), days: Int = 30): Flow<List<DoseGroupEntity>> =
        doseGroupDao.observeRecent(now - days * 24L * 60 * 60 * 1000, now)

    suspend fun itemsForGroups(groupIds: List<Long>): List<DoseItemEntity> =
        if (groupIds.isEmpty()) emptyList() else doseItemDao.getForGroups(groupIds)

    suspend fun evidenceForGroups(groupIds: List<Long>): List<com.pingwu.app.data.local.entity.EvidenceEntity> =
        if (groupIds.isEmpty()) emptyList() else evidenceDao.getForGroups(groupIds)

    suspend fun cleanup(now: Long) {
        doseGroupDao.deleteOlderFinished(now - 30L * 24 * 60 * 60 * 1000)
    }
}