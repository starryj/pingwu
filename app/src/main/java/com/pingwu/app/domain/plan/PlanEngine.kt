package com.pingwu.app.domain.plan

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 计划展开引擎（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 输入：启用的用药计划；输出：从 now 起 horizonDays 天内的「剂量组」。
 * 规则（见《02》§4.6 / 《03》M1）：
 *  - 同一天同一时刻的多种药合并为一个剂量组（doseKey = yyyy-MM-ddTHH:mm）；
 *  - 同一药同一时刻出现在多个计划里，颗数相加；
 *  - 停用计划、未到 startDate、不在重复星期的日期都不生成；
 *  - 只生成 scheduledAt >= now 的剂量组，过去的不重复生成。
 */
data class SchedulePlanInput(
    val scheduleId: Long,
    val drugId: Long,
    val countPerDose: Int,
    val times: List<String>,
    /** bit0=周一 … bit6=周日；0x7F=每天 */
    val daysOfWeekMask: Int,
    val enabled: Boolean,
    val startDate: LocalDate?,
    val windowMinutes: Int = 90,
)

data class PlannedItem(
    val drugId: Long,
    val plannedCount: Int,
    val scheduleId: Long,
)

data class PlannedDose(
    val doseKey: String,
    val scheduledAt: Long,
    val items: List<PlannedItem>,
)

object PlanEngine {

    fun generate(
        nowMillis: Long,
        horizonDays: Int,
        schedules: List<SchedulePlanInput>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PlannedDose> {
        val now = Instant.ofEpochMilli(nowMillis)
        val today = now.atZone(zone).toLocalDate()
        // 上界 = 最后一天次日 00:00（horizonDays=0 表示只含今天）
        val end = today.plusDays(horizonDays.toLong() + 1).atStartOfDay(zone).toInstant()

        data class Acc(val scheduledAt: Long, val items: MutableMap<Long, PlannedItem>)
        val byKey = LinkedHashMap<String, Acc>()

        for (offset in 0..horizonDays) {
            val date = today.plusDays(offset.toLong())
            val bit = 1 shl (date.dayOfWeek.value - 1)
            for (schedule in schedules) {
                if (!schedule.enabled) continue
                if (schedule.daysOfWeekMask and bit == 0) continue
                val start = schedule.startDate
                if (start != null && date.isBefore(start)) continue
                for (raw in schedule.times) {
                    val time = parseTime(raw) ?: continue
                    val instant = date.atTime(time).atZone(zone).toInstant()
                    if (instant.isBefore(now) || !instant.isBefore(end)) continue  // [now, end)
                    val key = doseKeyOf(date, time)
                    val acc = byKey.getOrPut(key) { Acc(instant.toEpochMilli(), LinkedHashMap()) }
                    val exist = acc.items[schedule.drugId]
                    acc.items[schedule.drugId] = if (exist == null) {
                        PlannedItem(schedule.drugId, schedule.countPerDose, schedule.scheduleId)
                    } else {
                        exist.copy(plannedCount = exist.plannedCount + schedule.countPerDose)
                    }
                }
            }
        }
        return byKey.entries
            .sortedBy { it.value.scheduledAt }
            .map { (key, acc) ->
                PlannedDose(
                    doseKey = key,
                    scheduledAt = acc.scheduledAt,
                    items = acc.items.values.sortedBy { it.drugId },
                )
            }
    }

    fun parseTime(raw: String): LocalTime? = runCatching {
        val parts = raw.trim().split(":")
        LocalTime.of(parts[0].toInt(), parts[1].toInt())
    }.getOrNull()

    fun doseKeyOf(date: LocalDate, time: LocalTime): String =
        date.toString() + "T" + pad(time.hour) + ":" + pad(time.minute)

    private fun pad(v: Int): String = if (v < 10) "0" + v else v.toString()
}
