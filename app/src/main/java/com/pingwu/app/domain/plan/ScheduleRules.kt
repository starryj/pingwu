package com.pingwu.app.domain.plan

/**
 * 提醒计划去重规则（纯 Kotlin，可单测）。
 *
 * 两条计划如果「同一件物品 + 至少一个共同星期 + 至少一个共同提醒时刻」，同一时刻就会给这件物品
 * 累计两件（PlanEngine 按物品数量相加），等于把一次要拍的数量悄悄翻倍；验证页要求「完全一致」，
 * 用户只能放两件才通过——这是**算错账**，不是显示问题，所以必须在保存前拦住。
 */
object ScheduleRules {

    /** 两条计划是否在同一星期、同一时刻给同一件物品重复计时。 */
    fun overlaps(timesA: List<String>, daysA: Int, timesB: List<String>, daysB: Int): Boolean {
        if (daysA and daysB == 0) return false
        if (timesA.isEmpty() || timesB.isEmpty()) return false
        val b = timesB.toSet()
        return timesA.any { it in b }
    }
}
