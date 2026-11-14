package com.pingwu.app.cv

/**
 * 模板 JSON 编解码（对应《02》§5(6) 的 PILL_TEMPLATE.featuresJson）。
 * 自己写的小编解码器：不依赖 org.json，纯 JVM 可单测，也避免 Android 单测里 org.json 是空桩。
 * 只处理本 App 自己的固定结构，不做通用 JSON。
 */
object TemplateJson {

    fun encode(t: TemplateFeatureSet): String {
        val sb = StringBuilder(512)
        sb.append("{\"featureOrder\":[")
        t.featureOrder.forEachIndexed { i, name ->
            if (i > 0) sb.append(',')
            sb.append('"').append(name).append('"')
        }
        sb.append("],\"mu\":[")
        appendDoubles(sb, t.mu)
        sb.append("],\"sigma\":[")
        appendDoubles(sb, t.sigma)
        sb.append("],\"sampleCount\":").append(t.sampleCount)
        sb.append(",\"relAreaMean\":").append(t.relAreaMean)
        sb.append(",\"relAreaSigma\":").append(t.relAreaSigma)
        sb.append(",\"tAccept\":").append(t.tAccept)
        sb.append(",\"tUnknown\":").append(t.tUnknown)
        sb.append('}')
        return sb.toString()
    }

    fun decode(json: String?): TemplateFeatureSet? {
        if (json.isNullOrBlank()) return null
        val order = strings(json, "featureOrder") ?: return null
        val mu = numbers(json, "mu") ?: return null
        val sigma = numbers(json, "sigma") ?: return null
        if (mu.size != FeatureOrder.COUNT || sigma.size != FeatureOrder.COUNT) return null
        val count = number(json, "sampleCount")?.toInt() ?: return null
        val relMean = number(json, "relAreaMean") ?: return null
        val relSigma = number(json, "relAreaSigma") ?: return null
        val tAccept = number(json, "tAccept") ?: return null
        val tUnknown = number(json, "tUnknown") ?: tAccept * TemplateFeatures.UNKNOWN_FACTOR
        return TemplateFeatureSet(order, mu, sigma, count, relMean, relSigma, tAccept, tUnknown)
    }

    private fun appendDoubles(sb: StringBuilder, values: DoubleArray) {
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            sb.append(v)
        }
    }

    private fun numbers(json: String, key: String): DoubleArray? {
        val m = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]").find(json) ?: return null
        val body = m.groupValues[1].trim()
        if (body.isEmpty()) return DoubleArray(0)
        return runCatching { body.split(',').map { it.trim().toDouble() }.toDoubleArray() }.getOrNull()
    }

    private fun strings(json: String, key: String): List<String>? {
        val m = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]").find(json) ?: return null
        val body = m.groupValues[1].trim()
        if (body.isEmpty()) return emptyList()
        return body.split(',').map { it.trim().trim('"') }
    }

    private fun number(json: String, key: String): Double? {
        val m = Regex("\"$key\"\\s*:\\s*([-0-9.eE+]+)").find(json) ?: return null
        return m.groupValues[1].toDoubleOrNull()
    }
}
