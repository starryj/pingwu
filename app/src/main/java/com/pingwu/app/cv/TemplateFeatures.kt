package com.pingwu.app.cv

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 一种药的模板特征统计量（对应《02》§5(6) 的模板 JSON 主体）。
 * mu/sigma 与 FeatureOrder 一一对应；relArea 单独存均值/标准差便于门禁。
 */
data class TemplateFeatureSet(
    val featureOrder: List<String>,
    val mu: DoubleArray,
    val sigma: DoubleArray,
    val sampleCount: Int,
    val relAreaMean: Double,
    val relAreaSigma: Double,
    val tAccept: Double,
    val tUnknown: Double,
)

/**
 * 模板构建与校准：把注册样本（多张照片、每张可有多颗）压成统计量，
 * 并用留一法估计同类距离的 P95，得到每药一份的 T_accept。
 */
object TemplateFeatures {

    /** 少于 3 个有效实例时不建议出模板（docs/18：2–3 张照片、累计 ≥3 颗）。 */
    const val MIN_SAMPLES = 3

    /** T_accept = max(P95(D_in) × 1.15, 0.8)；上限 3.0，避免样本过散时阈值离谱。 */
    const val ACCEPT_FACTOR = 1.15
    const val ACCEPT_FLOOR = 0.8
    const val ACCEPT_CEIL = 3.0
    const val UNKNOWN_FACTOR = 1.15

    /**
     * @param samples 每张注册照片的观测列表（一次拍照一组）。
     * relArea 在「同一张照片内」用中位面积归一化，跨照片量纲才一致。
     */
    fun fromSamples(samples: List<List<PillObservation>>): TemplateFeatureSet? {
        val features = ArrayList<PillFeature>()
        for (group in samples) {
            val median = PillMath.median(group.map { it.area })
            if (median <= 0.0) continue
            for (obs in group) {
                if (obs.isClump) continue
                features.add(PillFeature.from(obs, obs.area / median))
            }
        }
        return fromFeatures(features)
    }

    fun fromFeatures(features: List<PillFeature>): TemplateFeatureSet? {
        if (features.size < MIN_SAMPLES) return null
        val n = FeatureOrder.COUNT
        val mu = DoubleArray(n)
        for (f in features) for (i in 0 until n) mu[i] += f.values[i]
        for (i in 0 until n) mu[i] /= features.size
        val sigma = DoubleArray(n)
        for (f in features) for (i in 0 until n) {
            val d = f.values[i] - mu[i]
            sigma[i] += d * d
        }
        for (i in 0 until n) sigma[i] = sqrt(sigma[i] / features.size)

        val relAreas = features.map { it.values[8] }
        val relMean = relAreas.average()
        val relSigma = if (relAreas.size < 2) 0.0 else sqrt(
            relAreas.sumOf { val d = it - relMean; d * d } / relAreas.size
        )

        val distances = leaveOneOutDistances(features)
        val p95 = percentile(distances, 0.95)
        val tAccept = minOf(maxOf(p95 * ACCEPT_FACTOR, ACCEPT_FLOOR), ACCEPT_CEIL)
        val tUnknown = tAccept * UNKNOWN_FACTOR
        return TemplateFeatureSet(
            featureOrder = FeatureOrder.names,
            mu = mu,
            sigma = sigma,
            sampleCount = features.size,
            relAreaMean = relMean,
            relAreaSigma = relSigma,
            tAccept = tAccept,
            tUnknown = tUnknown,
        )
    }

    /** 留一法：每颗对「其余样本」的 μ/σ 求距离，衡量同类散度。 */
    fun leaveOneOutDistances(features: List<PillFeature>): List<Double> {
        if (features.size < 2) return emptyList()
        val n = FeatureOrder.COUNT
        val total = DoubleArray(n)
        for (f in features) for (i in 0 until n) total[i] += f.values[i]
        val out = ArrayList<Double>(features.size)
        for (idx in features.indices) {
            val rest = features.size - 1
            val mu = DoubleArray(n) { i -> (total[i] - features[idx].values[i]) / rest }
            val sigma = sigmaOf(features, skip = idx, mu = mu)
            out.add(FeatureDistance.distance(features[idx].values, mu, sigma))
        }
        return out
    }

    private fun sigmaOf(features: List<PillFeature>, skip: Int, mu: DoubleArray): DoubleArray {
        val n = FeatureOrder.COUNT
        val rest = features.size - 1
        val out = DoubleArray(n)
        for (j in features.indices) {
            if (j == skip) continue
            for (i in 0 until n) {
                val d = features[j].values[i] - mu[i]
                out[i] += d * d
            }
        }
        for (i in 0 until n) out[i] = sqrt(out[i] / rest.coerceAtLeast(1))
        return out
    }

    /** 最近秩百分位（输入无需排序）。 */
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p.coerceIn(0.0, 1.0)).toInt()
        return sorted[idx]
    }

    /** 两种药的模板距离（用于「容易混淆」预警）；σ 取两者较大值，避免一方过敏。 */
    fun similarity(a: TemplateFeatureSet, b: TemplateFeatureSet): Double {
        val sigma = DoubleArray(FeatureOrder.COUNT) { i -> max(a.sigma[i], b.sigma[i]) }
        return FeatureDistance.distance(a.mu, b.mu, sigma)
    }
}
