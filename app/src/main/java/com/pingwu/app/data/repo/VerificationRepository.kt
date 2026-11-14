package com.pingwu.app.data.repo

import com.pingwu.app.cv.DetectJson
import com.pingwu.app.cv.DetectResult
import com.pingwu.app.cv.PillObservation
import com.pingwu.app.cv.RegistrationCheck
import com.pingwu.app.cv.RegistrationOutcome
import com.pingwu.app.cv.TemplateEntry
import com.pingwu.app.cv.TemplateFeatureSet
import com.pingwu.app.cv.TemplateFeatures
import com.pingwu.app.cv.TemplateJson
import com.pingwu.app.data.local.dao.DrugDao
import com.pingwu.app.data.local.dao.DoseGroupDao
import com.pingwu.app.data.local.dao.DoseItemDao
import com.pingwu.app.data.local.dao.EvidenceDao
import com.pingwu.app.data.local.dao.PillTemplateDao
import com.pingwu.app.data.local.entity.EvidenceEntity
import com.pingwu.app.data.local.entity.PillTemplateEntity
import com.pingwu.app.data.model.DoseState
import com.pingwu.app.domain.verify.ClassifiedPill
import com.pingwu.app.domain.verify.VerifyVerdict
import org.json.JSONArray

/** 注册结果：outcome.ok=false 时 UI 需要展示具体原因（样本不足/太散/易混淆）。 */
data class TemplateRegistration(
    val drugId: Long,
    val version: Int,
    val outcome: RegistrationOutcome,
)

/**
 * 模板注册与验证落库（M4 闭环）：
 * - 注册：校验样本 → 与其它药比对 → 版本号 +1 → 旧版置 inactive → 存 featuresJson；
 * - 验证：写 EVIDENCE（detectJson）、更新 DOSE_ITEM.detectedCount、把剂量组置 VERIFIED。
 */
class VerificationRepository(
    private val drugDao: DrugDao,
    private val templateDao: PillTemplateDao,
    private val evidenceDao: EvidenceDao,
    private val doseItemDao: DoseItemDao,
    private val doseGroupDao: DoseGroupDao,
) {

    /** 取出这些药当前生效的模板；没有模板的药不会出现在结果里（→ 判定为未知药丸）。 */
    suspend fun loadEntries(drugIds: List<Long>): List<TemplateEntry> {
        val out = ArrayList<TemplateEntry>()
        for (drugId in drugIds.distinct()) {
            val drug = drugDao.getById(drugId) ?: continue
            val entity = templateDao.getActive(drugId) ?: continue
            val features = TemplateJson.decode(entity.featuresJson) ?: continue
            out.add(TemplateEntry(drugId, drug.name, features))
        }
        return out
    }

    /** 当前生效模板的特征（药品详情页展示「颜色签名」用，docs/18 M4）。 */
    suspend fun activeTemplateFeatures(drugId: Long): TemplateFeatureSet? {
        val entity = templateDao.getActive(drugId) ?: return null
        return TemplateJson.decode(entity.featuresJson)
    }

    /** 模板数据损坏（JSON 解不出来）的药名；用于给用户明确提示「重新注册」。 */
    suspend fun brokenTemplateNames(drugIds: List<Long>): List<String> {
        val out = ArrayList<String>()
        for (drugId in drugIds.distinct()) {
            val drug = drugDao.getById(drugId) ?: continue
            val entity = templateDao.getActive(drugId) ?: continue
            if (TemplateJson.decode(entity.featuresJson) == null) out.add(drug.name)
        }
        return out
    }

    suspend fun evaluateRegistration(
        drugId: Long,
        samples: List<List<PillObservation>>,
    ): RegistrationOutcome {
        val others = ArrayList<Pair<String, TemplateFeatureSet>>()
        for (drug in drugDao.getActive()) {
            if (drug.id == drugId) continue
            val entity = templateDao.getActive(drug.id) ?: continue
            val features = TemplateJson.decode(entity.featuresJson) ?: continue
            others.add(drug.name to features)
        }
        return RegistrationCheck.evaluate(samples, others)
    }

    /**
     * 全药同拍批量预检：先逐药校验（样本数/散度/与既有药相似），再批内两两比相似。
     * 批内相似度不在这里拦住的话，顺序保存会出现「前一半写进库、后一半报错」的中间态。
     */
    suspend fun evaluateBatch(
        drugIds: List<Long>,
        names: Map<Long, String>,
        samples: Map<Long, List<List<PillObservation>>>,
    ): Map<Long, RegistrationOutcome> {
        val single = LinkedHashMap<Long, RegistrationOutcome>()
        for (id in drugIds) {
            single[id] = evaluateRegistration(id, samples[id].orEmpty())
        }
        val result = LinkedHashMap<Long, RegistrationOutcome>()
        for (id in drugIds) {
            val base = single[id] ?: continue
            val features = base.features
            if (features == null) {
                result[id] = base
                continue
            }
            val similar = ArrayList(base.similarTo)
            for (other in drugIds) {
                if (other == id) continue
                val otherFeatures = single[other]?.features ?: continue
                if (TemplateFeatures.similarity(features, otherFeatures) <
                    RegistrationCheck.SIMILARITY_FACTOR * features.tAccept
                ) {
                    similar.add(names[other] ?: other.toString())
                }
            }
            result[id] = base.copy(similarTo = similar.distinct())
        }
        return result
    }

    /**
     * 注册模板；返回 null 表示药品不存在。
     * outcome.ok=false 时不会写库，调用方应让用户重拍/改参考方式。
     */
    suspend fun saveTemplate(
        drugId: Long,
        samples: List<List<PillObservation>>,
        photoPaths: List<String>,
        referenceMode: String,
        scaleRefMm: Double? = null,
        batch: String? = null,
    ): TemplateRegistration? {
        if (drugDao.getById(drugId) == null) return null
        val outcome = evaluateRegistration(drugId, samples)
        val features = outcome.features
        if (features == null || !outcome.ok) {
            return TemplateRegistration(drugId, templateDao.maxVersion(drugId) ?: 0, outcome)
        }
        val version = (templateDao.maxVersion(drugId) ?: 0) + 1
        templateDao.deactivateAll(drugId)
        templateDao.upsert(
            PillTemplateEntity(
                drugId = drugId,
                version = version,
                batch = batch,
                featuresJson = TemplateJson.encode(features),
                tAccept = features.tAccept,
                tUnknown = features.tUnknown,
                samplePhotosJson = JSONArray(photoPaths).toString(),
                referenceMode = referenceMode,
                scaleRefMm = scaleRefMm,
                active = 1,
            )
        )
        return TemplateRegistration(drugId, version, outcome)
    }

    /** 当前生效模板用的参考方式；没有模板返回 null。 */
    suspend fun activeTemplateReferenceMode(drugId: Long): String? =
        templateDao.getActive(drugId)?.referenceMode

    /** 通过：写证据 + 更新检测颗数 + 剂量组置 VERIFIED（迟到补拍 isLate 由落库时的状态决定）。 */
    suspend fun markVerified(
        doseId: Long,
        photoPath: String,
        capturedAt: Long,
        result: DetectResult,
        pills: List<ClassifiedPill>,
        verdict: VerifyVerdict,
    ): Long = markVerified(
        doseId = doseId,
        photoPaths = listOf(photoPath),
        capturedAt = capturedAt,
        detectJson = DetectJson.encode(result, pills),
        verdict = verdict,
    )

    /**
     * 分药拍摄（docs/18 P2）：一次验证可能留下多张证据照片（每味药一张）。
     * [detectJson] 由调用方汇总（[DetectJson.encodeSplit]），[photoPaths] 按拍摄顺序落库。
     */
    suspend fun markVerified(
        doseId: Long,
        photoPaths: List<String>,
        capturedAt: Long,
        detectJson: String,
        verdict: VerifyVerdict,
    ): Long {
        val current = doseGroupDao.getById(doseId)
        val late = current?.state == DoseState.MISSED.name
        if (current != null && current.state == DoseState.VERIFIED.name) {
            // 幂等：已通过的剂量组不再插第二条证据（C5 重复补拍只保留一条）
            return evidenceDao.getForGroup(doseId).firstOrNull()?.id ?: -1L
        }
        val evidenceId = evidenceDao.insert(
            EvidenceEntity(
                doseGroupId = doseId,
                photoPath = photoPaths.firstOrNull(),
                photoPathsJson = if (photoPaths.size > 1) JSONArray(photoPaths).toString() else null,
                capturedAt = capturedAt,
                detectJson = detectJson,
                verdict = if (verdict.passed) "PASSED" else "FAILED",
                isLate = if (late) 1 else 0,
            )
        )
        for ((drugId, count) in verdict.counts) {
            doseItemDao.updateDetectedCount(doseId, drugId, count)
        }
        val group = doseGroupDao.getById(doseId)
        if (group != null && verdict.passed) {
            doseGroupDao.update(
                group.copy(
                    state = DoseState.VERIFIED.name,
                    verifyAt = capturedAt,
                    nextNotifyAt = null,
                    finalReminderAt = null,
                )
            )
        }
        return evidenceId
    }
}
