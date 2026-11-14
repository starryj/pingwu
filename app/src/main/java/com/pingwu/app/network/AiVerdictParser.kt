package com.pingwu.app.network

/** 在线模型对一张照片的判定结果。 */
data class AiVerdict(
    val pass: Boolean,
    val reason: String,
    val count: Int? = null,
    val confidence: String? = null,
)

/**
 * 只用纯 Kotlin 解析模型返回的 JSON，避免依赖 Android 的 org.json，
 * 这样 JVM 单测可以直接跑。
 */
object AiVerdictParser {

    private val PASS = Regex("\"pass\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
    private val COUNT = Regex("\"count\"\\s*:\\s*(\\d+)")
    private val CONFIDENCE = Regex("\"confidence\"\\s*:\\s*\"([^\"]*)\"")
    private val REASON = Regex("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun parse(raw: String): AiVerdict? {
        val text = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = text.substring(start, end + 1)

        val pass = PASS.find(body)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: return null
        val reason = REASON.find(body)?.groupValues?.get(1)?.let(::unescape)?.trim()
            .orEmpty()
            .ifBlank { if (pass) "看起来对上了。" else "没对上。" }
        val count = COUNT.find(body)?.groupValues?.get(1)?.toIntOrNull()
        val confidence = CONFIDENCE.find(body)?.groupValues?.get(1)?.lowercase()
        return AiVerdict(pass = pass, reason = reason, count = count, confidence = confidence)
    }

    private fun unescape(input: String): String = buildString {
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                when (val next = input[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'r' -> append('\r')
                    '"' -> append('"')
                    '\\' -> append('\\')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
