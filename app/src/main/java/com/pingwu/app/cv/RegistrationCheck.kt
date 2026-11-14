package com.pingwu.app.cv

/**
 * 模板注册的纯逻辑校验（《02》§6 注册向导）：
 * ① 有效实例 < 3 → 样本不足；② 同类散度太大（留一法 P95 超过上限）→ 重拍；
 * ③ 与已有药品模板距离 < 1.5 × T_accept → 只是「颜色很像」的提示，不再拦保存。
 */
data class RegistrationOutcome(
    val features: TemplateFeatureSet?,
    val tooFewSamples: Boolean,
    val tooScattered: Boolean,
    val similarTo: List<String>,
    val rawP95: Double,
) {
    // similarTo 只作为提示：颜色接近说明验证时可能分不开，但仍允许保存（docs/18 P1）。
    val ok: Boolean get() = features != null && !tooFewSamples && !tooScattered
}

object RegistrationCheck {

    /** 同类散度上限（归一化距离单位）；真机样本校准后回填。 */
    const val MAX_SPREAD = 0.6

    /** 「容易混淆」的阈值倍数：距离 < 1.5 × T_accept。 */
    const val SIMILARITY_FACTOR = 1.5

    fun evaluate(
        samples: List<List<PillObservation>>,
        others: List<Pair<String, TemplateFeatureSet>> = emptyList(),
        maxSpread: Double = MAX_SPREAD,
    ): RegistrationOutcome {
        val flat = ArrayList<PillFeature>()
        for (group in samples) {
            val median = PillMath.median(group.map { it.area })
            if (median <= 0.0) continue
            for (obs in group) {
                if (obs.isClump) continue
                flat.add(PillFeature.from(obs, obs.area / median))
            }
        }
        if (flat.size < TemplateFeatures.MIN_SAMPLES) {
            return RegistrationOutcome(null, tooFewSamples = true, tooScattered = false, similarTo = emptyList(), rawP95 = 0.0)
        }
        val features = TemplateFeatures.fromFeatures(flat)
            ?: return RegistrationOutcome(null, tooFewSamples = true, tooScattered = false, similarTo = emptyList(), rawP95 = 0.0)
        val rawP95 = TemplateFeatures.percentile(TemplateFeatures.leaveOneOutDistances(flat), 0.95)
        val similar = others
            .filter { (_, other) ->
                TemplateFeatures.similarity(features, other) < SIMILARITY_FACTOR * features.tAccept
            }
            .map { it.first }
        return RegistrationOutcome(
            features = features,
            tooFewSamples = false,
            tooScattered = rawP95 > maxSpread,
            similarTo = similar,
            rawP95 = rawP95,
        )
    }
}
