package com.pingwu.app.data.repo

import com.pingwu.app.data.local.dao.ScheduleDao
import com.pingwu.app.data.local.entity.ScheduleEntity
import com.pingwu.app.domain.plan.PlanEngine
import com.pingwu.app.domain.plan.PlannedDose
import com.pingwu.app.domain.plan.SchedulePlanInput
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.time.LocalDate

class ScheduleRepository(private val scheduleDao: ScheduleDao) {

    fun observeAll(): Flow<List<ScheduleEntity>> = scheduleDao.observeAll()

    suspend fun getEnabled(): List<ScheduleEntity> = scheduleDao.getEnabled()

    suspend fun save(
        id: Long = 0,
        drugId: Long,
        countPerDose: Int,
        times: List<String>,
        daysOfWeek: Int,
        enabled: Boolean = true,
        windowMinutes: Int = 90,
        snoozeIntervalMinutes: Int = 10,
        snoozeLimit: Int = 3,
    ): Long = scheduleDao.upsert(
        ScheduleEntity(
            id = id,
            drugId = drugId,
            countPerDose = countPerDose,
            timesJson = encodeTimes(times),
            daysOfWeek = daysOfWeek,
            windowMinutes = windowMinutes,
            snoozeIntervalMinutes = snoozeIntervalMinutes.coerceIn(1, 120),
            snoozeLimit = snoozeLimit.coerceIn(0, 10),
            enabled = if (enabled) 1 else 0,
        )
    )

    suspend fun get(id: Long): ScheduleEntity? = scheduleDao.getById(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = scheduleDao.setEnabled(id, if (enabled) 1 else 0)

    suspend fun delete(id: Long) = scheduleDao.delete(id)

    /** 药品归档时一起停用它的计划，历史剂量组不动。 */
    suspend fun disableForDrug(drugId: Long) = scheduleDao.disableForDrug(drugId)

    /** 未来 [horizonDays] 天的剂量组（含今天），用于首页预览；M2 起写入 dose_group 表并加提醒。 */
    suspend fun previewDoses(nowMillis: Long, horizonDays: Int = 1): List<PlannedDose> {
        val inputs = getEnabled().map { it.toPlanInput() }
        return PlanEngine.generate(nowMillis, horizonDays, inputs)
    }

    companion object {
        fun decodeTimes(json: String): List<String> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())

        fun encodeTimes(times: List<String>): String = JSONArray(times).toString()

        fun ScheduleEntity.toPlanInput(): SchedulePlanInput = SchedulePlanInput(
            scheduleId = id,
            drugId = drugId,
            countPerDose = countPerDose,
            times = decodeTimes(timesJson),
            daysOfWeekMask = daysOfWeek,
            enabled = enabled == 1,
            startDate = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            windowMinutes = windowMinutes,
        )
    }
}
