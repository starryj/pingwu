package com.pingwu.app.domain.verify

/**
 * 置信度公式（《02》§5(7)）：
 *   conf   = clamp(1 − 0.35 × d_min / T_accept, 0, 1) × margin
 *   margin = clamp((d2 − d_min) / (0.5 × T_accept), 0, 1)，单药种时 margin = 1
 * 性质：d_min = T_accept 时 conf 恰为 0.65。
 */
object Confidence {

    const val SLOPE = 0.35
    const val PASS_THRESHOLD = 0.65

    fun score(dMin: Double, tAccept: Double, dSecond: Double? = null): Double {
        if (tAccept <= 0.0) return 0.0
        val base = (1.0 - SLOPE * dMin / tAccept).coerceIn(0.0, 1.0)
        val margin = if (dSecond == null) {
            1.0
        } else {
            ((dSecond - dMin) / (0.5 * tAccept)).coerceIn(0.0, 1.0)
        }
        return (base * margin).coerceIn(0.0, 1.0)
    }
}
