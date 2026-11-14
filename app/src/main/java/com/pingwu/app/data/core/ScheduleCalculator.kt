package com.pingwu.app.data.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 下一个闹钟时刻的纯计算：给仓储层用，也方便 JVM 单测。
 * 只看今天和明天的时刻，避免一次算很远。
 */
object ScheduleCalculator {

    fun nextAfter(timesCsv: List<String>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val times = timesCsv.mapNotNull { raw ->
            val text = raw.trim()
            if (text.isEmpty()) null else runCatching { LocalTime.parse(text) }.getOrNull()
        }.distinct()
        if (times.isEmpty()) return null

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var best: Long? = null
        listOf(today, today.plusDays(1)).forEach { date ->
            times.forEach { time ->
                val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                if (at > now && (best == null || at < best!!)) best = at
            }
        }
        return best
    }

    fun millisAt(date: LocalDate, time: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
        runCatching { date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli() }.getOrNull()

    /**
     * 这一条算不算「到点了」。
     *
     * [leadMs] 只给闹钟接收端留一点余量（系统偶尔早投递几百毫秒），
     * 界面轮询必须传 0——否则页面会提前一分钟弹出来（2026-11-13 作者反馈）。
     */
    fun isDue(scheduledAt: Long, now: Long, leadMs: Long = 0L): Boolean =
        scheduledAt <= now + leadMs
}
