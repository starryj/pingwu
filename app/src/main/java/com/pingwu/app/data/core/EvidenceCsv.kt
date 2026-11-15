package com.pingwu.app.data.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 凭物 CSV 导出（M5）。
 *
 * 纯 Kotlin 构建文本，方便 JVM 单测；写文件交给 ViewModel。
 * 格式：UTF-8 BOM + CRLF，Excel 直接打开中文不乱码。
 */
object EvidenceCsv {

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /** 列：日期, 时间, 物品, 备注, 应拍数, 状态, 迟到, 拍摄时间, 照片, 来源 */
    private const val HEADER = "日期,时间,物品,备注,应拍数,状态,迟到,拍摄时间,照片,来源"

    fun build(
        items: List<ItemEntity>,
        occurrences: List<OccurrenceEntity>,
        evidence: List<EvidenceEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val itemById = items.associateBy { it.id }
        val evidenceByOccurrence = evidence.groupBy { it.occurrenceId }

        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append(HEADER).append("\r\n")

        occurrences
            .sortedWith(
                compareByDescending<OccurrenceEntity> { it.localDate }
                    .thenByDescending { it.timeLabel },
            )
            .forEach { occurrence ->
                val item = itemById[occurrence.itemId]
                val base = listOf(
                    occurrence.localDate,
                    occurrence.timeLabel,
                    item?.name.orEmpty(),
                    item?.note.orEmpty(),
                    (item?.countPerDose ?: 0).toString(),
                    stateLabel(occurrence.state),
                    // 只有「已验证」才有迟到与否；待拍/漏拍留空，Excel 里不刺眼
                    if (occurrence.state == "VERIFIED") {
                        if (occurrence.isLate) "是" else "否"
                    } else {
                        ""
                    },
                )
                val photos = evidenceByOccurrence[occurrence.id].orEmpty().sortedBy { it.capturedAt }
                if (photos.isEmpty()) {
                    // 没拍照：照片/来源留空，Excel 里一眼能看出这次没有凭物
                    sb.append(row(base + listOf("", "", ""))).append("\r\n")
                } else {
                    photos.forEach { evidence ->
                        val captured = timeFormat.format(
                            Instant.ofEpochMilli(evidence.capturedAt).atZone(zone),
                        )
                        sb.append(
                            row(
                                base + listOf(
                                    captured,
                                    evidence.photoKey.substringAfterLast('/'),
                                    if (evidence.source == "ONLINE_API") "在线" else "本机",
                                ),
                            ),
                        ).append("\r\n")
                    }
                }
            }
        return sb.toString()
    }

    fun fileName(now: Long = System.currentTimeMillis()): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
            .format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        return "凭物-导出-$stamp.csv"
    }

    private fun stateLabel(state: String): String = when (state) {
        "VERIFIED" -> "已验证"
        "MISSED" -> "漏拍"
        "SKIPPED" -> "未拍"
        else -> "待拍"
    }

    private fun row(cols: List<String>): String = cols.joinToString(",") { col ->
        if (col.contains(',') || col.contains('"') || col.contains('\n') || col.contains('\r')) {
            "\"" + col.replace("\"", "\"\"") + "\""
        } else {
            col
        }
    }
}
