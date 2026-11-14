package com.pingwu.app.domain.reminder

import com.pingwu.app.data.model.DoseState

/**
 * 提醒窗口与延后策略（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * 语义（《01-需求优化与决策清单》问题 1/2，Q1=A、Q2=B）：
 * - 每个剂量组有一个有效窗口：默认 scheduledAt + windowMinutes（默认 90 分钟）。
 * - 窗口内点「延后」：nextNotifyAt = 延后时刻 + interval，且整个窗口从延后时刻重新计算。
 * - 延后次数上限默认 3：第 3 次延后后仍可再走一轮完整窗口才算漏服。
 * - 窗口结束即记 MISSED（解开阻塞），并在窗口结束后 10 分钟再弹一次「最终提醒」，
 *   此时仍可补拍记迟到，或记「本次未服」。
 */
object ReminderPolicy {

    /** 窗口结束后多久弹最终提醒（分钟） */
    const val FINAL_REMINDER_DELAY_MINUTES: Int = 10

    fun windowEndAt(base: Long, windowMinutes: Int): Long =
        base + windowMinutes.coerceAtLeast(1) * 60_000L

    fun finalReminderAt(windowEndAt: Long): Long =
        windowEndAt + FINAL_REMINDER_DELAY_MINUTES * 60_000L

    /** 是否处于阻塞主界面的状态 */
    fun isBlocking(state: DoseState): Boolean =
        state == DoseState.REMINDING || state == DoseState.SNOOZED

    /** 到点后首次进入提醒：PENDING -> REMINDING；已过窗口直接 MISSED */
    fun stateAt(now: Long, scheduledAt: Long, windowEndAt: Long): DoseState = when {
        now < scheduledAt -> DoseState.PENDING
        now < windowEndAt -> DoseState.REMINDING
        else -> DoseState.MISSED
    }

    /** 还能不能延后（次数上限内） */
    fun canSnooze(snoozeCount: Int, snoozeLimit: Int): Boolean = snoozeCount < snoozeLimit

    fun nextSnoozeNotifyAt(now: Long, intervalMinutes: Int): Long =
        now + intervalMinutes.coerceAtLeast(1) * 60_000L

    /** 一次延后的时间结果（Q2=B：整个有效窗口从延后时刻重新计算） */
    data class SnoozeOutcome(val nextNotifyAt: Long, val windowEndAt: Long)

    fun computeSnooze(now: Long, intervalMinutes: Int, windowMinutes: Int): SnoozeOutcome =
        SnoozeOutcome(
            nextNotifyAt = nextSnoozeNotifyAt(now, intervalMinutes),
            windowEndAt = windowEndAt(now, windowMinutes),
        )

    /** 下一个应该响的时间；null 表示无需再响 */
    fun nextAlarmAt(
        state: DoseState,
        scheduledAt: Long,
        nextNotifyAt: Long?,
        finalReminderAt: Long?,
        now: Long,
    ): Long? = when (state) {
        DoseState.PENDING -> nextNotifyAt ?: scheduledAt
        DoseState.REMINDING -> nextNotifyAt
        DoseState.SNOOZED -> nextNotifyAt
        DoseState.MISSED -> finalReminderAt?.takeIf { it > now }
        DoseState.VERIFIED, DoseState.SKIPPED -> null
    }

    /** 窗口已过但还没收尾的组：统一转 MISSED 并安排最终提醒 */
    fun isExpired(now: Long, windowEndAt: Long): Boolean = now >= windowEndAt
}
