package com.pingwu.app.util

import android.content.Context
import com.pingwu.app.R
import com.pingwu.app.data.model.DoseState
import com.pingwu.app.data.model.Verdict
import com.pingwu.app.data.repo.HistoryEntry
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 用药记录导出：UTF-8 BOM + CRLF，Excel 打开中文不乱码。 */
object HistoryCsv {

    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun write(context: Context, entries: List<HistoryEntry>): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "medication-history-" + fileStamp.format(LocalDateTime.now()) + ".csv")
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append(context.getString(R.string.csv_header)).append("\r\n")
        val zone = ZoneId.systemDefault()
        for (entry in entries) {
            val time = timeFormat.format(Instant.ofEpochMilli(entry.scheduledAt).atZone(zone))
            val state = stateLabel(context, entry.state)
            val verdict = when (entry.verdict) {
                Verdict.PASSED -> context.getString(R.string.csv_verdict_pass)
                Verdict.FAILED -> context.getString(R.string.csv_verdict_fail)
                null -> ""
            }
            val late = if (entry.isLate) context.getString(R.string.csv_late_yes) else ""
            // 需求要求导出「最低置信度」：没有证据（漏服/未服）时留空，Excel 里一眼能看出没拍照
            val confidence = entry.minConfidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: ""
            if (entry.items.isEmpty()) {
                sb.append(row(time, state, "", "", "", verdict, confidence, late)).append("\r\n")
            } else {
                for (item in entry.items) {
                    sb.append(
                        row(
                            time, state, item.name, item.planned.toString(), item.detected.toString(),
                            verdict, confidence, late,
                        )
                    ).append("\r\n")
                }
            }
        }
        file.writeText(sb.toString())
        return file
    }

    private fun row(vararg cols: String): String = cols.joinToString(",") { col ->
        if (col.contains(',') || col.contains('"') || col.contains('\n') || col.contains('\r')) {
            "\"" + col.replace("\"", "\"\"") + "\""
        } else {
            col
        }
    }

    /** 状态文案跟随界面语言（B8）：中文包用「已验证」，英文包用 Verified。 */
    private fun stateLabel(context: Context, state: DoseState): String = context.getString(
        when (state) {
            DoseState.PENDING -> R.string.history_state_pending
            DoseState.REMINDING -> R.string.history_state_reminding
            DoseState.SNOOZED -> R.string.history_state_snoozed
            DoseState.VERIFIED -> R.string.history_state_verified
            DoseState.MISSED -> R.string.history_state_missed
            DoseState.SKIPPED -> R.string.history_state_skipped
        }
    )
}
