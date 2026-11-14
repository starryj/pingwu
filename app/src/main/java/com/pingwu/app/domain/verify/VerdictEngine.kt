package com.pingwu.app.domain.verify

/** 判定失败的具体原因——每一条都要能翻译成一句人话（见《02》§5 失败文案映射）。 */
enum class VerifyFail {
    CLUMP,
    UNKNOWN_PILL,
    MISSING,
    EXTRA,
    LOW_CONFIDENCE,

    /** 颜色对不上：这颗药的颜色不像清单里任何一种（《18》§2.1 N2 / §6 第 11 条）。 */
    COLOR_MISMATCH,

    /** 两味药颜色太像，分不清归属（《18》§3 ④ 的 margin 检查）。 */
    COLOR_TOO_CLOSE,

    /**
     * 形状对不上（《18》§5 P3 辅助确认）：颜色已经分得清楚，但「圆片 / 长条」的粗分类与模板相反。
     * 只在两边形状都明确时才报；模糊区间一律放过——形状本身不可复现，不能当主判据。
     */
    SHAPE_MISMATCH,
}

/** 应服清单里的一项（来自 DOSE_ITEM + DRUG.name）。 */
data class PlannedDrug(
    val drugId: Long,
    val name: String,
    val plannedCount: Int,
    /**
     * 该药模板的颜色容差 T_accept（注册时按 p95×1.15 算，默认 0.8）。
     * 没有模板时用 [VerdictEngine.DEFAULT_TOLERANCE]。
     */
    val tAccept: Double = VerdictEngine.DEFAULT_TOLERANCE,
    /** 模板的粗形状签名；null 表示不做形状兜底（docs/18 P3）。 */
    val shape: ShapeSignature? = null,
)

/** 一颗被分类的药丸；drugId = null 表示不认识（未知药丸）。 */
data class ClassifiedPill(
    val drugId: Long?,
    val confidence: Double,
    val isClump: Boolean = false,
)

/**
 * 一颗药丸到各计划药种的颜色距离（《18》§3 ①③）。
 * 判定层不再让每颗药各自挑最近模板，而是拿着这份距离表做整体最优分配。
 */
data class ColorObservation(
    val distances: Map<Long, Double>,
    val isClump: Boolean = false,
    /** 这颗药的粗形状签名；null 表示没有（不做形状兜底）。 */
    val shape: ShapeSignature? = null,
)

/**
 * 粗形状分类（《18》§5 P3）：只用来区分「圆片」和「长条 / 胶囊」。
 * 真机上形状特征不可复现（同一颗胶囊 aspect 3.24 → 1.66），细粒度形状不能当判据。
 */
enum class ShapeClass { ROUND, ELONGATED, UNKNOWN }

/** 一颗药丸的粗形状签名（长宽比 + 圆度）；两个阈值之间是模糊区间。 */
data class ShapeSignature(val aspect: Double, val circularity: Double) {
    val shapeClass: ShapeClass
        get() = when {
            aspect <= ROUND_MAX_ASPECT -> ShapeClass.ROUND
            aspect >= ELONGATED_MIN_ASPECT -> ShapeClass.ELONGATED
            else -> ShapeClass.UNKNOWN
        }

    companion object {
        /** 圆片的长宽比上限：圆片实测 1.0–1.2，留到 1.30。 */
        const val ROUND_MAX_ASPECT = 1.30

        /** 长条 / 胶囊的长宽比下限：胶囊实测 1.5–3.2；1.45 以下视为模糊。 */
        const val ELONGATED_MIN_ASPECT = 1.45
    }
}

/** 一颗药丸的分配结果，用于展示「哪颗被判成哪味药、有多像」。 */
data class PillAssignment(
    val drugId: Long,
    val drugName: String,
    /** 与所分配药种的颜色距离（越小越像）。 */
    val distance: Double,
    /** 到「第二像」的计划药种的距离；只有一个药种时为 null。 */
    val secondDistance: Double?,
    /** 颜色置信度（公式见 [Confidence]）。 */
    val confidence: Double,
    /** 与第二像药种的距离差；单药种时视为 1.0。 */
    val margin: Double,
    /** 颜色距离是否在容差内。 */
    val colorOk: Boolean,
)

/** 一条可展示的具体原因；expected/detected 只在计数类原因里有意义。 */
data class VerifyIssue(
    val fail: VerifyFail,
    val drugId: Long? = null,
    val drugName: String? = null,
    val expected: Int? = null,
    val detected: Int? = null,
    /** 颜色类原因：实测距离 / 容差，便于真机校准与日志排查。 */
    val distance: Double? = null,
    val tolerance: Double? = null,
    /** 「颜色太像」时另一味药的名字。 */
    val otherDrugName: String? = null,
)

data class VerifyVerdict(
    val passed: Boolean,
    val issues: List<VerifyIssue>,
    val counts: Map<Long, Int>,
    val clumpCount: Int,
    val unknownCount: Int,
    /** 与输入 observations 同序：clump 或没分出去的药丸为 null。 */
    val assignments: List<PillAssignment?> = emptyList(),
    /** 每味药的颜色置信度（该药所有已分配药丸里的最小值；没分到为 0）。 */
    val perDrugConfidence: Map<Long, Double> = emptyMap(),
)

/**
 * 放行判定（纯 Kotlin，可单测）：
 * ① 有粘连 → 不判（先分开重拍）；② 有未知药丸 → 拒绝；
 * ③ 每个药种的检测颗数必须等于计划颗数（防止两种相似药「总数相抵」）；
 * ④ 所有分类置信度 ≥ 0.65（debug 构建可从调试面板临时调低/调高，用于真机校准）。四条全过才允行。
 *
 * 2026-11-12 起新增 [judgeColor]：身份只认「颜色 + 数量」（《18》），
 * 按计划颗数做整体最优分配后，再校验每颗的颜色距离 / margin / 置信度。
 * 旧的 [judge] 保留给单测与回退（13 维特征逐颗分类）使用。
 */
object VerdictEngine {

    const val MIN_CONFIDENCE = 0.65

    /** 没有模板时的默认颜色容差（《18》§1：ΔE 15 以内算同色，归一化后约 0.8）。 */
    const val DEFAULT_TOLERANCE = 0.8

    /** 两种药的颜色距离差小于它就算「太像，分不开」。 */
    const val MIN_MARGIN = 0.4

    fun judge(
        planned: List<PlannedDrug>,
        pills: List<ClassifiedPill>,
        minConfidence: Double = MIN_CONFIDENCE,
    ): VerifyVerdict {
        val issues = ArrayList<VerifyIssue>()
        val plannedIds = planned.map { it.drugId }.toSet()

        val clumps = pills.count { it.isClump }
        if (clumps > 0) {
            issues.add(VerifyIssue(VerifyFail.CLUMP, detected = clumps))
        }

        val usable = pills.filter { !it.isClump }
        val unknown = usable.count { it.drugId == null || it.drugId !in plannedIds }
        if (unknown > 0) {
            issues.add(VerifyIssue(VerifyFail.UNKNOWN_PILL, detected = unknown))
        }

        val counts = HashMap<Long, Int>()
        for (p in usable) {
            val id = p.drugId ?: continue
            if (id !in plannedIds) continue
            counts[id] = (counts[id] ?: 0) + 1
        }
        // 先按药种汇总「应服颗数」再比较：即使计划里同一药出现多行（历史数据/异常写入），
        // 也不能各自拿 1 颗去比而漏掉合计 2 颗的缺口。宁可误拒，也不误放行。
        val expected = LinkedHashMap<Long, Int>()
        val names = HashMap<Long, String>()
        for (item in planned) {
            expected[item.drugId] = (expected[item.drugId] ?: 0) + item.plannedCount
            names.putIfAbsent(item.drugId, item.name)
        }
        for ((drugId, want) in expected) {
            val got = counts[drugId] ?: 0
            when {
                got < want -> issues.add(
                    VerifyIssue(VerifyFail.MISSING, drugId = drugId, drugName = names[drugId], expected = want, detected = got)
                )
                got > want -> issues.add(
                    VerifyIssue(VerifyFail.EXTRA, drugId = drugId, drugName = names[drugId], expected = want, detected = got)
                )
            }
        }

        // 置信度不足的颗数也要报具体数量：只说「有些不确定」用户不知道是 1 颗还是 5 颗。
        val lowConfidence = usable.filter {
            it.drugId != null && it.drugId in plannedIds && it.confidence < minConfidence
        }
        if (lowConfidence.isNotEmpty()) {
            issues.add(VerifyIssue(VerifyFail.LOW_CONFIDENCE, detected = lowConfidence.size))
        }

        return VerifyVerdict(
            passed = issues.isEmpty() && planned.isNotEmpty(),
            issues = issues,
            counts = counts,
            clumpCount = clumps,
            unknownCount = unknown,
        )
    }

    /**
     * 颜色 + 数量的放行判定（《18》§3）：
     * ① 有粘连 → 只报粘连，先分开重拍；
     * ② 按计划颗数做整体最优分配（[DoseAssignment]）；
     * ③ 数量对不上 → 少/多；
     * ④ 颜色距离超出该药容差 → 颜色对不上；
     * ⑤ 与第二像的药种距离差 < [MIN_MARGIN] → 两药颜色太像（建议分开拍）；
     * ⑥ 颜色置信度 < minConfidence → 不确定；
     * ⑦ 全过且颗数恰好才放行。
     */
    fun judgeColor(
        planned: List<PlannedDrug>,
        observations: List<ColorObservation>,
        minConfidence: Double = MIN_CONFIDENCE,
    ): VerifyVerdict {
        val issues = ArrayList<VerifyIssue>()
        val blankAssignments = List<PillAssignment?>(observations.size) { null }
        val clumps = observations.count { it.isClump }
        if (clumps > 0) {
            issues.add(VerifyIssue(VerifyFail.CLUMP, detected = clumps))
            return VerifyVerdict(
                passed = false,
                issues = issues,
                counts = emptyMap(),
                clumpCount = clumps,
                unknownCount = 0,
                assignments = blankAssignments,
                perDrugConfidence = emptyMap(),
            )
        }

        // 同药多行要合并（历史数据/异常写入），容差取最宽的那个。
        val caps = LinkedHashMap<Long, Int>()
        val names = LinkedHashMap<Long, String>()
        val tolerances = HashMap<Long, Double>()
        for (item in planned) {
            caps[item.drugId] = (caps[item.drugId] ?: 0) + item.plannedCount
            names.putIfAbsent(item.drugId, item.name)
            tolerances[item.drugId] = maxOf(
                tolerances[item.drugId] ?: 0.0,
                item.tAccept,
            )
        }
        if (caps.isEmpty()) {
            // 没有计划：永远不放行，也不能把「空清单」当成「都符合」。
            return VerifyVerdict(
                passed = false,
                issues = issues,
                counts = emptyMap(),
                clumpCount = 0,
                unknownCount = 0,
                assignments = blankAssignments,
                perDrugConfidence = emptyMap(),
            )
        }

        val slots = caps.map { (drugId, count) -> DoseAssignment.Slot(drugId, count) }
        val totalCapacity = slots.sumOf { it.capacity }
        // 缺距离（理论上不该发生）给一个大代价，避免被错误地当成「完美匹配」。
        val missingCost = Double.MAX_VALUE / 4.0
        val costs = observations.map { obs ->
            DoubleArray(slots.size) { index ->
                obs.distances[slots[index].drugId] ?: missingCost
            }
        }
        val result = DoseAssignment.solve(costs, slots)

        val assignments = ArrayList<PillAssignment?>(observations.size)
        val counts = LinkedHashMap<Long, Int>()
        for (slot in slots) counts[slot.drugId] = 0
        for (i in observations.indices) {
            val slotIndex = result.assignment[i]
            if (slotIndex < 0 || slotIndex >= slots.size) {
                assignments.add(null)
                continue
            }
            val slot = slots[slotIndex]
            val tolerance = tolerances[slot.drugId] ?: DEFAULT_TOLERANCE
            val distance = costs[i][slotIndex]
            var secondDistance: Double? = null
            for (other in slots.indices) {
                if (other == slotIndex) continue
                val d = costs[i][other]
                if (secondDistance == null || d < secondDistance) {
                    secondDistance = d
                }
            }
            val margin = if (secondDistance == null) 1.0 else (secondDistance - distance)
            val confidence = Confidence.score(distance, tolerance, secondDistance)
            val colorOk = distance <= tolerance
            assignments.add(
                PillAssignment(
                    drugId = slot.drugId,
                    drugName = names[slot.drugId] ?: "",
                    distance = distance,
                    secondDistance = secondDistance,
                    confidence = confidence,
                    margin = margin,
                    colorOk = colorOk,
                )
            )
            counts[slot.drugId] = (counts[slot.drugId] ?: 0) + 1
        }

        // ③ 数量：先把「少了」按药种报出来；多出来的药丸分不出去，按总数报。
        for (slot in slots) {
            val got = counts[slot.drugId] ?: 0
            if (got < slot.capacity) {
                issues.add(
                    VerifyIssue(
                        fail = VerifyFail.MISSING,
                        drugId = slot.drugId,
                        drugName = names[slot.drugId],
                        expected = slot.capacity,
                        detected = got,
                    )
                )
            }
        }
        val extras = result.assignment.count { it < 0 }
        if (extras > 0) {
            issues.add(
                VerifyIssue(
                    fail = VerifyFail.EXTRA,
                    expected = totalCapacity,
                    detected = observations.size,
                )
            )
        }

        // ④ 颜色对不上：分配到了，但距离超出该药容差。
        val mismatched = assignments.filterNotNull().filter { !it.colorOk }
        if (mismatched.isNotEmpty()) {
            val worst = mismatched.maxByOrNull { it.distance - (tolerances[it.drugId] ?: DEFAULT_TOLERANCE) }
            val tolerance = worst?.let { tolerances[it.drugId] ?: DEFAULT_TOLERANCE } ?: DEFAULT_TOLERANCE
            issues.add(
                VerifyIssue(
                    fail = VerifyFail.COLOR_MISMATCH,
                    drugId = worst?.drugId,
                    drugName = worst?.drugName,
                    detected = mismatched.size,
                    distance = worst?.distance,
                    tolerance = tolerance,
                )
            )
        }

        // ⑤ 两药颜色太像：颜色本身对得上（colorOk），但与第二像药种的距离差太小，
        // 归属不可信（单药种时跳过）。颜色已经对不上的那颗由 ④ 负责，不再重复报「太像」。
        val ambiguousIndices = if (slots.size > 1) {
            assignments.indices.filter { i ->
                val a = assignments[i]
                a != null && a.colorOk && a.margin < MIN_MARGIN
            }
        } else {
            emptyList()
        }
        if (ambiguousIndices.isNotEmpty()) {
            val firstIndex = ambiguousIndices.first()
            val first = assignments[firstIndex]!!
            // 找出这颗药「第二像」的药种，用于文案「A 和 B 颜色太像」。
            var otherName: String? = null
            var otherDistance = Double.MAX_VALUE
            for (other in slots.indices) {
                if (slots[other].drugId == first.drugId) continue
                val d = costs[firstIndex][other]
                if (d < otherDistance) {
                    otherDistance = d
                    otherName = names[slots[other].drugId]
                }
            }
            issues.add(
                VerifyIssue(
                    fail = VerifyFail.COLOR_TOO_CLOSE,
                    drugId = first.drugId,
                    drugName = first.drugName,
                    detected = ambiguousIndices.size,
                    distance = first.distance,
                    tolerance = tolerances[first.drugId] ?: DEFAULT_TOLERANCE,
                    otherDrugName = otherName,
                )
            )
        }

        // ⑥ 形状辅助确认（《18》§5 P3）：只有颜色已经分清楚（colorOk 且 margin 够）时才用
        // 「圆片 / 长条」的粗分类兜底；形状模糊、或颜色本身就有问题的颗不参与。
        val plannedShapes = HashMap<Long, ShapeSignature?>()
        for (item in planned) plannedShapes.putIfAbsent(item.drugId, item.shape)
        val shapeBad = assignments.indices.filter { i ->
            val a = assignments[i]
            a != null && a.colorOk && a.margin >= MIN_MARGIN &&
                isShapeMismatch(plannedShapes[a.drugId], observations[i].shape)
        }
        if (shapeBad.isNotEmpty()) {
            val first = assignments[shapeBad.first()]!!
            issues.add(
                VerifyIssue(
                    fail = VerifyFail.SHAPE_MISMATCH,
                    drugId = first.drugId,
                    drugName = first.drugName,
                    detected = shapeBad.size,
                )
            )
        }

        // ⑦ 置信度不足：已经归到「颜色对不上 / 颜色太像」的颗数不再重复报，否则一次失败刷三条提示。
        val lowConfidence = assignments.filterNotNull()
            .count { it.colorOk && it.margin >= MIN_MARGIN && it.confidence < minConfidence }
        if (lowConfidence > 0) {
            issues.add(VerifyIssue(VerifyFail.LOW_CONFIDENCE, detected = lowConfidence))
        }

        val perDrugConfidence = LinkedHashMap<Long, Double>()
        for (slot in slots) {
            val confs = assignments.filterNotNull().filter { it.drugId == slot.drugId }.map { it.confidence }
            perDrugConfidence[slot.drugId] = confs.minOrNull() ?: 0.0
        }

        val passed = issues.isEmpty() && planned.isNotEmpty() &&
            observations.size == totalCapacity

        return VerifyVerdict(
            passed = passed,
            issues = issues,
            counts = counts,
            clumpCount = 0,
            unknownCount = mismatched.size,
            assignments = assignments,
            perDrugConfidence = perDrugConfidence,
        )
    }

    /**
     * 分药拍摄的放行判定（《18》§5 P2 / §2.1 N3）：一张照片只拍一味药。
     * 分开拍本来就是为了避开「两味颜色太像」，所以这里不查 margin / 第二像药种；
     * 但颗数、颜色距离、置信度仍然逐项校验——拍错药（颜色不对）一样拦。
     */
    fun judgeSingleDrug(
        planned: PlannedDrug,
        observations: List<ColorObservation>,
        minConfidence: Double = MIN_CONFIDENCE,
    ): VerifyVerdict {
        val issues = ArrayList<VerifyIssue>()
        val blankAssignments = List<PillAssignment?>(observations.size) { null }
        val clumps = observations.count { it.isClump }
        if (clumps > 0) {
            issues.add(VerifyIssue(VerifyFail.CLUMP, detected = clumps))
            return VerifyVerdict(
                passed = false,
                issues = issues,
                counts = emptyMap(),
                clumpCount = clumps,
                unknownCount = 0,
                assignments = blankAssignments,
                perDrugConfidence = emptyMap(),
            )
        }

        // 缺距离（理论上不该发生）给一个大代价，避免被错误地当成「完美匹配」。
        val missingCost = Double.MAX_VALUE / 4.0
        val tolerance = planned.tAccept
        val assignments = observations.map { obs ->
            val distance = obs.distances[planned.drugId] ?: missingCost
            PillAssignment(
                drugId = planned.drugId,
                drugName = planned.name,
                distance = distance,
                secondDistance = null,
                confidence = Confidence.score(distance, tolerance, null),
                margin = 1.0,
                colorOk = distance <= tolerance,
            )
        }

        // 分开拍时，画面里的颗数就是这味药的实测颗数（拍错药也照样记下来，供复盘）。
        val counts = if (planned.plannedCount > 0) {
            mapOf(planned.drugId to observations.size)
        } else {
            emptyMap()
        }
        when {
            observations.size < planned.plannedCount -> issues.add(
                VerifyIssue(
                    fail = VerifyFail.MISSING,
                    drugId = planned.drugId,
                    drugName = planned.name,
                    expected = planned.plannedCount,
                    detected = observations.size,
                )
            )
            observations.size > planned.plannedCount -> issues.add(
                VerifyIssue(
                    fail = VerifyFail.EXTRA,
                    drugId = planned.drugId,
                    drugName = planned.name,
                    expected = planned.plannedCount,
                    detected = observations.size,
                )
            )
        }

        val mismatched = assignments.filter { !it.colorOk }
        if (mismatched.isNotEmpty()) {
            val worst = mismatched.maxByOrNull { it.distance }
            issues.add(
                VerifyIssue(
                    fail = VerifyFail.COLOR_MISMATCH,
                    drugId = planned.drugId,
                    drugName = planned.name,
                    detected = mismatched.size,
                    distance = worst?.distance,
                    tolerance = tolerance,
                )
            )
        }

        // 形状辅助确认（《18》§5 P3）：分开拍时颜色多半分不开，形状是最后一道兜底。
        val shapeBad = assignments.indices.filter { i ->
            assignments[i].colorOk && isShapeMismatch(planned.shape, observations[i].shape)
        }
        if (shapeBad.isNotEmpty()) {
            issues.add(
                VerifyIssue(
                    fail = VerifyFail.SHAPE_MISMATCH,
                    drugId = planned.drugId,
                    drugName = planned.name,
                    detected = shapeBad.size,
                )
            )
        }

        // 颜色已经对不上的那颗由 COLOR_MISMATCH 负责，不再重复报「不确定」。
        val lowConfidence = assignments.count { it.colorOk && it.confidence < minConfidence }
        if (lowConfidence > 0) {
            issues.add(VerifyIssue(VerifyFail.LOW_CONFIDENCE, detected = lowConfidence))
        }

        val passed = issues.isEmpty() && planned.plannedCount > 0 &&
            observations.size == planned.plannedCount

        return VerifyVerdict(
            passed = passed,
            issues = issues,
            counts = counts,
            clumpCount = 0,
            unknownCount = mismatched.size,
            assignments = assignments,
            perDrugConfidence = mapOf(
                planned.drugId to (assignments.minOfOrNull { it.confidence } ?: 0.0)
            ),
        )
    }

    /**
     * 形状兜底判据：两边的粗形状都必须明确（圆片 / 长条）且相反才算对不上。
     * 形状在真机上不可复现（阴影会改变颜色掩膜），所以模糊区间、缺数据一律放过。
     */
    private fun isShapeMismatch(expected: ShapeSignature?, actual: ShapeSignature?): Boolean {
        if (expected == null || actual == null) return false
        val want = expected.shapeClass
        val got = actual.shapeClass
        return want != ShapeClass.UNKNOWN && got != ShapeClass.UNKNOWN && want != got
    }
}
