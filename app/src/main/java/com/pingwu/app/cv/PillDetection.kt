package com.pingwu.app.cv

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * 药丸检测入口（所有页面统一走这里）：
 *
 * 1) 首选训练好的 ONNX 检测器（[PillOnnxDetector]，YOLOX-Nano 640×640）——
 *    对木桌纹理、日常杂乱背景更稳，胶囊即使被拆成多个框也会先合并回一颗；
 * 2) 模型缺失 / 加载失败 / 推理抛异常时自动退回经典 CV 管线（[PillDetector]），
 *    保证没有模型也能用（降级但不崩）；
 * 3) ONNX 正常返回「0 颗」是有效结论，不做降级——否则会掩盖真实漏检，
 *    反而让经典管线把木纹数成药丸。
 *
 * 两条路线的返回类型相同（[DetectResult]），上层判定/模板匹配代码完全不用改。
 */
object PillDetection {

    private const val TAG = "PillDetection"

    /** 连续失败这么多次就本进程彻底停用 ONNX，避免每张图都卡一下再回退。 */
    private const val MAX_ONNX_FAILURES = 3

    @Volatile
    private var onnxEnabled = true

    @Volatile
    private var onnxFailures = 0

    @Volatile
    private var usingOnnx = false

    /** 最近一次检测实际用的引擎（诊断/日志用）。 */
    val lastEngine: String
        get() = if (usingOnnx) "onnx" else "classic"

    fun detect(context: Context, bitmap: Bitmap, sideMarginFraction: Float = 0f): DetectResult {
        if (onnxEnabled) {
            val attempt = runCatching {
                PillOnnxDetector.detect(context.applicationContext, bitmap, sideMarginFraction)
            }
            val result = attempt.getOrNull()
            if (result != null) {
                onnxFailures = 0
                usingOnnx = true
                return result
            }
            onnxFailures += 1
            Log.w(TAG, "ONNX 检测不可用（第 " + onnxFailures + " 次）", attempt.exceptionOrNull())
            if (onnxFailures >= MAX_ONNX_FAILURES) {
                onnxEnabled = false
                Log.w(TAG, "ONNX 连续失败，本进程改用经典 CV")
            }
        }
        usingOnnx = false
        return PillDetector.detect(bitmap, sideMarginFraction)
    }
}
