package com.pingwu.app.cv

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 拍摄质量门禁的问题（能直接对应一句具体文案，见 ui/capture/QualityText.kt）。 */
enum class QualityIssue {
    /** 画面分辨率太低（长边不足），药丸细节不够，检测不可信。 */
    TOO_SMALL,

    /** 太暗：镜头挡住、没开灯，分割和比对都会失准。 */
    TOO_DARK,

    /** 太亮：直射光/反光把药丸边缘冲掉了。 */
    TOO_BRIGHT,

    /** 几乎没有明暗差异：整幅一个颜色（镜头被手指挡住是典型情况）。 */
    LOW_CONTRAST,
}

/** 给用户看的中文说明，避免把枚举名直接显示出来。 */
val QualityIssue.label: String
    get() = when (this) {
        QualityIssue.TOO_SMALL -> "画面太小"
        QualityIssue.TOO_DARK -> "太暗"
        QualityIssue.TOO_BRIGHT -> "太亮"
        QualityIssue.LOW_CONTRAST -> "几乎没有明暗变化"
    }

/**
 * 一次拍摄质量检查的结果。
 *
 * meanLuma / stdLuma / edgeRatio 只是诊断值（debug 日志与后续真机校准用），不参与放行判定。
 */
data class QualityReport(
    val ok: Boolean,
    val issue: QualityIssue? = null,
    val meanLuma: Double = 0.0,
    val stdLuma: Double = 0.0,
    val edgeRatio: Double = 0.0,
)

/**
 * 拍摄质量门禁（《02》§5.3(8) 的前四条里可以在 v1 直接挡住的那些）。
 *
 * 原则：**只挡「明显拍错」的照片**，阈值刻意保守——误拒也要重拍，宁可不挡也不能
 * 把正常照片挡在门外（NFR-3 误拒 ≤5%）。所以：
 * - 尺寸：长边 < 1280 才拦（拍照解码目标长边 1600，正常不会触发）；
 * - 曝光：均值 < 25（近似全黑）或 > 243（近似全白）；
 * - 清晰度：**不在这里拦**。亮度标准差的比值判据对「药丸占画面比例」太敏感
 *   （同样清晰的照片，一颗小药丸和大药丸算出来的边缘能量差一倍以上），
 *   没法在没有真机样本时定一个安全的阈值。模糊检测列进 M7 真机校准待办，
 *   现在先把「检测不到/数量不对」的具体原因交给 VerdictEngine。
 */
object ImageQuality {

    /** 药丸检测对长边的要求（与 PillDetector.MAX_LONG_EDGE 对齐）。 */
    const val MIN_LONG_EDGE = 1280

    /** 质量检查把画面缩到这个长边再算统计量：够判断明暗，省内存。 */
    const val ANALYSIS_LONG_EDGE = 160

    const val MIN_MEAN_LUMA = 25.0
    const val MAX_MEAN_LUMA = 243.0

    /**  Uniform 画面的标准差阈值：只拦「几乎一个颜色」的废片。 */
    const val MIN_STD_LUMA = 3.0

    /** 实例贴边判定（像素，检测图空间）：轮廓外接框进入画面边缘这么近就算切了。 */
    const val EDGE_TOUCH_MARGIN = 2f

    fun evaluate(bitmap: Bitmap): QualityReport {
        resolutionIssue(bitmap.width, bitmap.height)?.let { return QualityReport(ok = false, issue = it) }
        val longEdge = max(bitmap.width, bitmap.height)
        val factor = ANALYSIS_LONG_EDGE.toDouble() / longEdge
        val w = max(8, (bitmap.width * factor).roundToInt())
        val h = max(8, (bitmap.height * factor).roundToInt())
        val small = if (w == bitmap.width && h == bitmap.height) bitmap else bitmap.scale(w, h)
        val pixels = IntArray(w * h)
        try {
            small.getPixels(pixels, 0, w, 0, 0, w, h)
        } finally {
            if (small !== bitmap && !small.isRecycled) small.recycle()
        }
        return evaluateGrayscale(lumaOf(pixels), w, h)
    }

    /** 纯 JVM 可测：输入 0..255 的灰度数组。 */
    internal fun evaluateGrayscale(luma: IntArray, width: Int, height: Int): QualityReport {
        if (luma.isEmpty() || width <= 0 || height <= 0) {
            return QualityReport(ok = false, issue = QualityIssue.TOO_SMALL)
        }
        var sum = 0.0
        for (v in luma) sum += v
        val mean = sum / luma.size
        if (mean < MIN_MEAN_LUMA) {
            return QualityReport(ok = false, issue = QualityIssue.TOO_DARK, meanLuma = mean)
        }
        if (mean > MAX_MEAN_LUMA) {
            return QualityReport(ok = false, issue = QualityIssue.TOO_BRIGHT, meanLuma = mean)
        }
        var variance = 0.0
        for (v in luma) {
            val d = v - mean
            variance += d * d
        }
        val std = sqrt(variance / luma.size)
        val edge = meanAbsLaplacian(luma, width, height)
        if (std < MIN_STD_LUMA) {
            return QualityReport(
                ok = false,
                issue = QualityIssue.LOW_CONTRAST,
                meanLuma = mean,
                stdLuma = std,
                edgeRatio = if (std > 0.0) edge / std else 0.0,
            )
        }
        return QualityReport(
            ok = true,
            meanLuma = mean,
            stdLuma = std,
            edgeRatio = edge / std,
        )
    }

    /** 只做分辨率检查（纯函数，便于单测）。 */
    internal fun resolutionIssue(width: Int, height: Int): QualityIssue? {
        if (width <= 0 || height <= 0) return QualityIssue.TOO_SMALL
        return if (max(width, height) < MIN_LONG_EDGE) QualityIssue.TOO_SMALL else null
    }

    /**
     * 有没有药丸被画面边缘切掉。
     *
     * 切掉的药丸仍然会被数成一颗（轮廓不完整、特征失真），是最容易「误放行」的形态之一；
     * 这里直接拦住，要求重拍。判定用外接框，留 [EDGE_TOUCH_MARGIN] 像素容差。
     */
    internal fun touchesFrame(
        observations: List<PillObservation>,
        width: Int,
        height: Int,
        margin: Float = EDGE_TOUCH_MARGIN,
    ): Boolean = observations.any { o ->
        val halfW = o.width / 2f
        val halfH = o.height / 2f
        o.centerX - halfW <= margin ||
            o.centerY - halfH <= margin ||
            o.centerX + halfW >= width - margin ||
            o.centerY + halfH >= height - margin
    }

    private fun lumaOf(pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
        val c = pixels[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun meanAbsLaplacian(luma: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = 4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width]
                sum += kotlin.math.abs(lap).toDouble()
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }
}
