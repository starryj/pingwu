package com.pingwu.app.cv

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/** 观测到的一颗「药丸候选」（轮廓 + 尺度无关特征）。 */
data class PillObservation(
    val centerX: Float,
    val centerY: Float,
    val area: Double,
    val width: Int,
    val height: Int,
    val aspect: Double,
    val circularity: Double,
    val solidity: Double,
    val colorLab: DoubleArray,
    val hu: DoubleArray,
    val points: List<FloatArray>,
    val isClump: Boolean,
    /** 面积 / 最小外接旋转矩形面积；-1 表示未计算（回退用轴对齐包围盒）。 */
    val extent: Double = -1.0,
)

/** 一次检测的结果。count 是「轮廓数」，clumps 是判为粘连的个数（不自动拆分）。 */
data class DetectResult(
    val count: Int,
    val clumps: Int,
    val observations: List<PillObservation>,
    val medianArea: Double,
    val threshold: Double,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val cleanCount: Int get() = count - clumps
}

/** 纯 Kotlin 的几何/规则判定：可以脱离 OpenCV 在 JVM 单测里跑。 */
object PillMath {

    /** 粘连判定阈值（见《02》§5）：面积比 > 2.8 或 实心度 < 0.72 视为可疑粘连。 */
    const val CLUMP_AREA_RATIO = 2.8
    const val CLUMP_SOLIDITY = 0.72

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    fun circularity(area: Double, perimeter: Double): Double {
        if (perimeter <= 0.0) return 0.0
        return (4.0 * Math.PI * area) / (perimeter * perimeter)
    }

    fun aspectOf(width: Int, height: Int): Double {
        val long = max(width, height).toDouble()
        val short = kotlin.math.min(width, height).toDouble()
        if (short <= 0.0) return 0.0
        return long / short
    }

    /** 轮廓面积的合理范围：太小是噪点，太大是手掌/桌面边缘。 */
    fun isPlausibleArea(area: Double, imageWidth: Int, imageHeight: Int): Boolean {
        val frame = imageWidth.toDouble() * imageHeight.toDouble()
        return area >= frame * 0.0002 && area <= frame * 0.08
    }

    fun isClump(area: Double, medianArea: Double, solidity: Double): Boolean {
        if (medianArea <= 0.0) return false
        return area > medianArea * CLUMP_AREA_RATIO || solidity < CLUMP_SOLIDITY
    }

    /** 把 Hu 矩转成对数尺度，避免数值跨量级；前 4 个足够区分常见药片形状。 */
    fun logHu(hu: DoubleArray): DoubleArray {
        val out = DoubleArray(4)
        for (i in 0 until 4) {
            val v = abs(hu.getOrElse(i) { 0.0 })
            out[i] = if (v < 1e-12) 0.0 else -Math.signum(hu.getOrElse(i) { 0.0 }) * ln(v)
        }
        return out
    }

    fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double = hypot(x1 - x2, y1 - y2)
}
