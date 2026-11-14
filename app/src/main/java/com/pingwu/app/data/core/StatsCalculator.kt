package com.pingwu.app.data.core

import java.time.LocalDate

/**
 * 纯计算：把 Occurrence / Evidence 汇总成界面用的统计口径。
 * 不碰数据库，方便 JVM 单测（Room 的 DAO 行为在设备/仪器测试里覆盖）。
 */
object StatsCalculator {

    fun compute(
        occurrences: List<OccurrenceEntity>,
        evidence: List<EvidenceEntity>,
        today: LocalDate = LocalDate.now(),
    ): EvidenceStats {
        val verified = occurrences.count { it.state == "VERIFIED" }
        val missed = occurrences.count { it.state == "MISSED" || it.state == "SKIPPED" }
        val denom = verified + missed
        val percent = if (denom == 0) 100 else Math.round(verified * 100f / denom)

        // 连续天数：今天已验证就从今天数起，否则从昨天往前数
        val verifiedDates = occurrences.filter { it.state == "VERIFIED" }.map { it.localDate }.toSet()
        var streak = 0
        var cursor = if (verifiedDates.contains(today.toString())) today else today.minusDays(1)
        while (verifiedDates.contains(cursor.toString())) {
            streak++
            cursor = cursor.minusDays(1)
        }

        val todayOccurrences = occurrences.filter { it.localDate == today.toString() }
        val done = todayOccurrences.count { it.state == "VERIFIED" }
        val total = todayOccurrences.size

        // -1 = 那天压根没安排，0 = 漏了，1 = 按时，2 = 今天还排着（待拍）
        val weekFlags = (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong()).toString()
            val dayOccurrences = occurrences.filter { it.localDate == date }
            val dayHasMiss = dayOccurrences.any { it.state == "MISSED" || it.state == "SKIPPED" }
            val dayHasVerified = dayOccurrences.any { it.state == "VERIFIED" }
            when {
                dayHasMiss -> 0
                dayHasVerified -> 1
                dayOccurrences.isNotEmpty() && date == today.toString() -> 2
                dayOccurrences.isEmpty() -> -1
                else -> 0
            }
        }

        return EvidenceStats(
            evidenceCount = evidence.size,
            onTimePercent = percent,
            streakDays = streak,
            missedCount = missed,
            todayDone = done,
            todayTotal = total,
            weekFlags = weekFlags,
            scheduledCount = occurrences.size,
            decidedCount = denom,
        )
    }
}
