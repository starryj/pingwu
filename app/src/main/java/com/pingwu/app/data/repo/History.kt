package com.pingwu.app.data.repo

import com.pingwu.app.data.model.DoseState
import com.pingwu.app.data.model.Verdict

/** 历史记录里的一味药 */
data class HistoryItem(
    val drugId: Long,
    val name: String,
    val planned: Int,
    val detected: Int,
)

/** 一个已完成/已过期的剂量组（历史页与 CSV 用） */
data class HistoryEntry(
    val id: Long,
    val scheduledAt: Long,
    val state: DoseState,
    val items: List<HistoryItem>,
    val verdict: Verdict?,
    val isLate: Boolean,
    val photoPath: String? = null,
    /** 分药拍摄留下的全部证据照片（docs/18 P2）；单拍时只有一张。 */
    val photoPaths: List<String> = emptyList(),
    val detectJson: String? = null,
    val minConfidence: Double? = null,
)