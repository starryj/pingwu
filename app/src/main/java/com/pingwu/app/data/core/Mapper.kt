package com.pingwu.app.data.core

import com.pingwu.app.R
import com.pingwu.app.data.mock.DayStatus
import com.pingwu.app.data.mock.ItemCategory
import com.pingwu.app.data.mock.MockEvidenceDay
import com.pingwu.app.data.mock.MockItem
import com.pingwu.app.data.mock.MockPhoto
import com.pingwu.app.data.mock.categoryOf
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 照片 key → 工程内 drawable；本机绝对路径与空值返回 0（调用方自己判断）。 */
fun drawableFor(photoKey: String): Int = when (photoKey) {
    "pw_photo_1" -> R.drawable.pw_photo_1
    "pw_photo_2" -> R.drawable.pw_photo_2
    "pw_photo_3" -> R.drawable.pw_photo_3
    "pw_thumb" -> R.drawable.pw_thumb
    else -> 0
}

private val DATE_LABEL = DateTimeFormatter.ofPattern("MM.dd")
private val WEEK_LABEL = DateTimeFormatter.ofPattern("HH:mm")

fun localDateLabel(date: LocalDate): String = date.format(DATE_LABEL)

fun weekdayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    date == today -> "今天"
    date == today.minusDays(1) -> "昨天"
    else -> when (date.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
}

/** 把 OccurrenceEntity + 当时的 evidence 映射成设计稿用的展示结构。 */
fun List<OccurrenceEntity>.toEvidenceDays(
    evidence: List<EvidenceEntity>,
    items: List<ItemEntity>,
    today: LocalDate = LocalDate.now(),
): List<MockEvidenceDay> {
    val itemById = items.associateBy { it.id }
    val photosByOccurrence = evidence.groupBy { it.occurrenceId }
    return sortedWith(compareByDescending<OccurrenceEntity> { it.localDate }.thenByDescending { it.timeLabel })
        .mapIndexed { index, occ ->
            val date = runCatching { LocalDate.parse(occ.localDate) }.getOrElse { today }
            val status = when (occ.state) {
                "VERIFIED" -> DayStatus.VERIFIED
                "MISSED" -> DayStatus.MISSED
                "SKIPPED" -> DayStatus.MISSED
                else -> if (date.isBefore(today)) DayStatus.MISSED else DayStatus.PENDING
            }
            val timeText = when {
                status == DayStatus.PENDING -> "${occ.timeLabel} · 今晚"
                status == DayStatus.MISSED -> "未拍"
                occ.verifiedAt != null -> Instant.ofEpochMilli(occ.verifiedAt)
                    .atZone(ZoneId.systemDefault()).toLocalTime().format(WEEK_LABEL)
                else -> occ.timeLabel
            }
            val item = itemById[occ.itemId]
            val occEvidence = photosByOccurrence[occ.id].orEmpty()
            MockEvidenceDay(
                id = index + 1,
                dateLabel = localDateLabel(date),
                weekday = weekdayLabel(date, today),
                itemId = occ.itemId.toInt(),
                itemName = item?.name ?: "（已删除）",
                photoKeys = occEvidence.map { it.photoKey },
                status = status,
                timeLabel = timeText,
                occurrenceId = occ.id,
                evidenceId = occEvidence.firstOrNull()?.id ?: 0,
                countPerDose = item?.countPerDose ?: 1,
                isLate = occ.isLate,
                isoDate = date.toString(),
                itemArchived = item?.archived == true,
            )
        }
}

fun ItemEntity.toUiItem(reminders: List<ReminderEntity>, subjectCount: Int = 1): MockItem {
    val reminder = reminders.firstOrNull()
    return MockItem(
        id = id.toInt(),
        name = name,
        note = note,
        countLabel = "×$countPerDose",
        count = countPerDose,
        schedule = ReminderRule.summaryOf(reminders),
        times = reminder?.let { ReminderRule.times(it) } ?: emptyList(),
        rule = reminder?.rule ?: ReminderRule.DAILY,
        daysCsv = reminder?.daysCsv.orEmpty(),
        onceDate = reminder?.onceDate.orEmpty(),
        windowMinutes = reminder?.windowMinutes ?: 30,
        templateReady = templateReady,
        category = categoryOf(category),
        coverRes = if (coverPhotoKey.isBlank()) 0 else drawableFor(coverPhotoKey),
        coverPhotoKey = coverPhotoKey,
        subjectCount = subjectCount,
    )
}

/** "3,7" → setOf(3L, 7L)；空串得到空集合。 */
fun String.subjectIdSet(): Set<Long> =
    split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

/** 解析 "08:00,21:00" → 当天的 epoch ms 列表。 */
fun timesOf(timesCsv: String, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Long> =
    timesCsv.split(',').mapNotNull { raw ->
        runCatching {
            val t = LocalTime.parse(raw.trim())
            date.atTime(t).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

fun timeLabelOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().format(WEEK_LABEL)

fun todayIso(): String = LocalDate.now().toString()

fun nowMs(): Long = System.currentTimeMillis()
