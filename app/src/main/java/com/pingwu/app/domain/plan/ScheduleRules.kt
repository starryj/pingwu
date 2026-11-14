package com.pingwu.app.domain.plan

/**
 * 用药计划去重规则（纯 Kotlin，可单测）。
 *
 * 两条计划如果「同一种药 + 至少一个共同星期 + 至少一个共同提醒时刻」，同一时刻就会给这种药
 * 累计两颗（PlanEngine 按药种颗数相加），等于把一次剂量悄悄翻倍；验证页要求「完全一致」，
 * 用户只能放两颗才通过——这是**安全问题**，不是显示问题，所以必须在保存前拦住。
 */
object ScheduleRules {

    /** 两条计划是否在同一星期、同一时刻给同一种药重复计时。 */
    fun overlaps(timesA: List<String>, daysA: Int, timesB: List<String>, daysB: Int): Boolean {
        if (daysA and daysB == 0) return false
        if (timesA.isEmpty() || timesB.isEmpty()) return false
        val b = timesB.toSet()
        return timesA.any { it in b }
    }
}
