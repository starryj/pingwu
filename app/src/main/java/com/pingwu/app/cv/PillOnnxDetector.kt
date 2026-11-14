package com.pingwu.app.cv

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 训练好的药丸检测器（YOLOX-Nano，单类 "pill"，输入 640×640 BGR 0–255，不做 mean 减法）。
 *
 * 模型来源：ml/train_pill.py 训练 → ml/export_onnx.py 导出，
 * 放在 app/src/main/assets/pill_detector.onnx（自带解码头，输出 [1,8400,6]）。
 * 640 版（pill_ft7_640）比 416 版（pill_ft6）在贴太近的药丸上分得更开：
 * 47 张实拍 39/47（83.0%）、少算从 5 张降到 2 张、平均计数误差 0.30→0.23。
 * 推理用 App 已内置的 OpenCV DNN（org.opencv.dnn），不额外引第三方运行时；
 * 已在桌面 OpenCV 4.14 DNN 上验证与 onnxruntime 结果一致。
 *
 * 与经典 CV 管线的分工：ONNX 只负责「药丸在哪、有几颗、框多大」，
 * 每个框再在框内做一次局部 Lab 分割，算出与注册模板同一套 13 维特征
 * （圆度/长宽比/实心度/Hu 矩/平均颜色），这样模板匹配和判定逻辑完全不用改。
 */
object PillOnnxDetector {

    private const val TAG = "PillOnnx"

    /** 与 assets 里模型文件名一致；换模型只替换这个文件即可。 */
    const val ASSET_NAME = "pill_detector.onnx"

    const val INPUT_SIZE = 640

    /** 640 输入的 anchor 数：80×80 + 40×40 + 20×20。 */
    private const val ANCHORS = 8400

    /** letterbox 填充值：与训练/评估时的 pad 114 一致。 */
    private const val PAD_VALUE = 114.0

    // 下面四个参数在 2026-11-12 的 pill_ft7_640 上重调（用户实拍 47 张，4/5/6 颗）：
    // conf 0.55 / NMS 0.30 / merge contain 0.80 → 47 张对 39 张（83.0%），
    // 同时 named 21/21、val22 15/22、cc0val 10/22、合成 val exact 0.8675。
    // 换模型时要一起复核，别只换 asset。
    private const val CONF_THRESHOLD = 0.55f
    private const val NMS_IOU = 0.30f
    /** 胶囊被拆框时用聚类合并压回去（实测最优参数，见 PillBoxMath）。 */
    private const val MERGE_IOU = 0.40f
    private const val MERGE_CONTAIN = 0.80f

    /** 与经典管线保持一致：长边先缩到 1280 以内再算特征，控制耗时。 */
    private const val MAX_LONG_EDGE = PillDetector.MAX_LONG_EDGE

    /** 框外扩比例：留一圈背景，才能用「与背景的色差」把药丸轮廓切出来。 */
    private const val CROP_PAD_RATIO = 0.28

    /** 局部背景色差的绝对下限（Lab ΔE）：低于它的当背景纹理。比整幅管线宽松，因为框已经由模型给出。 */
    private const val MIN_FG_DELTA_E = 12.0

    /** Lab 距离放大倍数，与 PillDetector 一致（8U 存储）。 */
    private const val DIST_SCALE = 3.0

    @Volatile
    private var net: Net? = null

    @Volatile
    private var loadFailed = false

    private val lock = Any()

    /** 最近一次检测的原始框数（诊断用：合并前 / 合并后）。 */
    @Volatile
    var lastRawCount: Int = 0
        private set

    @Volatile
    var lastMergedCount: Int = 0
        private set

    /**
     * 加载模型（首次调用时把 asset 拷到 cacheDir 再读）。失败后本进程不再重试，交给 [PillDetection] 回退。
     */
    fun ensureLoaded(context: Context): Net? {
        net?.let { return it }
        if (loadFailed) return null
        synchronized(lock) {
            net?.let { return it }
            if (loadFailed) return null
            return try {
                if (!PillDetector.ensureInitialized()) error("OpenCV 初始化失败")
                val target = File(context.cacheDir, ASSET_NAME)
                val assetLength = runCatching {
                    context.assets.openFd(ASSET_NAME).use { it.length }
                }.getOrElse {
                    context.assets.open(ASSET_NAME).use { it.available().toLong() }
                }
                if (!target.exists() || target.length() != assetLength) {
                    context.assets.open(ASSET_NAME).use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                }
                val loaded = Dnn.readNetFromONNX(target.absolutePath)
                net = loaded
                Log.i(TAG, "ONNX 模型加载成功 size=" + target.length() / 1024 + "KB")
                loaded
            } catch (t: Throwable) {
                loadFailed = true
                Log.e(TAG, "ONNX 模型加载失败，改用经典 CV", t)
                null
            }
        }
    }

    /**
     * 跑一次 ONNX 检测。返回 null 表示模型不可用（加载失败/推理异常），调用方应回退经典管线。
     * 注意：正常返回 0 颗是有效结果，不代表失败。
     */
    fun detect(context: Context, bitmap: Bitmap, sideMarginFraction: Float = 0f): DetectResult? {
        val model = ensureLoaded(context) ?: return null
        synchronized(lock) {
            val rgba = Mat()
            val bgr = Mat()
            val scaled = Mat()
            val letterboxed = Mat()
            val lab = Mat()
            val resized = Mat()
            var roi: Mat? = null
            var blob: Mat? = null
            var output: Mat? = null
            var rows: Mat? = null
            try {
                Utils.bitmapToMat(bitmap, rgba)
                if (rgba.channels() == 4) {
                    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                } else {
                    rgba.copyTo(bgr)
                }
                val longEdge = max(bgr.cols(), bgr.rows())
                val scale = if (longEdge > MAX_LONG_EDGE) MAX_LONG_EDGE.toDouble() / longEdge else 1.0
                if (scale < 1.0) {
                    Imgproc.resize(bgr, scaled, Size(), scale, scale, Imgproc.INTER_AREA)
                } else {
                    bgr.copyTo(scaled)
                }
                val w = scaled.cols()
                val h = scaled.rows()
                if (w < 32 || h < 32) return emptyResult(w, h)

                Imgproc.cvtColor(scaled, lab, Imgproc.COLOR_BGR2Lab)

                // letterbox：等比缩到 640 内，右下 pad 114（与训练/评估预处理一致）
                val r = min(INPUT_SIZE.toDouble() / w, INPUT_SIZE.toDouble() / h)
                val nw = max(1, (w * r).roundToInt())
                val nh = max(1, (h * r).roundToInt())
                val resizedMat = resized
                Imgproc.resize(scaled, resizedMat, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                letterboxed.create(INPUT_SIZE, INPUT_SIZE, CvType.CV_8UC3)
                letterboxed.setTo(Scalar(PAD_VALUE, PAD_VALUE, PAD_VALUE))
                val roiMat = letterboxed.submat(0, nh, 0, nw)
                resizedMat.copyTo(roiMat)
                roiMat.release()

                // 关键：mean 必须传 0。模型是在 BGR 0–255 原始值上训练的，
                // 这里若把 pad 值 114 当 mean 传进去，blobFromImage 会做 (img-114)，
                // 输入整体偏移，颗数会明显变差（实测 21 张实拍从 21/21 掉到 19/21）。
                blob = Dnn.blobFromImage(
                    letterboxed,
                    1.0,
                    Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()),
                    Scalar(0.0, 0.0, 0.0),
                    false,
                    false,
                    CvType.CV_32F,
                )
                model.setInput(blob)
                output = model.forward()

                val flat = FloatArray(ANCHORS * 6)
                rows = output.reshape(1, ANCHORS)
                val row = FloatArray(6)
                for (i in 0 until ANCHORS) {
                    rows.get(i, 0, row)
                    System.arraycopy(row, 0, flat, i * 6, 6)
                }
                val coordScale = (1.0 / r).toFloat()
                val raw = PillBoxMath.decode(flat, ANCHORS, coordScale, CONF_THRESHOLD)
                lastRawCount = raw.size
                val nms = PillBoxMath.nms(raw, NMS_IOU)
                val merged = PillBoxMath.clusterMerge(nms, MERGE_IOU, MERGE_CONTAIN)
                lastMergedCount = merged.size

                // 参照物模式：只保留落在中心区域的框（边缘留给硬币/卡/角标等参照物）
                val mx = w * sideMarginFraction
                val my = h * sideMarginFraction
                val boxes = merged.filter { b ->
                    b.cx >= mx && b.cx <= w - mx && b.cy >= my && b.cy <= h - my
                }

                val raws = ArrayList<RawObservation>(boxes.size)
                for (box in boxes) {
                    raws.add(observe(lab, w, h, box) ?: boxFallback(lab, w, h, box))
                }
                val medianArea = PillMath.median(raws.map { it.area })
                val observations = raws.map { it.toObservation(medianArea) }
                Log.d(
                    TAG,
                    "detect raw=" + lastRawCount + " merged=" + lastMergedCount +
                        " kept=" + observations.size + " size=" + w + "x" + h,
                )
                return DetectResult(
                    count = observations.size,
                    clumps = observations.count { it.isClump },
                    observations = observations,
                    medianArea = medianArea,
                    threshold = CONF_THRESHOLD.toDouble(),
                    imageWidth = w,
                    imageHeight = h,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "ONNX 推理失败", t)
                return null
            } finally {
                rgba.release()
                bgr.release()
                scaled.release()
                letterboxed.release()
                lab.release()
                resized.release()
                roi?.release()
                blob?.release()
                output?.release()
                rows?.release()
            }
        }
    }

    private fun emptyResult(width: Int, height: Int): DetectResult = DetectResult(
        count = 0,
        clumps = 0,
        observations = emptyList(),
        medianArea = 0.0,
        threshold = CONF_THRESHOLD.toDouble(),
        imageWidth = width,
        imageHeight = height,
    )

    /** 一个框切出来的原始特征（还没算 isClump，因为要等整图中位面积）。 */
    private class RawObservation(
        val cx: Float,
        val cy: Float,
        val area: Double,
        val width: Int,
        val height: Int,
        val perimeter: Double,
        val solidity: Double,
        /** 最小外接旋转矩形的长/短边比（与摆放角度无关）。 */
        val aspect: Double,
        /** 面积 / 最小外接旋转矩形面积（与摆放角度无关）。 */
        val extent: Double,
        val hu: DoubleArray,
        val colorLab: DoubleArray,
        val points: List<FloatArray>,
    ) {
        fun toObservation(medianArea: Double): PillObservation = PillObservation(
            centerX = cx,
            centerY = cy,
            area = area,
            width = width,
            height = height,
            aspect = aspect,
            circularity = PillMath.circularity(area, perimeter),
            solidity = solidity,
            extent = extent,
            colorLab = colorLab,
            hu = hu,
            points = points,
            isClump = PillMath.isClump(area, medianArea, solidity),
        )
    }

    /**
     * 在一个检测框内做局部 Lab 分割，取最大轮廓算特征：
     * 框外扩一圈 → 用边界均值当背景色 → 色差图 Otsu + 绝对下限 → 形态学 → 外轮廓。
     * 这样得到的圆度/实心度/Hu 矩与注册模板用的是同一套算法，模板比对无需改动。
     */
    private fun observe(lab: Mat, imageW: Int, imageH: Int, box: DetBox): RawObservation? {
        val bw = box.width.toInt()
        val bh = box.height.toInt()
        if (bw < 8 || bh < 8) return null
        val pad = max(4, (max(bw, bh) * CROP_PAD_RATIO).toInt())
        val x0 = max(0, box.x1.toInt() - pad)
        val y0 = max(0, box.y1.toInt() - pad)
        val x1 = min(imageW, box.x2.toInt() + pad)
        val y1 = min(imageH, box.y2.toInt() + pad)
        if (x1 - x0 < 12 || y1 - y0 < 12) return null

        val crop = lab.submat(y0, y1, x0, x1)
        var dist8: Mat? = null
        var mask: Mat? = null
        val contours = ArrayList<MatOfPoint>()
        try {
            val bg = borderMeanLab(crop)
            val diff = splitDistance(crop, bg)
            val d8 = Mat()
            diff.convertTo(d8, CvType.CV_8UC1, DIST_SCALE)
            diff.release()
            dist8 = d8

            val m = Mat()
            val otsu = Imgproc.threshold(d8, m, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val fgThresh = max(otsu, MIN_FG_DELTA_E * DIST_SCALE)
            if (fgThresh > otsu) {
                Imgproc.threshold(d8, m, fgThresh, 255.0, Imgproc.THRESH_BINARY)
            }
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, closeKernel)
            openKernel.release()
            closeKernel.release()
            mask = m

            val hierarchy = Mat()
            Imgproc.findContours(m, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()
            if (contours.isEmpty()) return null

            val boxArea = bw.toDouble() * bh.toDouble()
            val best = contours
                .map { it to Imgproc.contourArea(it) }
                .filter { (_, a) -> a >= boxArea * 0.10 && a <= boxArea * 4.0 }
                .maxByOrNull { it.second }
                ?.first
            if (best == null) return null

            val area = Imgproc.contourArea(best)
            val rect = Imgproc.boundingRect(best)
            val contour2f = MatOfPoint2f(*best.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true).coerceAtLeast(1e-6)
            // 旋转不变几何：药丸随便怎么摆，长/短边比与实心度都不变。
            val minRect = Imgproc.minAreaRect(contour2f)
            contour2f.release()
            val sideLong = max(minRect.size.width, minRect.size.height)
            val sideShort = min(minRect.size.width, minRect.size.height)
            val rotAspect = if (sideShort > 1e-6) sideLong / sideShort else 0.0
            val rotArea = minRect.size.width * minRect.size.height
            val rotExtent = if (rotArea > 1e-6) (area / rotArea).coerceIn(0.0, 1.0) else 0.0

            val contourPoints = best.toArray()
            val hullIdx = MatOfInt()
            Imgproc.convexHull(best, hullIdx)
            val hullPoints = MatOfPoint(*hullIdx.toArray().map { contourPoints[it] }.toTypedArray())
            val hullArea = Imgproc.contourArea(hullPoints)
            val solidity = if (hullArea > 0.0) area / hullArea else 1.0
            hullPoints.release()
            hullIdx.release()

            val moments = Imgproc.moments(best)
            val huMat = Mat()
            val hu = DoubleArray(7)
            if (moments.m00 > 0.0) {
                Imgproc.HuMoments(moments, huMat)
                huMat.get(0, 0, hu)
            }
            huMat.release()

            val oneMask = Mat.zeros(m.size(), CvType.CV_8UC1)
            Imgproc.drawContours(oneMask, listOf(best), -1, Scalar(255.0), -1)
            val meanArr = Core.mean(crop, oneMask).`val`
            oneMask.release()

            val cx = if (moments.m00 > 0.0) (moments.m10 / moments.m00).toFloat() else rect.x + rect.width / 2f
            val cy = if (moments.m00 > 0.0) (moments.m01 / moments.m00).toFloat() else rect.y + rect.height / 2f
            return RawObservation(
                cx = x0 + cx,
                cy = y0 + cy,
                area = area,
                width = rect.width,
                height = rect.height,
                perimeter = perimeter,
                solidity = solidity,
                aspect = rotAspect,
                extent = rotExtent,
                hu = PillMath.logHu(hu),
                colorLab = doubleArrayOf(meanArr[0], meanArr[1], meanArr[2]),
                points = contourPoints.map { floatArrayOf(x0 + it.x.toFloat(), y0 + it.y.toFloat()) },
            )
        } catch (_: Throwable) {
            return null
        } finally {
            contours.forEach { it.release() }
            crop.release()
            dist8?.release()
            mask?.release()
        }
    }

    /** 分割失败时的兜底：直接用框当一颗药丸（保证颗数正确），几何特征按椭圆近似。 */
    private fun boxFallback(lab: Mat, imageW: Int, imageH: Int, box: DetBox): RawObservation {
        val bw = max(1, box.width.toInt())
        val bh = max(1, box.height.toInt())
        val x0 = box.x1.toInt().coerceIn(0, max(0, imageW - 1))
        val y0 = box.y1.toInt().coerceIn(0, max(0, imageH - 1))
        val x1 = box.x2.toInt().coerceIn(x0 + 1, imageW)
        val y1 = box.y2.toInt().coerceIn(y0 + 1, imageH)
        val roi = lab.submat(y0, y1, x0, x1)
        val meanArr = Core.mean(roi).`val`
        roi.release()
        val a = bw / 2.0
        val b = bh / 2.0
        return RawObservation(
            cx = box.cx,
            cy = box.cy,
            area = Math.PI * a * b,
            width = bw,
            height = bh,
            perimeter = ellipsePerimeter(a, b),
            solidity = 0.90,
            aspect = PillMath.aspectOf(bw, bh),
            extent = Math.PI / 4.0,
            hu = PillMath.logHu(DoubleArray(7)),
            colorLab = doubleArrayOf(meanArr[0], meanArr[1], meanArr[2]),
            points = listOf(
                floatArrayOf(box.x1, box.y1),
                floatArrayOf(box.x2, box.y1),
                floatArrayOf(box.x2, box.y2),
                floatArrayOf(box.x1, box.y2),
            ),
        )
    }

    /** Ramanujan 近似椭圆周长（a、b 为半轴）。 */
    private fun ellipsePerimeter(a: Double, b: Double): Double {
        if (a <= 0.0 || b <= 0.0) return 0.0
        val h = ((a - b) * (a - b)) / ((a + b) * (a + b))
        return Math.PI * (a + b) * (1.0 + 3.0 * h / (10.0 + Math.sqrt(4.0 - 3.0 * h)))
    }

    /** 用四条边带（各占 8%）的 Lab 均值估计局部背景色。 */
    private fun borderMeanLab(lab: Mat): DoubleArray {
        val band = max(2, (min(lab.cols(), lab.rows()) * 0.08).toInt())
        val strips = listOf(
            lab.submat(0, band, 0, lab.cols()),
            lab.submat(lab.rows() - band, lab.rows(), 0, lab.cols()),
            lab.submat(0, lab.rows(), 0, band),
            lab.submat(0, lab.rows(), lab.cols() - band, lab.cols()),
        )
        val sum = DoubleArray(3)
        for (s in strips) {
            val arr = Core.mean(s).`val`
            for (i in 0 until 3) sum[i] += arr[i]
            s.release()
        }
        return DoubleArray(3) { sum[it] / strips.size }
    }

    /** 每个像素与背景 Lab 的欧氏距离（CV_32F 单通道）。 */
    private fun splitDistance(lab: Mat, bg: DoubleArray): Mat {
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        val diffs = ArrayList<Mat>()
        for (i in 0 until 3) {
            val f = Mat()
            channels[i].convertTo(f, CvType.CV_32F)
            Core.subtract(f, Scalar(bg[i]), f)
            diffs.add(f)
        }
        val mag1 = Mat()
        Core.magnitude(diffs[0], diffs[1], mag1)
        val mag2 = Mat()
        Core.magnitude(mag1, diffs[2], mag2)
        channels.forEach { it.release() }
        diffs.forEach { it.release() }
        mag1.release()
        return mag2
    }
}
