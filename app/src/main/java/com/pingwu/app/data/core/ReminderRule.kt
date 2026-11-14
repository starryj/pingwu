package com.pingwu.app.data.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 提醒的重复规则（M9）。
 *
 * 四类，够覆盖日常：
 * - DAILY   每天，可多个时间点
 * - WEEKLY  每周某几天，可多选
 * - MONTHLY 每月某日
 * - ONCE    只一次，指定日期 + 时间
 *
 * 全部是纯函数，方便 JVM 单测；数据库里只存字符串与逗号列表。
 */
object ReminderRule {

    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
    const val ONCE = "ONCE"

    val ALL = listOf(DAILY, WEEKLY, MONTHLY, ONCE)

    private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val WEEK_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun label(rule: String): String = when (rule) {
        WEEKLY -> "每周"
        MONTHLY -> "每月"
        ONCE -> "一次"
        else -> "每天"
    }

    fun weekdayName(dayOfWeek: Int): String = WEEK_NAMES.getOrElse(dayOfWeek - 1) { "" }

    /** WEEKLY 的星期（1=周一…7=周日）/ MONTHLY 的几号，统一存在 daysCsv 里。 */
    fun days(reminder: ReminderEntity): List<Int> =
        reminder.daysCsv.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..31 }
            .distinct()

    fun weekDays(reminder: ReminderEntity): List<Int> = days(reminder).filter { it in 1..7 }.sorted()

    fun monthDay(reminder: ReminderEntity): Int = days(reminder).firstOrNull()?.coerceIn(1, 31) ?: 1

    fun times(reminder: ReminderEntity): List<String> =
        reminder.timesCsv.split(',')
            .map { it.trim() }
            .filter { TIME_REGEX.matches(it) }
            .distinct()
            .sorted()

    /** 这条提醒在 date 这天要不要发生。 */
    fun matches(reminder: ReminderEntity, date: LocalDate): Boolean {
        if (!reminder.enabled) return false
        return when (reminder.rule) {
            WEEKLY -> date.dayOfWeek.value in weekDays(reminder)
            MONTHLY -> date.dayOfMonth == monthDay(reminder)
            ONCE -> reminder.onceDate == date.toString()
            else -> true
        }
    }

    /** 从 now 起的下一次响铃（epoch ms）；未来 400 天内没有就返回 null。 */
    fun nextAfter(
        reminder: ReminderEntity,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        lookAheadDays: Int = 400,
    ): Long? {
        if (!reminder.enabled) return null
        val dayTimes = times(reminder).mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (dayTimes.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (offset in 0..lookAheadDays) {
            val date = today.plusDays(offset.toLong())
            if (!matches(reminder, date)) continue
            dayTimes.forEach { time ->
                val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
        }
        return null
    }

    /** 一句话说明，物品卡、详情页、首页用。 */
    fun summary(reminder: ReminderEntity): String {
        val t = times(reminder).joinToString(" · ")
        if (t.isEmpty()) return "还没设时间"
        return when (reminder.rule) {
            WEEKLY -> {
                val names = weekDays(reminder).joinToString("、") { weekdayName(it).removePrefix("周") }
                "每周${names.ifBlank { "一" }} $t"
            }
            MONTHLY -> "每月 ${monthDay(reminder)} 日 $t"
            ONCE -> "${formatOnceDate(reminder.onceDate)} $t"
            else -> "每天 $t"
        }
    }

    fun summaryOf(reminders: List<ReminderEntity>): String =
        reminders.firstOrNull()?.let { summary(it) } ?: "还没设置提醒"

    fun formatOnceDate(iso: String): String = runCatching {
        val date = LocalDate.parse(iso)
        "${date.monthValue}.${date.dayOfMonth} ${weekdayName(date.dayOfWeek.value)}"
    }.getOrDefault("还没选日期")
}
