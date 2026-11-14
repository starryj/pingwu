package com.pingwu.app.network

/** 多件一起拍时，其中一件的判定结果。 */
data class TargetVerdict(
    /** 从 1 开始，对应登记照的顺序。 */
    val index: Int,
    val pass: Boolean,
    val count: Int? = null,
    val reason: String = "",
)

/**
 * 解析「一次拍好几件」的判定结果：
 * `{"results":[{"index":1,"pass":true,"count":1,"reason":"…"}, …]}`
 *
 * 和 [AiVerdictParser] 一样只用纯 Kotlin 解析，方便 JVM 单测直接跑。
 * 模型漏答某一件时补一条 pass=false，不让一件没答就整次失败。
 */
object MultiVerdictParser {

    private val INDEX = Regex("\"index\"\\s*:\\s*(\\d+)")
    private val PASS = Regex("\"pass\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
    private val COUNT = Regex("\"count\"\\s*:\\s*(\\d+)")
    private val REASON = Regex("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun parse(raw: String, expected: Int): List<TargetVerdict>? {
        if (expected <= 0) return emptyList()
        val blocks = objectBlocks(stripFence(raw))
        if (blocks.isEmpty()) return null

        val found = LinkedHashMap<Int, TargetVerdict>()
        blocks.forEachIndexed { position, block ->
            val pass = PASS.find(block)?.groupValues?.get(1)?.toBooleanStrictOrNull()
                ?: return@forEachIndexed
            val index = INDEX.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: (position + 1)
            val reason = REASON.find(block)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
            val count = COUNT.find(block)?.groupValues?.get(1)?.toIntOrNull()
            if (index in 1..expected && index !in found) {
                found[index] = TargetVerdict(index = index, pass = pass, count = count, reason = reason)
            }
        }
        if (found.isEmpty()) return null
        return (1..expected).map { i ->
            found[i] ?: TargetVerdict(i, pass = false, reason = "模型没有回答这一件")
        }
    }

    private fun stripFence(raw: String): String = raw.trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    /** 只切结果数组里第一层的 `{…}`，够用且不依赖 org.json。 */
    private fun objectBlocks(text: String): List<String> {
        val from = text.indexOf('[').let { if (it >= 0) it else text.indexOf('{') }
        if (from < 0) return emptyList()
        val blocks = ArrayList<String>()
        var depth = 0
        var start = -1
        for (i in from until text.length) {
            when (text[i]) {
                '[' -> if (depth == 0) depth = 1
                '{' -> {
                    if (start < 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (start >= 0 && depth <= 1) {
                        blocks += text.substring(start, i + 1)
                        start = -1
                    }
                    if (depth == 0) break
                }
                ']' -> if (depth == 1) break
            }
        }
        return blocks
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
