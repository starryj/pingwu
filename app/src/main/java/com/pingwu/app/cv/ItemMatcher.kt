package com.pingwu.app.cv

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/** 一次「这张照片里是不是那件东西」的比对结果。 */
data class ItemMatchResult(
    /** 认出来了没有。 */
    val matched: Boolean,
    /** 通过比例检验的 ORB 特征点对数量。 */
    val goodMatches: Int,
    /** 经 RANSAC 认为是同一平面变换的内点数。 */
    val inliers: Int,
    /** 颜色直方图相关性（-1..1），只作日志与兜底参考。 */
    val colorScore: Double = 0.0,
    /** 没认出来时给人看的一句话。 */
    val reason: String? = null,
    /** 登记照里提出来多少特征点：太少说明这件东西太素，本机认不准。 */
    val coverKeyPoints: Int = 0,
    /** 现场照里提出来多少特征点。 */
    val photoKeyPoints: Int = 0,
)

/**
 * 通用物品的「认物」比对（M11）：
 * 用 ORB 特征点把「这件东西的登记照」和「刚拍的照片」对上。
 *
 * 为什么用 ORB：特征点自带方向，拿手机把东西转 90° / 180° / 斜着放，仍然能对上——
 * 解决了 M9 之前「换个方向就识别不出来」的问题。颜色直方图只做辅助（旋转不变），
 * 用来在纹理太少时给一句可解释的话。
 *
 * 这里只回答「像不像同一件东西」，不判断「有没有真的做」，和产品口径一致。
 */
object ItemMatcher {

    /** 长边缩到这个尺寸再算，速度和精度平衡。 */
    private const val MAX_EDGE = 900

    /** 至少要对上这么多组特征点，才敢说「是它」。 */
    const val MIN_KEYPOINTS = 10

    private const val MIN_GOOD = MIN_KEYPOINTS

    /** RANSAC 内点下限。 */
    private const val MIN_INLIERS = 5

    /** Lowe 比例检验阈值。 */
    private const val RATIO = 0.75f

    fun load(path: String): Bitmap? = runCatching {
        android.graphics.BitmapFactory.decodeFile(path)
    }.getOrNull()

    fun compare(cover: Bitmap, photo: Bitmap): ItemMatchResult {
        if (!OpenCVLoader.initLocal()) {
            return ItemMatchResult(false, 0, 0, reason = "本机视觉组件没起来。")
        }
        var grayA: Mat? = null
        var grayB: Mat? = null
        var descA: Mat? = null
        var descB: Mat? = null
        var mask: Mat? = null
        try {
            grayA = grayMat(cover)
            grayB = grayMat(photo)
            val orb = ORB.create(1500, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20)
            val keyA = MatOfKeyPoint()
            val keyB = MatOfKeyPoint()
            descA = Mat()
            descB = Mat()
            orb.detectAndCompute(grayA, Mat(), keyA, descA)
            orb.detectAndCompute(grayB, Mat(), keyB, descB)
            val colorScore = colorCorrelation(grayA, grayB)

            val countA = keyA.toArray().size
            val countB = keyB.toArray().size
            if (descA.empty() || countA < MIN_GOOD) {
                return ItemMatchResult(
                    false, 0, 0, colorScore,
                    "这张样子太素了，本机认不准，可以靠近一点重拍一张。",
                    coverKeyPoints = countA, photoKeyPoints = countB,
                )
            }
            if (descB.empty() || countB < MIN_GOOD) {
                return ItemMatchResult(
                    false, 0, 0, colorScore,
                    "照片里能认的细节太少，靠近一点、对着光再拍一张。",
                    coverKeyPoints = countA, photoKeyPoints = countB,
                )
            }

            val matcher = BFMatcher(BFMatcher.BRUTEFORCE_HAMMING, false)
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(descA, descB, knn, 2)
            val good = ArrayList<Point>()
            val goodB = ArrayList<Point>()
            val ptsA = keyA.toArray()
            val ptsB = keyB.toArray()
            for (pair in knn) {
                val arr = pair.toArray()
                if (arr.size < 2) continue
                val first = arr[0]
                val second = arr[1]
                if (first.distance < RATIO * second.distance) {
                    good.add(ptsA[first.queryIdx].pt)
                    goodB.add(ptsB[first.trainIdx].pt)
                }
            }

            var inliers = 0
            if (good.size >= 8) {
                val src = MatOfPoint2f(*good.toTypedArray())
                val dst = MatOfPoint2f(*goodB.toTypedArray())
                mask = Mat()
                Calib3d.findHomography(src, dst, Calib3d.RANSAC, 5.0, mask)
                src.release()
                dst.release()
                inliers = Core.countNonZero(mask)
            }

            val matched = (good.size >= MIN_GOOD && inliers >= MIN_INLIERS) || good.size >= MIN_GOOD + 6
            val reason = if (matched) {
                null
            } else if (colorScore >= 0.85) {
                "颜色有点像，但形状/细节没对上，再拍一张试试。"
            } else {
                "画面里没认出这件东西，靠近一点、把东西放全再拍。"
            }
            return ItemMatchResult(
                matched, good.size, inliers, colorScore, reason,
                coverKeyPoints = countA, photoKeyPoints = countB,
            )
        } catch (t: Throwable) {
            android.util.Log.w("PwMatch", "compare failed", t)
            return ItemMatchResult(false, 0, 0, reason = "比对没跑完，再拍一张试试。")
        } finally {
            grayA?.release()
            grayB?.release()
            descA?.release()
            descB?.release()
            mask?.release()
        }
    }

    /**
     * 单独数一张图能提多少特征点。
     * 登记完顺手看一眼：太少就提示用户靠近一点重拍，别等以后认不出来才发现。
     */
    fun featureCount(path: String): Int = runCatching {
        if (!OpenCVLoader.initLocal()) return@runCatching 0
        val bitmap = load(path) ?: return@runCatching 0
        var gray: Mat? = null
        try {
            gray = grayMat(bitmap)
            val orb = ORB.create(1500, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20)
            val keys = MatOfKeyPoint()
            val desc = Mat()
            orb.detectAndCompute(gray, Mat(), keys, desc)
            desc.release()
            keys.toArray().size
        } finally {
            gray?.release()
        }
    }.getOrDefault(0)
    /** 转灰度并等比缩到长边 ≤ MAX_EDGE。 */
    private fun grayMat(bitmap: Bitmap): Mat {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val scale = MAX_EDGE.toDouble() / maxOf(src.cols(), src.rows()).coerceAtLeast(1)
        val resized = Mat()
        if (scale < 1.0) {
            Imgproc.resize(src, resized, org.opencv.core.Size(src.cols() * scale, src.rows() * scale))
        } else {
            src.copyTo(resized)
        }
        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
        src.release()
        resized.release()
        return gray
    }

    /**
     * 颜色相关性：旋转不变的 HSV 直方图（H×S），取中间 80% 避开背景。
     * 这里传入的已经是灰度图，颜色就从原始灰度近似——只作文字提示用，不参与是否通过的硬判定。
     */
    private fun colorCorrelation(grayA: Mat, grayB: Mat): Double = runCatching {
        val histA = grayHist(grayA)
        val histB = grayHist(grayB)
        val score = Imgproc.compareHist(histA, histB, Imgproc.HISTCMP_CORREL)
        histA.release()
        histB.release()
        score
    }.getOrDefault(0.0)

    private fun grayHist(gray: Mat): Mat {
        val w = gray.cols()
        val h = gray.rows()
        val crop = gray.submat(h / 10, h - h / 10, w / 10, w - w / 10)
        val hist = Mat()
        Imgproc.calcHist(
            listOf(crop),
            MatOfInt(0),
            Mat(),
            hist,
            MatOfInt(32),
            MatOfFloat(0f, 256f),
        )
        Core.normalize(hist, hist)
        crop.release()
        return hist
    }
}
