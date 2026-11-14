package com.pingwu.app.cv

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
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
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 药丸检测管线（M3 基线，对应《02》§5 的 11 步里的 1–7 步）：
 * 缩放到长边 1280 → Lab 颜色距离（以背景为基准）→ Otsu → 形态学 →
 * 外轮廓 → 面积过滤 → 粘连拆分 → 颜色/形状特征。
 *
 * 背景基准分两种情形（真机上都会遇到）：
 * 1. 背景铺满画面（纯色垫/白纸）：直接用四条边带的均值当背景色。
 * 2. 药丸放在一小块纯色纸/卡片上，纸又放在木桌上（真实日常摆法）：
 *    第一遍会把整块纸当成一个「超大前景」而过滤掉，药丸全被吞在里面。
 *    这时自动裁到这块纸上、用纸的颜色当背景重测一遍（两遍法）。
 *
 * 粘连处理（2026-11-10 用户反馈「实际场景中药丸不会均匀分开，因为不便利」）：
 * 不再要求用户重拍，而是对可疑粘连块做距离变换 + 种子区域生长自动拆分成单颗。
 * 只有在拆分失败（例如两颗药完全叠压成一颗、或糊成一片）时才报 template_clump。
 *
 * 只做「检测 + 计数 + 粘连拆分」，不做模板比对（M4）。
 */
object PillDetector {

    const val MAX_LONG_EDGE = 1280

    /** 参照物/参考卡模式下，四周丢弃 12% 画布：药丸放中间，辅助物留边上。 */
    const val REFERENCE_SIDE_MARGIN = 0.12f
    /** 单个候选的最小面积占比：木纹碎片实测中位面积只有药丸的 1/20，用 0.05% 把它们全挡掉。 */
    private const val MIN_AREA_RATIO = 0.0005
    private const val MAX_AREA_RATIO = 0.06
    /** 候选内部「与背景的平均 Lab 距离」。药丸对木桌通常 >30，木纹/反光 <20。 */
    private const val MIN_MEAN_DELTA_E = 22.0

    /** Lab 距离放大倍数：ΔE 3 左右对应 8U（药丸 ΔE 常 >25，木纹 <15）。 */
    private const val DIST_SCALE = 3.0
    /** 前景绝对下限（Lab ΔE）：低于它的一律当背景纹理，防 Otsu 在木纹/反光上乱切。 */
    private const val MIN_FG_DELTA_E = 15.0

    /** 单个外轮廓超过画面这个比例，就认定它是「用户垫的底垫/纸」，走两遍法的第二遍。 */
    private const val PAD_MIN_FRAME_RATIO = 0.12
    /** 裁到垫内时向内收 4%，避开纸边缘的阴影/反光。 */
    private const val PAD_SHRINK = 0.04

    /** 面积超过中位数这个倍数，就尝试拆分（单颗药丸的面积比远小于此）。 */
    private const val SPLIT_AREA_RATIO = 1.7
    /** 距离变换内核阈值：> 峰值 * 该比例 的像素算「种子内核」。0.55 能让贴在一起的两颗药丸各留一个核。 */
    private const val SPLIT_CORE_RATIO = 0.55
    /** 拆出来的单块至少要有中位面积的这个比例，否则当噪点丢掉。 */
    private const val SPLIT_PIECE_MIN_RATIO = 0.35
    /** 一块最多拆成这么多颗，超了说明是手掌/杂物，不拆。 */
    private const val SPLIT_MAX_PIECES = 6

    @Volatile
    private var initialized = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            initialized = try {
                OpenCVLoader.initLocal()
            } catch (_: Throwable) {
                runCatching { System.loadLibrary("opencv_java4") }.isSuccess
            }
        }
        return initialized
    }

    /**
     * @param sideMarginFraction 画布四周要丢弃的比例（0 表示整幅都算）。
     * 选「参照物 / 参考卡」时传 0.12：药丸要求放在中间，硬币/卡/角标留在边上，
     * 这些辅助物不会被当成药丸采样（否则模板会被污染、验证会多算一颗）。
     */
    fun detect(bitmap: Bitmap, sideMarginFraction: Float = 0f): DetectResult {
        require(ensureInitialized()) { "OpenCV 初始化失败" }
        val rgba = Mat()
        val rgb = Mat()
        val scaled = Mat()
        var lab: Mat? = null
        try {
            Utils.bitmapToMat(bitmap, rgba)
            if (rgba.channels() == 4) {
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            } else {
                rgba.copyTo(rgb)
            }
            val scale = scaleFor(rgb.cols(), rgb.rows())
            if (scale < 1.0) {
                Imgproc.resize(rgb, scaled, Size(), scale, scale, Imgproc.INTER_AREA)
            } else {
                rgb.copyTo(scaled)
            }
            val labMat = Mat()
            Imgproc.cvtColor(scaled, labMat, Imgproc.COLOR_RGB2Lab)
            lab = labMat

            // 第一遍：整幅，背景取四条边带均值（背景铺满画面的常见情形）。
            val first = runPass(labMat, null, sideMarginFraction, null)
            val frameArea = scaled.cols().toDouble() * scaled.rows().toDouble()
            val padRect = first.largestRect
            // 第一遍已经数到药丸就直接用：真实场景里木桌纹理会被 Otsu 切碎，
            // 若此时还去走「第二遍垫子假设」，反而把木纹当药丸（实测 37 个假目标）。
            val firstCount = first.result.count
            val looksLikePadOnly = firstCount == 1 && first.largestArea > frameArea * 0.02
            if (firstCount > 0 && !looksLikePadOnly) return first.result
            if (first.largestArea > frameArea * PAD_MIN_FRAME_RATIO && padRect != null) {
                // 第二遍：画面里有一大块「药丸以外的均匀物件」，多半是用户垫的纸/卡片。
                // 裁到它里面，用它自己的颜色当背景，药丸才不会被整块纸吞掉。
                val inner = shrink(padRect, PAD_SHRINK, scaled.cols(), scaled.rows())
                if (inner != null) {
                    val padBg = medianLab(labMat, inner)
                    val second = runPass(labMat, inner, sideMarginFraction, padBg)
                    if (second.result.count > 0) return second.result
                }
            }
            return first.result
        } finally {
            rgba.release()
            rgb.release()
            scaled.release()
            lab?.release()
        }
    }

    private class PassResult(
        val result: DetectResult,
        val largestArea: Double,
        val largestRect: Rect?,
    )

    private class Candidate(
        val contour: MatOfPoint,
        val area: Double,
        val rect: Rect,
        val perimeter: Double,
        val solidity: Double,
        val hu: DoubleArray,
        val cx: Float,
        val cy: Float,
        val colorLab: DoubleArray,
        val points: List<FloatArray>,
        val meanDeltaE: Double,
    )

    /**
     * 跑一遍完整管线。
     * @param region 只处理这张图的这一块（null = 整幅）；返回的坐标是相对该块的。
     * @param bgOverride 指定背景 Lab 颜色（null = 用边带均值估计）。
     */
    private fun runPass(
        lab: Mat,
        region: Rect?,
        sideMarginFraction: Float,
        bgOverride: DoubleArray?,
    ): PassResult {
        val x0 = region?.x ?: 0
        val y0 = region?.y ?: 0
        val w = region?.width ?: lab.cols()
        val h = region?.height ?: lab.rows()

        val labR = lab.submat(y0, y0 + h, x0, x0 + w)
        var dist8: Mat? = null
        var mask: Mat? = null
        try {
            // 1) 背景色：给定值，或用四条边带（各占 8%）估计
            val bg = bgOverride ?: borderMeanLab(labR)

            // 2) 每个像素与背景的 Lab 欧氏距离，归一化后 Otsu 二值化（前景 = 与背景差异大的像素）
            val diff = splitDistance(labR, bg)
            dist8 = Mat()
            // 固定倍数放大 Lab 距离（ΔE * 3），再用绝对下限兜底：
            // 木纹/桌面反光的 ΔE 通常 < 15，药丸 > 25；只靠 Otsu 会在整幅木桌上把纹理也切出来
            // （实测 5 颗药丸被切成 32 个「对象」）。
            diff.convertTo(dist8, CvType.CV_8UC1, DIST_SCALE)
            diff.release()

            mask = Mat()
            val otsu = Imgproc.threshold(dist8, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val fgThresh = max(otsu, MIN_FG_DELTA_E * DIST_SCALE)
            if (fgThresh > otsu) {
                Imgproc.threshold(dist8, mask, fgThresh, 255.0, Imgproc.THRESH_BINARY)
            }
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeKernel)
            openKernel.release()
            closeKernel.release()

            // 3) 外轮廓 + 面积/形状过滤
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            val frame = w.toDouble() * h.toDouble()
            var largestArea = 0.0
            var largestRect: Rect? = null
            val candidates = ArrayList<Candidate>()
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area > largestArea) {
                    largestArea = area
                    largestRect = Imgproc.boundingRect(contour)
                }
                if (area < frame * MIN_AREA_RATIO || area > frame * MAX_AREA_RATIO) continue
                val rect = Imgproc.boundingRect(contour)
                val candidate = candidateOf(labR, mask.size(), contour, area, rect, dist8)
                // 木纹/桌面色块与背景的平均色差很小，药丸很大：用平均 ΔE 再筛一道，
                // 避免「木桌被数成 37 颗」这类假阳性。
                if (candidate.meanDeltaE < MIN_MEAN_DELTA_E) continue
                candidates.add(candidate)
            }

            val kept = if (sideMarginFraction > 0f) {
                val mx = w * sideMarginFraction
                val my = h * sideMarginFraction
                candidates.filter { c ->
                    c.cx >= mx && c.cx <= w - mx && c.cy >= my && c.cy <= h - my
                }
            } else {
                candidates
            }

            // 4) 粘连拆分：可疑块自动拆成单颗（真实场景用户不会均匀摆开）
            val baseMedian = PillMath.median(kept.map { it.area })
            val expanded = ArrayList<Candidate>(kept.size)
            for (c in kept) {
                if (shouldTrySplit(c, baseMedian)) {
                    val pieces = splitCandidate(mask, labR, c, baseMedian)
                    if (pieces != null && pieces.size >= 2) {
                        expanded.addAll(pieces)
                        continue
                    }
                }
                expanded.add(c)
            }

            val medianArea = PillMath.median(expanded.map { it.area })
            val observations = expanded.map { c ->
                PillObservation(
                    centerX = c.cx,
                    centerY = c.cy,
                    area = c.area,
                    width = c.rect.width,
                    height = c.rect.height,
                    aspect = PillMath.aspectOf(c.rect.width, c.rect.height),
                    circularity = PillMath.circularity(c.area, c.perimeter),
                    solidity = c.solidity,
                    colorLab = c.colorLab,
                    hu = c.hu,
                    points = c.points,
                    isClump = PillMath.isClump(c.area, medianArea, c.solidity),
                )
            }
            contours.forEach { it.release() }

            return PassResult(
                result = DetectResult(
                    count = observations.size,
                    clumps = observations.count { it.isClump },
                    observations = observations,
                    medianArea = medianArea,
                    threshold = fgThresh,
                    imageWidth = w,
                    imageHeight = h,
                ),
                largestArea = largestArea,
                largestRect = largestRect,
            )
        } finally {
            labR.release()
            dist8?.release()
            mask?.release()
        }
    }

    /** 面积明显偏大或实心度低 → 值得尝试拆分。 */
    private fun shouldTrySplit(c: Candidate, medianArea: Double): Boolean {
        if (medianArea <= 0.0) return false
        if (c.area > medianArea * SPLIT_AREA_RATIO) return true
        return c.solidity < PillMath.CLUMP_SOLIDITY
    }

    /**
     * 粘连拆分：在轮廓内部做距离变换，取「内核」（距离边界最远的区域）当种子，
     * 再用最近种子把粘连块切成若干块（Voronoi 划分），每块单独当一颗药丸。
     * 拆不出来（种子只有一个）就返回 null，由调用方保留原轮廓标 isClump。
     */
    private fun splitCandidate(
        mask: Mat,
        lab: Mat,
        c: Candidate,
        medianArea: Double,
    ): List<Candidate>? {
        val oneMask = Mat.zeros(mask.size(), CvType.CV_8UC1)
        var dt: Mat? = null
        var cores: Mat? = null
        var coreValid: Mat? = null
        var inv: Mat? = null
        var dist: Mat? = null
        var labels: Mat? = null
        try {
            Imgproc.drawContours(oneMask, listOf(c.contour), -1, Scalar(255.0), -1)
            dt = Mat()
            Imgproc.distanceTransform(oneMask, dt, Imgproc.DIST_L2, 5)
            val maxR = Core.minMaxLoc(dt, oneMask).maxVal
            if (maxR < 2.0) return null

            // dt 是 CV_32F（distanceTransform 的输出），不能直接 threshold 成 8U 再喂给
            // connectedComponentsWithStats（它只接受 CV_8U/CV_8S，否则抛 cv::Exception 被上层吞掉，
            // 粘连拆分就静默失效）。用 compare 得到 CV_8U 的 0/255 掩膜。
            cores = Mat()
            Core.compare(dt, Scalar(maxR * SPLIT_CORE_RATIO), cores, Core.CMP_GT)

            val compLabels = Mat()
            val stats = Mat()
            val cents = Mat()
            Imgproc.connectedComponentsWithStats(cores, compLabels, stats, cents, 8, CvType.CV_32S)
            val total = stats.rows()
            val validComp = HashSet<Int>()
            val minCoreArea = max(6.0, medianArea * 0.03)
            for (i in 1 until total) {
                if (stats.get(i, Imgproc.CC_STAT_AREA)[0] >= minCoreArea) validComp.add(i)
            }
            if (validComp.size < 2 || validComp.size > SPLIT_MAX_PIECES) {
                compLabels.release(); stats.release(); cents.release()
                return null
            }

            // 只保留有效内核像素，越界坐标直接跳过
            coreValid = Mat.zeros(mask.size(), CvType.CV_8UC1)
            val rect = c.rect
            val compAt = IntArray(rect.width * rect.height) { -1 }
            for (dy in 0 until rect.height) {
                val y = rect.y + dy
                if (y < 0 || y >= mask.rows()) continue
                for (dx in 0 until rect.width) {
                    val x = rect.x + dx
                    if (x < 0 || x >= mask.cols()) continue
                    val cv = cores.get(y, x) ?: continue
                    if (cv[0] < 128.0) continue
                    val comp = compLabels.get(y, x)!![0].toInt()
                    if (comp !in validComp) continue
                    compAt[dy * rect.width + dx] = comp
                    coreValid.put(y, x, 255.0)
                }
            }
            compLabels.release(); stats.release(); cents.release()

            inv = Mat()
            Core.bitwise_not(coreValid, inv)
            dist = Mat()
            labels = Mat()
            Imgproc.distanceTransformWithLabels(inv, dist, labels, Imgproc.DIST_L2, 3, Imgproc.DIST_LABEL_PIXEL)

            // 种子像素的标签 id → 内核组件 id
            val labelToComp = HashMap<Int, Int>()
            for (dy in 0 until rect.height) {
                val y = rect.y + dy
                if (y < 0 || y >= mask.rows()) continue
                for (dx in 0 until rect.width) {
                    val comp = compAt[dy * rect.width + dx]
                    if (comp <= 0) continue
                    val x = rect.x + dx
                    if (x < 0 || x >= mask.cols()) continue
                    val lblId = labels.get(y, x)!![0].toInt()
                    labelToComp[lblId] = comp
                }
            }
            if (labelToComp.isEmpty()) return null

            // 每个内核组件 = 一颗药丸：把属于它的前景像素收集成一块
            val pieceMasks = HashMap<Int, Mat>()
            for (y in rect.y until rect.y + rect.height) {
                for (x in rect.x until rect.x + rect.width) {
                    val mv = oneMask.get(y, x) ?: continue
                    if (mv[0] < 128.0) continue
                    val lblId = labels.get(y, x)!![0].toInt()
                    val comp = labelToComp[lblId] ?: continue
                    val pm = pieceMasks.getOrPut(comp) { Mat.zeros(mask.size(), CvType.CV_8UC1) }
                    pm.put(y, x, 255.0)
                }
            }

            val pieces = ArrayList<Candidate>()
            var sumArea = 0.0
            for (pm in pieceMasks.values) {
                val pc = ArrayList<MatOfPoint>()
                val ph = Mat()
                Imgproc.findContours(pm, pc, ph, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                ph.release()
                if (pc.isNotEmpty()) {
                    val contour = pc.maxByOrNull { Imgproc.contourArea(it) }!!
                    val area = Imgproc.contourArea(contour)
                    if (area >= medianArea * SPLIT_PIECE_MIN_RATIO) {
                        val pieceRect = Imgproc.boundingRect(contour)
                        pieces.add(candidateOf(lab, pm.size(), contour, area, pieceRect))
                        sumArea += area
                    }
                }
                pc.forEach { it.release() }
                pm.release()
            }

            if (pieces.size < 2) return null
            // 拆出来的总面积应该接近原面积（只允许少量缺口损耗）
            if (sumArea < c.area * 0.6) return null
            return pieces
        } catch (_: Throwable) {
            return null
        } finally {
            oneMask.release()
            dt?.release()
            cores?.release()
            coreValid?.release()
            inv?.release()
            dist?.release()
            labels?.release()
        }
    }

    private fun candidateOf(
        lab: Mat,
        maskSize: Size,
        contour: MatOfPoint,
        area: Double,
        rect: Rect,
        dist8: Mat? = null,
    ): Candidate {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val perimeter = Imgproc.arcLength(contour2f, true).coerceAtLeast(1e-6)
        contour2f.release()
        val contourPts = contour.toArray()
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val hullPoints = MatOfPoint(*hullIdx.toArray().map { contourPts[it] }.toTypedArray())
        val hullArea = Imgproc.contourArea(hullPoints)
        val solidity = if (hullArea > 0.0) area / hullArea else 1.0
        hullPoints.release()
        hullIdx.release()

        val moments = Imgproc.moments(contour)
        val huMat = Mat()
        val hu = DoubleArray(7)
        if (moments.m00 > 0.0) {
            Imgproc.HuMoments(moments, huMat)
            huMat.get(0, 0, hu)
        }
        huMat.release()

        val oneMask = Mat.zeros(maskSize, CvType.CV_8UC1)
        Imgproc.drawContours(oneMask, listOf(contour), -1, Scalar(255.0), -1)
        val meanArr = Core.mean(lab, oneMask).`val`
        // dist8 是「Lab 距离 × DIST_SCALE」的 8U 图，均值除回去就是候选内部的平均 ΔE。
        val meanDeltaE = if (dist8 != null && dist8.cols() == oneMask.cols() && dist8.rows() == oneMask.rows()) {
            Core.mean(dist8, oneMask).`val`[0] / DIST_SCALE
        } else {
            Double.MAX_VALUE
        }
        oneMask.release()

        return Candidate(
            contour = contour,
            area = area,
            rect = rect,
            perimeter = perimeter,
            solidity = solidity,
            hu = PillMath.logHu(hu),
            cx = if (moments.m00 > 0.0) (moments.m10 / moments.m00).toFloat() else rect.x + rect.width / 2f,
            cy = if (moments.m00 > 0.0) (moments.m01 / moments.m00).toFloat() else rect.y + rect.height / 2f,
            colorLab = doubleArrayOf(meanArr[0], meanArr[1], meanArr[2]),
            points = contour.toArray().map { floatArrayOf(it.x.toFloat(), it.y.toFloat()) },
            meanDeltaE = meanDeltaE,
        )
    }

    private fun scaleFor(width: Int, height: Int): Double {
        val longEdge = max(width, height)
        if (longEdge <= MAX_LONG_EDGE) return 1.0
        return MAX_LONG_EDGE.toDouble() / longEdge.toDouble()
    }

    /** 用四条边带（各占 8%）的 Lab 均值估计背景色。 */
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

    /** 取区域内 Lab 的中位数当背景色（药丸只占小部分，中位数落在垫子颜色上）。 */
    private fun medianLab(lab: Mat, rect: Rect): DoubleArray {
        val sub = lab.submat(rect.y, rect.y + rect.height, rect.x, rect.x + rect.width)
        val step = max(1, max(sub.cols(), sub.rows()) / 96)
        val l = ArrayList<Double>(8192)
        val a = ArrayList<Double>(8192)
        val b = ArrayList<Double>(8192)
        var y = 0
        while (y < sub.rows()) {
            var x = 0
            while (x < sub.cols()) {
                val v = sub.get(y, x)
                if (v != null && v.size >= 3) {
                    l.add(v[0]); a.add(v[1]); b.add(v[2])
                }
                x += step
            }
            y += step
        }
        sub.release()
        return doubleArrayOf(
            PillMath.median(l),
            PillMath.median(a),
            PillMath.median(b),
        )
    }

    /** 把矩形向内收 ratio（相对宽高），并夹在图像范围内；太小返回 null。 */
    private fun shrink(rect: Rect, ratio: Double, maxW: Int, maxH: Int): Rect? {
        val dx = (rect.width * ratio).toInt()
        val dy = (rect.height * ratio).toInt()
        val w = rect.width - 2 * dx
        val h = rect.height - 2 * dy
        if (w < 16 || h < 16) return null
        val x = (rect.x + dx).coerceIn(0, max(0, maxW - w))
        val y = (rect.y + dy).coerceIn(0, max(0, maxH - h))
        return Rect(x, y, min(w, maxW - x), min(h, maxH - y))
    }

    /** 计算每个像素与背景 Lab 的欧氏距离（CV_32F 单通道）。 */
    private fun splitDistance(lab: Mat, bg: DoubleArray): Mat {
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val diff = ArrayList<Mat>()
        for (i in 0 until 3) {
            val f = Mat()
            ch[i].convertTo(f, CvType.CV_32F)
            Core.subtract(f, Scalar(bg[i]), f)
            diff.add(f)
        }
        val mag1 = Mat()
        Core.magnitude(diff[0], diff[1], mag1)
        val mag2 = Mat()
        Core.magnitude(mag1, diff[2], mag2)
        ch.forEach { it.release() }
        diff.forEach { it.release() }
        mag1.release()
        return mag2
    }
}
