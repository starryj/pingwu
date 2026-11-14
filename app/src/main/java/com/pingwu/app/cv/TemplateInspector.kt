package com.pingwu.app.cv

/**
 * 调试面板用的模板体检（《03》M4 任务 5「看特征距离」）。
 *
 * 注册时只用「新模板 vs 已有模板」做一次相似度检查；这里把库里所有在用模板两两比一遍，
 * 让开发者/真机验收时能直接看到哪两种药在特征空间里靠得太近——那是将来最容易误判的组合。
 * 纯 Kotlin，不依赖 Android，可在主机上单测。
 */
data class TemplateSnapshot(
    val drugId: Long,
    val name: String,
    val features: TemplateFeatureSet,
)

data class TemplatePair(
    val nameA: String,
    val nameB: String,
    val similarity: Double,
    val threshold: Double,
) {
    /** 距离小于阈值即「容易混淆」（与 RegistrationCheck 同一判据：1.5 × T_accept）。 */
    val similar: Boolean get() = similarity < threshold
}

object TemplateInspector {

    fun pairs(list: List<TemplateSnapshot>): List<TemplatePair> = buildList {
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                add(
                    TemplatePair(
                        nameA = a.name,
                        nameB = b.name,
                        similarity = TemplateFeatures.similarity(a.features, b.features),
                        threshold = RegistrationCheck.SIMILARITY_FACTOR *
                            minOf(a.features.tAccept, b.features.tAccept),
                    )
                )
            }
        }
    }
}
