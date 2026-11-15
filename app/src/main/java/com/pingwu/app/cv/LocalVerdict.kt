package com.pingwu.app.cv

/** 本机判定结果：数量 + 照片质量都过关才算通过。 */
data class LocalVerdict(val pass: Boolean, val reason: String? = null)

/**
 * 离线判定规则。抽成纯 Kotlin，方便 JVM 单测；
 * 界面上只负责把 [reason] 显示出来。
 */
object LocalVerdictJudge {

    fun judge(
        count: Int,
        expected: Int,
        qualityOk: Boolean,
        qualityIssue: String? = null,
        unit: String = "件",
    ): LocalVerdict = when {
        count != expected -> LocalVerdict(
            pass = false,
            reason = "本机看到 $count$unit，和应拍的 $expected${unit}对不上。",
        )

        !qualityOk -> LocalVerdict(
            pass = false,
            reason = "数量对上了，但照片不够清楚" +
                (qualityIssue?.let { "（$it）" } ?: "") + "。",
        )

        else -> LocalVerdict(pass = true)
    }
}
