package com.pingwu.app.cv

import com.pingwu.app.domain.verify.ClassifiedPill
import com.pingwu.app.domain.verify.ColorObservation
import com.pingwu.app.domain.verify.Confidence
import com.pingwu.app.domain.verify.ShapeSignature

/** 一种药的模板（带展示名，便于结果页出具体文案）。 */
data class TemplateEntry(
    val drugId: Long,
    val name: String,
    val features: TemplateFeatureSet,
)

/**
 * 模板匹配（《02》§5(5)(7) / 《18》§3）：
 * ① 粘连实例不分类，原样标成 isClump；
 * ② 颜色判定不再让每颗药各自挑最近模板，而是先算出「到每个药种的颜色距离」，
 *    交给 [com.pingwu.app.domain.verify.VerdictEngine.judgeColor] 按计划颗数做整体最优分配。
 */
object TemplateMatcher {

    fun classify(
        obs: PillObservation,
        relArea: Double,
        templates: List<TemplateEntry>,
    ): ClassifiedPill {
        if (obs.isClump) return ClassifiedPill(drugId = null, confidence = 0.0, isClump = true)
        if (templates.isEmpty()) return ClassifiedPill(drugId = null, confidence = 0.0)
        val feature = PillFeature.from(obs, relArea)
        val ranked = templates
            .map { it to FeatureDistance.distance(feature, it.features.mu, it.features.sigma) }
            .sortedBy { it.second }
        val best = ranked.first()
        if (com.pingwu.app.BuildConfig.DEBUG) {
            android.util.Log.d(
                "TplMatch",
                "feat=" + feature.values.joinToString(",") { String.format("%.4f", it) } +
                    " ranked=" + ranked.joinToString(";") {
                        it.first.name + ":" + String.format("%.3f", it.second)
                    } +
                    " tUnknown=" + String.format("%.3f", best.first.features.tUnknown),
            )
        }
        if (best.second > best.first.features.tUnknown) {
            return ClassifiedPill(drugId = null, confidence = 0.0)
        }
        val second = ranked.getOrNull(1)?.second
        val confidence = Confidence.score(best.second, best.first.features.tAccept, second)
        return ClassifiedPill(drugId = best.first.drugId, confidence = confidence)
    }

    /** 一颗药丸到每个药种模板的颜色距离（drugId → 距离，越小越像）。 */
    fun distances(
        obs: PillObservation,
        relArea: Double,
        templates: List<TemplateEntry>,
    ): Map<Long, Double> {
        if (templates.isEmpty()) return emptyMap()
        val feature = PillFeature.from(obs, relArea)
        val out = LinkedHashMap<Long, Double>(templates.size)
        for (template in templates) {
            out[template.drugId] =
                FeatureDistance.distance(feature, template.features.mu, template.features.sigma)
        }
        return out
    }

    /**
     * 模板的粗形状签名（《18》§5 P3）：取注册样本 aspect / circularity 的均值。
     * 模板里没有这两项时返回 null（不做形状兜底）。
     */
    fun shapeSignature(template: TemplateEntry): ShapeSignature? {
        val features = template.features
        val aspectIndex = features.featureOrder.indexOf("aspect")
        if (aspectIndex < 0) return null
        val aspect = features.mu.getOrNull(aspectIndex) ?: return null
        val circularityIndex = features.featureOrder.indexOf("circularity")
        val circularity = if (circularityIndex >= 0) {
            features.mu.getOrNull(circularityIndex) ?: 0.0
        } else {
            0.0
        }
        return ShapeSignature(aspect = aspect, circularity = circularity)
    }

    /**
     * 把一次检测结果转成判定层要的「颜色距离表」。
     * relArea 仍按全图中位面积算：颜色权重已归零，relArea 只用于日志/兼容旧特征向量。
     * 同时带上粗形状签名（aspect / circularity），供颜色分得清时的形状兜底（P3）。
     */
    fun colorObservations(
        result: DetectResult,
        templates: List<TemplateEntry>,
    ): List<ColorObservation> {
        val median = result.medianArea
        return result.observations.map { obs ->
            if (obs.isClump) {
                ColorObservation(distances = emptyMap(), isClump = true)
            } else {
                val relArea = if (median > 0.0) obs.area / median else 1.0
                val feature = PillFeature.from(obs, relArea)
                val distances = LinkedHashMap<Long, Double>(templates.size)
                for (template in templates) {
                    distances[template.drugId] =
                        FeatureDistance.distance(feature, template.features.mu, template.features.sigma)
                }
                ColorObservation(
                    distances = distances,
                    shape = ShapeSignature(
                        aspect = feature.values[1],
                        circularity = feature.values[0],
                    ),
                )
            }
        }
    }
}
