package com.pingwu.app.ui

import com.pingwu.app.data.core.VerifyResult
import com.pingwu.app.data.core.localDateLabel
import com.pingwu.app.data.core.timeLabelOf
import com.pingwu.app.data.core.weekdayLabel
import com.pingwu.app.data.mock.DayStatus
import com.pingwu.app.data.mock.MockEvidenceDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一张凭据（票根）要显示的全部内容。
 *
 * 凭物台账里的一行、拍照成功后跳转的页面，都是同一张 [Proof]，
 * 只是从哪来、要不要「收下」不同。
 */
data class Proof(
    /** 票根编号，印在票面右上。 */
    val serial: String,
    val dateLabel: String,
    val weekday: String,
    val timeLabel: String,
    val itemName: String,
    val count: Int,
    val photos: List<String>,
    val status: DayStatus,
    /** 这一张是不是在线模型判的。 */
    val online: Boolean = false,
    val isLate: Boolean = false,
    /** 一次拍好几件时的逐件明细；只有一件时留空，按 [itemName] / [count] 显示。 */
    val lines: List<ProofLine> = emptyList(),
    /** 这次一起勾了、但没认出来的那几件——票面上留一句，提醒还在等。 */
    val pendingNote: String? = null,
) {
    val verified: Boolean get() = status == DayStatus.VERIFIED

    /** 票根正面那行名字：多件时合起来写，免得票面被撑爆。 */
    val displayName: String
        get() = if (lines.size > 1) lines.joinToString("、") { it.name } else itemName

    val displayCount: Int
        get() = if (lines.size > 1) lines.sumOf { it.count } else count
}

/** 多件票面上的一行。 */
data class ProofLine(val name: String, val count: Int)

/** 编号统一成 No. + 四位；0 是还没落库的兜底。 */
fun proofSerial(id: Long): String = "No." + id.coerceAtLeast(1).toString().padStart(4, '0')

/** 台账里的一行 → 票根。 */
fun MockEvidenceDay.toProof(): Proof = Proof(
    serial = proofSerial(if (evidenceId > 0) evidenceId else id.toLong()),
    dateLabel = dateLabel,
    weekday = weekday,
    timeLabel = timeLabel.substringBefore('·').trim().ifBlank { timeLabel },
    itemName = itemName,
    count = countPerDose,
    photos = photoKeys,
    status = status,
    isLate = isLate,
)

/** 刚拍完的一次 → 票根（拍照成功页用它）。 */
fun VerifyResult.toProof(    photoKey: String?,
    online: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): Proof {
    val at = if (capturedAt > 0) capturedAt else System.currentTimeMillis()
    val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return Proof(
        serial = proofSerial(evidenceId),
        dateLabel = localDateLabel(date),
        weekday = weekdayLabel(date, LocalDate.now(zone)),
        timeLabel = timeLabel.ifBlank { timeLabelOf(at, zone) },
        itemName = itemName,
        count = countPerDose,
        photos = listOfNotNull(photoKey),
        status = DayStatus.VERIFIED,
        online = online,
        isLate = isLate,
    )
}

/**
 * 一次快门拍好几件 → 一张多行票面。
 *
 * 只列真正记上账的那几件（[pendingNames] 是勾了却没认出来的，
 * 不在票面明细里，只在底下留一句「还差……」）。
 */
fun List<VerifyResult>.toMultiProof(
    photoKey: String?,
    online: Boolean,
    pendingNames: List<String> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
): Proof? {
    val first = firstOrNull() ?: return null
    val at = if (first.capturedAt > 0) first.capturedAt else System.currentTimeMillis()
    val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return Proof(
        serial = proofSerial(first.evidenceId),
        dateLabel = localDateLabel(date),
        weekday = weekdayLabel(date, LocalDate.now(zone)),
        timeLabel = first.timeLabel.ifBlank { timeLabelOf(at, zone) },
        itemName = first.itemName,
        count = first.countPerDose,
        photos = listOfNotNull(photoKey),
        status = DayStatus.VERIFIED,
        online = online,
        isLate = any { it.isLate },
        lines = map { ProofLine(it.itemName, it.countPerDose) },
        pendingNote = pendingNames.takeIf { it.isNotEmpty() }?.joinToString("、"),
    )
}

