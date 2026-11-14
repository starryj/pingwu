package com.pingwu.app.data.core

import com.pingwu.app.data.mock.DayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MapperTest {

    private val today = LocalDate.of(2026, 11, 12)
    private val zone = java.time.ZoneId.of("Asia/Shanghai")

    private fun occ(
        date: LocalDate,
        state: String,
        time: String = "21:00",
        id: Long = 0,
        verifiedAt: Long? = null,
    ): OccurrenceEntity = OccurrenceEntity(
        id = id,
        itemId = 1L,
        localDate = date.toString(),
        timeLabel = time,
        scheduledAt = date.atTime(21, 0).atZone(zone).toInstant().toEpochMilli(),
        state = state,
        verifiedAt = verifiedAt,
        windowMinutes = 30,
    )

    @Test
    fun `weekday label handles today yesterday and weekday`() {
        assertEquals("今天", weekdayLabel(today, today))
        assertEquals("昨天", weekdayLabel(today.minusDays(1), today))
        assertEquals("周一", weekdayLabel(LocalDate.of(2026, 11, 9), today))
        // 换一个「今天」参照，才能看到 11.12 的真实星期
        assertEquals("周四", weekdayLabel(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 20)))
        assertEquals("11.12", localDateLabel(today))
    }

    @Test
    fun `evidence days map states and group photos`() {
        val items = listOf(
            ItemEntity(id = 1, name = "盐酸二甲双胍片", countPerDose = 4),
        )
        val occurrences = listOf(
            occ(today, "PENDING", id = 10),
            occ(today.minusDays(1), "VERIFIED", id = 11, verifiedAt = 0),
            occ(today.minusDays(2), "MISSED", id = 12),
        )
        val evidence = listOf(
            EvidenceEntity(id = 1, occurrenceId = 11, itemId = 1, photoKey = "pw_photo_1"),
            EvidenceEntity(id = 2, occurrenceId = 11, itemId = 1, photoKey = "pw_photo_2"),
        )

        val days = occurrences.toEvidenceDays(evidence, items, today)
        assertEquals(3, days.size)
        // 按日期倒序：今天、昨天、前天
        assertEquals("今天", days[0].weekday)
        assertEquals(DayStatus.PENDING, days[0].status)
        assertTrue(days[0].photoKeys.isEmpty())

        assertEquals("昨天", days[1].weekday)
        assertEquals(DayStatus.VERIFIED, days[1].status)
        assertEquals(listOf("pw_photo_1", "pw_photo_2"), days[1].photoKeys)

        assertEquals("周二", days[2].weekday)
        assertEquals(DayStatus.MISSED, days[2].status)
        assertEquals("未拍", days[2].timeLabel)
    }

    @Test
    fun `pending past date becomes missed`() {
        val days = listOf(occ(today.minusDays(1), "PENDING")).toEvidenceDays(emptyList(), emptyList(), today)
        assertEquals(DayStatus.MISSED, days[0].status)
    }

    @Test
    fun `item maps times and count label`() {
        val item = ItemEntity(id = 3, name = "阿莫西林胶囊", note = "0.25g", countPerDose = 2, category = "MEDICINE")
        val reminder = ReminderEntity(id = 1, itemId = 3, timesCsv = "08:00,21:00")
        val ui = item.toUiItem(listOf(reminder))
        assertEquals("×2", ui.countLabel)
        assertEquals(listOf("08:00", "21:00"), ui.times)
        assertEquals("每天 08:00 · 21:00", ui.schedule)
        assertEquals("阿莫西林胶囊", ui.name)
    }
}
