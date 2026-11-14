package com.pingwu.app.cv

/**
 * 一个检测框（像素坐标）。score 是模型置信度，合并后取组内最大值。
 */
data class DetBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
) {
    val width: Float get() = (x2 - x1).coerceAtLeast(0f)
    val height: Float get() = (y2 - y1).coerceAtLeast(0f)
    val area: Float get() = width * height
    val cx: Float get() = (x1 + x2) * 0.5f
    val cy: Float get() = (y1 + y2) * 0.5f
}

/**
 * 检测框后处理（纯 Kotlin，可在 JVM 单测里跑）：
 * 解码模型输出 → 置信度过滤 → NMS → 聚类合并。
 *
 * 为什么需要「聚类合并」：实测长条形胶囊经常被拆成两三个互相重叠的框
 * （整颗外接框 + 左半 + 右半），标准 NMS（IoU 0.65）压不掉，颗数会多数。
 * 合并规则：IoU > iouThreshold，或交集占较小框面积比 > containThreshold，即并成一组；
 * 每组取并集外接框 + 组内最大分数。真实照片回放实测：
 * exact_count 从 0.476 提到 0.905，count_MAE 从 1.048 降到 0.095。
 */
object PillBoxMath {

    fun iou(a: DetBox, b: DetBox): Float {
        val inter = intersectionArea(a, b)
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** 交集 / 较小框面积：一个框被另一个框吞掉时接近 1（胶囊被拆成两半时正是这种形态）。 */
    fun containment(a: DetBox, b: DetBox): Float {
        val inter = intersectionArea(a, b)
        val minArea = kotlin.math.min(a.area, b.area)
        return if (minArea <= 0f) 0f else inter / minArea
    }

    private fun intersectionArea(a: DetBox, b: DetBox): Float {
        val w = kotlin.math.min(a.x2, b.x2) - kotlin.math.max(a.x1, b.x1)
        val h = kotlin.math.min(a.y2, b.y2) - kotlin.math.max(a.y1, b.y1)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    /** 标准贪心 NMS：按分数从高到低保留，压掉与已保留框 IoU 超阈值的低分框。 */
    fun nms(boxes: List<DetBox>, iouThreshold: Float): List<DetBox> {
        if (boxes.size <= 1) return boxes
        val remaining = boxes.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<DetBox>(remaining.size)
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            kept.add(best)
            remaining.removeAll { iou(best, it) > iouThreshold }
        }
        return kept
    }

    /** 并查集聚类合并：把「同一个药丸的多个重叠框」合成一个并集框。 */
    fun clusterMerge(
        boxes: List<DetBox>,
        iouThreshold: Float,
        containThreshold: Float,
    ): List<DetBox> {
        if (boxes.size <= 1) return boxes
        val parent = IntArray(boxes.size) { it }
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                if (iou(boxes[i], boxes[j]) > iouThreshold ||
                    containment(boxes[i], boxes[j]) > containThreshold
                ) {
                    val ri = find(parent, i)
                    val rj = find(parent, j)
                    if (ri != rj) parent[rj] = ri
                }
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in boxes.indices) {
            groups.getOrPut(find(parent, i)) { ArrayList() }.add(i)
        }
        return groups.values.map { group ->
            DetBox(
                x1 = group.minOf { boxes[it].x1 },
                y1 = group.minOf { boxes[it].y1 },
                x2 = group.maxOf { boxes[it].x2 },
                y2 = group.maxOf { boxes[it].y2 },
                score = group.maxOf { boxes[it].score },
            )
        }
    }

    private fun find(parent: IntArray, start: Int): Int {
        var root = start
        while (parent[root] != root) root = parent[root]
        var cur = start
        while (parent[cur] != cur) {
            val next = parent[cur]
            parent[cur] = root
            cur = next
        }
        return root
    }

    /**
     * 解码 ONNX 输出。flat 布局为每颗 anchor 6 个数：cx, cy, w, h（输入尺度像素）, obj, cls；
     * score = obj * cls。coordScale 把输入尺度坐标折算到目标图坐标（= 1 / letterbox 缩放比）。
     */
    fun decode(
        flat: FloatArray,
        anchors: Int,
        coordScale: Float,
        confThreshold: Float,
    ): List<DetBox> {
        require(flat.size >= anchors * 6) { "flat too small: " + flat.size + " < " + anchors * 6 }
        val out = ArrayList<DetBox>(64)
        for (i in 0 until anchors) {
            val o = i * 6
            val score = flat[o + 4] * flat[o + 5]
            if (score < confThreshold) continue
            val cx = flat[o] * coordScale
            val cy = flat[o + 1] * coordScale
            val hw = flat[o + 2] * coordScale * 0.5f
            val hh = flat[o + 3] * coordScale * 0.5f
            out.add(DetBox(cx - hw, cy - hh, cx + hw, cy + hh, score))
        }
        return out
    }
}
