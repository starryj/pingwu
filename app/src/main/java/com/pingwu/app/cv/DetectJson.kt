package com.pingwu.app.cv

import com.pingwu.app.domain.verify.ClassifiedPill

/** EVIDENCE.detectJson 的编码：只存复盘需要的信息，不存图片本身。 */
object DetectJson {

    fun encode(result: DetectResult, pills: List<ClassifiedPill>): String {
        val sb = StringBuilder(256)
        sb.append("{\"count\":").append(result.count)
        sb.append(",\"clumps\":").append(result.clumps)
        sb.append(",\"medianArea\":").append(result.medianArea)
        sb.append(",\"threshold\":").append(result.threshold)
        sb.append(",\"image\":[").append(result.imageWidth).append(',').append(result.imageHeight).append(']')
        sb.append(",\"pills\":[")
        pills.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"drugId\":").append(p.drugId ?: 0L)
            sb.append(",\"conf\":").append(p.confidence)
            sb.append(",\"clump\":").append(if (p.isClump) 1 else 0)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 分药拍摄的一张证据：这一味药 + 这一张照片的检测结果。 */
    data class SplitShot(
        val drugId: Long,
        val drugName: String,
        val count: Int,
        val confidence: Double,
        val photoPath: String,
    )

    /** 分药拍摄的汇总 JSON：验证页逐张拍完后一次写入 evidence.detectJson。 */
    fun encodeSplit(shots: List<SplitShot>): String {
        val sb = StringBuilder(256)
        sb.append("{\"split\":true,\"shots\":[")
        shots.forEachIndexed { i, shot ->
            if (i > 0) sb.append(',')
            sb.append("{\"drugId\":").append(shot.drugId)
            sb.append(",\"name\":").append(quote(shot.drugName))
            sb.append(",\"count\":").append(shot.count)
            sb.append(",\"conf\":").append(shot.confidence)
            sb.append(",\"photo\":").append(quote(shot.photoPath))
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 手写 JSON 字符串转义，避免单测里依赖 Android 的 org.json 实现。 */
    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
