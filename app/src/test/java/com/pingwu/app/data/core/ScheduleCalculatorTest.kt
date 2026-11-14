package com.pingwu.app.data.core

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, 13, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `今天还有一次就排今天`() {
        val next = ScheduleCalculator.nextAfter(listOf("08:00", "21:00"), at(9, 0), zone)
        assertEquals(at(21, 0), next)
    }

    @Test
    fun `今天的都过了就排明天最早`() {
        val next = ScheduleCalculator.nextAfter(listOf("08:00", "21:00"), at(22, 0), zone)
        assertEquals(
            LocalDateTime.of(2026, 11, 14, 8, 0).atZone(zone).toInstant().toEpochMilli(),
            next,
        )
    }

    @Test
    fun `空时刻返回 null`() {
        assertNull(ScheduleCalculator.nextAfter(emptyList(), at(9, 0), zone))
        assertNull(ScheduleCalculator.nextAfter(listOf(" ", "not-a-time"), at(9, 0), zone))
    }

    @Test
    fun `重复时刻只算一次`() {
        val next = ScheduleCalculator.nextAfter(listOf("21:00", "21:00"), at(9, 0), zone)
        assertEquals(at(21, 0), next)
    }

    @Test
    fun `界面轮询不到点不算到点`() {
        // 20:34:30 时，20:35 那一条不该算到点（作者 2026-11-13 反馈的提前一分钟）。
        val scheduled = at(20, 35)
        val now = at(20, 34) + 30_000L
        assertEquals(false, ScheduleCalculator.isDue(scheduled, now))
        assertEquals(true, ScheduleCalculator.isDue(scheduled, at(20, 35)))
        assertEquals(true, ScheduleCalculator.isDue(scheduled, at(20, 36)))
    }

    @Test
    fun `闹钟接收端只给五秒余量`() {
        val scheduled = at(20, 35)
        // 早 3 秒投递要能认出来，早 10 秒不认（否则又会提前一整分钟）。
        assertEquals(true, ScheduleCalculator.isDue(scheduled, scheduled - 3_000L, leadMs = 5_000L))
        assertEquals(false, ScheduleCalculator.isDue(scheduled, scheduled - 10_000L, leadMs = 5_000L))
    }
}
