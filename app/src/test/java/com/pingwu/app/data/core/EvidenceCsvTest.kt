package com.pingwu.app.data.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceCsvTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private val item = ItemEntity(
        id = 1,
        name = "盐酸二甲双胍片",
        note = "0.25g",
        countPerDose = 4,
    )

    private fun occurrence(
        id: Long,
        time: String,
        state: String,
        date: String = "2026-11-13",
        late: Boolean = false,
    ) = OccurrenceEntity(
        id = id,
        itemId = 1,
        localDate = date,
        timeLabel = time,
        scheduledAt = 0,
        state = state,
        isLate = late,
    )

    private fun epoch(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun `header is stable and rows are newest first`() {
        val verified = occurrence(10, "08:00", "VERIFIED")
        val missed = occurrence(11, "21:00", "MISSED")
        val evidence = EvidenceEntity(
            occurrenceId = 10,
            itemId = 1,
            photoKey = "/data/x/evidence/2026-11-13/dose-1-080102.jpg",
            capturedAt = epoch("2026-11-13T08:01:02+08:00"),
            source = "LOCAL",
        )

        val csv = EvidenceCsv.build(listOf(item), listOf(verified, missed), listOf(evidence), zone)
        val lines = csv.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }

        assertEquals("日期,时间,物品,备注,应拍数,状态,迟到,拍摄时间,照片,来源", lines[0])
        assertEquals(3, lines.size)
        // 同一天 21:00 排在 08:00 前面
        assertTrue(lines[1].startsWith("2026-11-13,21:00,盐酸二甲双胍片,0.25g,4,漏拍,,,,"))
        assertTrue(
            lines[2].startsWith(
                "2026-11-13,08:00,盐酸二甲双胍片,0.25g,4,已验证,否,2026-11-13 08:01,dose-1-080102.jpg,本机",
            ),
        )
    }

    @Test
    fun `late verified row and online source are labelled`() {
        val late = occurrence(12, "21:00", "VERIFIED", late = true)
        val evidence = EvidenceEntity(
            occurrenceId = 12,
            itemId = 1,
            photoKey = "/data/x/evidence/2026-11-13/dose-1-213500.jpg",
            capturedAt = epoch("2026-11-13T21:35:00+08:00"),
            source = "ONLINE_API",
        )

        val lines = EvidenceCsv.build(listOf(item), listOf(late), listOf(evidence), zone)
            .removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }

        assertTrue(lines[1].contains("已验证,是,"))
        assertTrue(lines[1].endsWith(",在线"))
    }

    @Test
    fun `commas and quotes in item name are escaped`() {
        val tricky = item.copy(name = "维生素,C \"片\"")
        val lines = EvidenceCsv.build(listOf(tricky), listOf(occurrence(13, "08:00", "PENDING")), emptyList(), zone)
            .removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }

        assertTrue(lines[1].contains("\"维生素,C \"\"片\"\"\""))
    }

    @Test
    fun `file name uses local time`() {
        val name = EvidenceCsv.fileName(epoch("2026-11-13T09:05:00+08:00"))
        assertEquals("凭物-导出-20261113-0905.csv", name)
    }
}
