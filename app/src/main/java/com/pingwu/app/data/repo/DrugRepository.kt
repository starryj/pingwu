package com.pingwu.app.data.repo

import com.pingwu.app.data.local.dao.DrugDao
import com.pingwu.app.data.local.dao.PillTemplateDao
import com.pingwu.app.data.local.entity.DrugEntity
import kotlinx.coroutines.flow.Flow

class DrugRepository(
    private val drugDao: DrugDao,
    private val templateDao: PillTemplateDao,
) {
    fun observeActive(): Flow<List<DrugEntity>> = drugDao.observeActive()

    /** 历史记录需要已归档药名，所以保留全量 */
    fun observeAllDrugs(): Flow<List<DrugEntity>> = drugDao.observeAllIncludingArchived()

    suspend fun get(id: Long): DrugEntity? = drugDao.getById(id)

    suspend fun getActive(): List<DrugEntity> = drugDao.getActive()

    suspend fun createManual(
        name: String,
        spec: String?,
        form: String?,
        presetShape: String?,
        presetColor: String?,
        presetColor2: String?,
        referenceMode: String = "NONE",
    ): Long = drugDao.upsert(
        DrugEntity(
            name = name.trim(),
            spec = spec?.trim()?.ifBlank { null },
            form = form?.trim()?.ifBlank { null },
            presetShape = presetShape,
            presetColor = presetColor,
            presetColor2 = presetColor2,
            referenceMode = referenceMode,
        )
    )

    suspend fun save(drug: DrugEntity): Long = drugDao.upsert(drug)

    suspend fun archive(id: Long) = drugDao.archive(id)

    /** 从「已归档」恢复；计划不会自动重新启用，需要用户自己再打开（避免悄悄开始提醒）。 */
    suspend fun unarchive(id: Long) = drugDao.unarchive(id)

    /** 供「计划必须已有视觉模板」门禁用；M4 实现模板后真正生效 */
    suspend fun hasActiveTemplate(drugId: Long): Boolean = templateDao.countActive(drugId) > 0
}
