package com.pingwu.app.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.scale
import com.pingwu.app.domain.verify.ClassifiedPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 证据照片标注（验收 A5）：在缩到长边 ≤1600 的照片上画检测框、药名、未知/粘连标记和顶部信息条。
 *
 * 只把标注后的「证据副本」落盘：原图检测后即释放，证据图既方便回看，也避免几 MB 原图占满空间。
 * 坐标来自 [DetectResult]（长边 1280 的检测图），这里按 out.width / result.imageWidth 等比缩放。
 */
object EvidenceRenderer {

    const val MAX_LONG_EDGE = 1600

    private val KNOWN = Color.rgb(0, 150, 136)
    private val UNKNOWN = Color.rgb(211, 47, 47)
    private val CLUMP = Color.rgb(245, 124, 0)
    private val SCRIM = Color.argb(190, 0, 0, 0)
    private val LABEL_BG = Color.argb(210, 0, 0, 0)

    /** 生成可标注的可变副本；长边超限时先缩放。 */
    fun prepare(photo: Bitmap, maxLongEdge: Int = MAX_LONG_EDGE): Bitmap {
        val longEdge = max(photo.width, photo.height)
        if (longEdge <= maxLongEdge) {
            return photo.copy(Bitmap.Config.ARGB_8888, true)
        }
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val w = max(1, (photo.width * scale).toInt())
        val h = max(1, (photo.height * scale).toInt())
        val scaled = photo.scale(w, h)
        return if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * 画检测框与标签，返回新的证据图（不修改入参）。
     * @param names drugId → 药名
     * @param passText 顶部信息条里的结论文案（如「验证通过」）
     * @param unknownLabel 未知药丸标签
     * @param clumpLabel 粘连标签
     */
    fun annotate(
        photo: Bitmap,
        result: DetectResult,
        pills: List<ClassifiedPill>,
        names: Map<Long, String>,
        capturedAt: Long,
        passText: String,
        unknownLabel: String,
        clumpLabel: String,
    ): Bitmap {
        val out = prepare(photo)
        if (result.imageWidth <= 0 || result.imageHeight <= 0) return out
        val sx = out.width.toFloat() / result.imageWidth.toFloat()
        val sy = out.height.toFloat() / result.imageHeight.toFloat()
        val unit = max(20f, out.width * 0.026f)
        val boxStroke = max(3f, out.width * 0.0035f)
        val canvas = Canvas(out)

        val under = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = boxStroke * 2.0f
            color = Color.argb(200, 0, 0, 0)
        }
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = boxStroke
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit
            typeface = Typeface.DEFAULT_BOLD
        }
        val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = LABEL_BG
        }

        for (i in result.observations.indices) {
            val obs = result.observations[i]
            val pill = pills.getOrNull(i)
            val color = when {
                pill?.isClump == true -> CLUMP
                pill?.drugId == null -> UNKNOWN
                else -> KNOWN
            }
            val halfW = obs.width * sx / 2f
            val halfH = obs.height * sy / 2f
            val cx = obs.centerX * sx
            val cy = obs.centerY * sy
            val rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            canvas.drawRect(rect, under)
            box.color = color
            canvas.drawRect(rect, box)
            val label = when {
                pill?.isClump == true -> clumpLabel
                pill?.drugId == null -> unknownLabel
                else -> names[pill.drugId]?.take(10) ?: "?"
            }
            drawLabel(canvas, label, rect, labelPaint, labelBg, unit, out.width, out.height)
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(capturedAt))
        val banner = stamp + "  ·  " + passText
        val bannerH = unit * 2.1f
        canvas.drawRect(0f, 0f, out.width.toFloat(), bannerH, Paint().apply { color = SCRIM })
        labelPaint.textSize = unit * 0.95f
        val baseline = bannerH * 0.68f
        canvas.drawText(banner, unit * 0.6f, baseline, labelPaint)
        return out
    }

    private fun drawLabel(
        canvas: Canvas,
        text: String,
        rect: RectF,
        paint: Paint,
        bg: Paint,
        textSize: Float,
        width: Int,
        height: Int,
    ) {
        if (text.isEmpty()) return
        val pad = textSize * 0.28f
        val textWidth = paint.measureText(text)
        val boxH = textSize * 1.5f
        var left = rect.left
        var top = rect.top - boxH
        if (top < 0f) top = rect.bottom
        if (left + textWidth + pad * 2f > width) left = width - textWidth - pad * 2f
        if (left < 0f) left = 0f
        if (top + boxH > height) top = height - boxH
        canvas.drawRect(left, top, left + textWidth + pad * 2f, top + boxH, bg)
        canvas.drawText(text, left + pad, top + boxH * 0.72f, paint)
    }
}
