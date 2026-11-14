package com.pingwu.app.cv

import kotlin.math.sqrt

/**
 * 颜色工具：OpenCV 8U Lab 的三通道并不是真实 Lab 值。
 * L = L* × 255 / 100；a = a* + 128；b = b* + 128。见《02-技术方案》§5(3)。
 */
object LabColor {
    fun from8u(l8: Double, a8: Double, b8: Double): DoubleArray =
        doubleArrayOf(l8 * 100.0 / 255.0, a8 - 128.0, b8 - 128.0)

    /** 色度 C*ab = sqrt(a² + b²)，越高越鲜艳。 */
    fun chroma(lab: DoubleArray): Double {
        val a = lab.getOrElse(1) { 0.0 }
        val b = lab.getOrElse(2) { 0.0 }
        return sqrt(a * a + b * b)
    }

    /** CIE76 色差（MVP 够用；CIEDE2000 留到后续版本）。 */
    fun deltaE76(c1: DoubleArray, c2: DoubleArray): Double {
        var sum = 0.0
        for (i in 0 until 3) {
            val d = c1.getOrElse(i) { 0.0 } - c2.getOrElse(i) { 0.0 }
            sum += d * d
        }
        return sqrt(sum)
    }
}

/**
 * 特征顺序即契约（写入模板 JSON 的 featureOrder）。
 * MVP 先实现 13 维：几何 8 + 相对面积 1 + 颜色 4。
 * 纹理（texLap64）与色相直方图（hueHist18）留到 v0.4，避免 MVP 过度复杂。
 */
object FeatureOrder {
    val names: List<String> = listOf(
        "circularity", "aspect", "extent", "solidity",
        "hu1", "hu2", "hu3", "hu4",
        "relArea",
        "labL", "laba", "labb", "chroma",
    )
    const val COUNT = 13
}

/** 单颗药丸的尺度无关特征向量。 */
data class PillFeature(val values: DoubleArray) {
    init {
        require(values.size == FeatureOrder.COUNT) { "feature size must be 13" }
    }

    fun named(): Map<String, Double> =
        FeatureOrder.names.mapIndexed { i, n -> n to values[i] }.toMap()

    companion object {
        /** @param relArea 该颗面积 / 全图中位面积；尺度无关，用于发现多颗/粘连/异物。 */
        fun from(obs: PillObservation, relArea: Double): PillFeature {
            // 优先用「最小外接旋转矩形」算实心度：轴对齐包围盒会随药丸摆放角度变化，
            // 45° 的胶囊会被算成 aspect≈1.0、extent≈0.58，和模板对不上（2026-11-12 真机教训）。
            val extent = when {
                obs.extent > 0.0 -> obs.extent
                obs.width > 0 && obs.height > 0 ->
                    obs.area / (obs.width.toDouble() * obs.height.toDouble())
                else -> 0.0
            }
            val lab = LabColor.from8u(
                obs.colorLab.getOrElse(0) { 0.0 },
                obs.colorLab.getOrElse(1) { 128.0 },
                obs.colorLab.getOrElse(2) { 128.0 },
            )
            return PillFeature(
                doubleArrayOf(
                    obs.circularity,
                    obs.aspect,
                    extent,
                    obs.solidity,
                    obs.hu.getOrElse(0) { 0.0 },
                    obs.hu.getOrElse(1) { 0.0 },
                    obs.hu.getOrElse(2) { 0.0 },
                    obs.hu.getOrElse(3) { 0.0 },
                    relArea,
                    lab[0], lab[1], lab[2],
                    LabColor.chroma(lab),
                )
            )
        }
    }
}

/**
 * 特征归一化与距离。
 * d = sqrt( Σ (w_i · Δz_i)² / Σ w_i² )，Δz_i = (x_i − μ_i) / max(σ_i, floor_i)
 * 用 σ 归一化后，不同量纲（Lab 的 a/b 与圆度）可以放进同一把尺子。
 */
object FeatureDistance {
    /** 特征初值权重，见《02》§5(4) 特征表；未列出的项按 0.3。 */
    val weights: DoubleArray = doubleArrayOf(
        // ===== 策略：只认「颜色 + 数量」（2026-11-12 用户决定，停止调参）=====
        // 依据：真机上形态特征全部来自「框内 Lab 分割掩膜」，掩膜会吃到药丸底下的阴影。
        // 同一颗蓝胶囊，注册时 aspect 3.24、验证时 1.66；圆度 0.53 漂到 0.73。
        // 形态不可复现 → 权重全部归零，身份只由颜色决定。
        0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0,
        // relArea = 该颗面积 / 全图中位面积，取决于「同框里还有哪些药」，同样不是身份特征。
        0.0,
        // 颜色：Lab L + a* + b* + chroma。
        1.0, 1.5, 1.5,
        1.2,
    )

    /**
     * σ 下限：σ 过小会把噪声放大成巨大距离。
     * 真机教训（2026-11-12）：注册与验证的光照/曝光必然不同，Lab 的 L 能差 20+；
     * Hu 矩对轮廓抖动极敏感。下限必须按“真实世界波动”给，而不是按单次拍摄的 σ 给。
     */
    val sigmaFloor: DoubleArray = doubleArrayOf(
        // 形态项权重为 0，下限保留只为防御性（不再参与距离）。
        0.20, 1.00, 0.20, 0.05,
        1.5, 1.5, 1.5, 1.5,
        0.20,
        // 颜色下限按真机实测漂移给：同一颗药在不同背景下自动白平衡会变，
        // L 能差 5~25，a*/b* 能各差 10~13，chroma 差 8~10。
        // 因此 L 给 20、a*/b*/chroma 各给 15 —— 相当于「ΔE 15 以内算同一颜色」。
        20.0, 15.0, 15.0, 15.0,
    )

    fun normalizedDelta(x: DoubleArray, mu: DoubleArray, sigma: DoubleArray): DoubleArray =
        DoubleArray(FeatureOrder.COUNT) { i ->
            val s = maxOf(sigma.getOrElse(i) { 0.0 }, sigmaFloor[i])
            (x.getOrElse(i) { 0.0 } - mu.getOrElse(i) { 0.0 }) / s
        }

    fun distance(x: DoubleArray, mu: DoubleArray, sigma: DoubleArray): Double {
        val dz = normalizedDelta(x, mu, sigma)
        var num = 0.0
        var den = 0.0
        for (i in 0 until FeatureOrder.COUNT) {
            val w = weights[i]
            num += (w * dz[i]) * (w * dz[i])
            den += w * w
        }
        return if (den <= 0.0) 0.0 else sqrt(num / den)
    }

    fun distance(a: PillFeature, mu: DoubleArray, sigma: DoubleArray): Double =
        distance(a.values, mu, sigma)
}
