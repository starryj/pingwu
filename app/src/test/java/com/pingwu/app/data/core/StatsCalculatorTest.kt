package com.pingwu.app.data.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsCalculatorTest {

    private val today = LocalDate.of(2026, 11, 12)
    private val zone = java.time.ZoneId.of("Asia/Shanghai")

    private fun occ(date: LocalDate, state: String, itemId: Long = 1L): OccurrenceEntity =
        OccurrenceEntity(
            id = 0,
            itemId = itemId,
            localDate = date.toString(),
            timeLabel = "21:00",
            scheduledAt = date.atTime(21, 0).atZone(zone).toInstant().toEpochMilli(),
            state = state,
        )

    private fun ev(occId: Long, itemId: Long = 1L): EvidenceEntity =
        EvidenceEntity(id = 0, occurrenceId = occId, itemId = itemId, photoKey = "pw_photo_1")

    @Test
    fun `today counts and streak include today`() {
        val occurrences = listOf(
            occ(today, "VERIFIED"),
            occ(today, "PENDING"),
            occ(today.minusDays(1), "VERIFIED"),
            occ(today.minusDays(2), "VERIFIED"),
            occ(today.minusDays(3), "MISSED"),
        )
        val stats = StatsCalculator.compute(occurrences, listOf(ev(1), ev(2)), today)

        assertEquals(1, stats.todayDone)
        assertEquals(2, stats.todayTotal)
        assertEquals(3, stats.streakDays)
        assertEquals(1, stats.missedCount)
        // 3 verified / (3 verified + 1 missed) = 75%
        assertEquals(75, stats.onTimePercent)
        assertEquals(2, stats.evidenceCount)
        assertEquals(listOf(-1, -1, -1, 0, 1, 1, 1), stats.weekFlags)
    }

    @Test
    fun `streak starts from yesterday when today not verified`() {
        val occurrences = listOf(
            occ(today, "PENDING"),
            occ(today.minusDays(1), "VERIFIED"),
            occ(today.minusDays(2), "VERIFIED"),
            occ(today.minusDays(3), "MISSED"),
        )
        val stats = StatsCalculator.compute(occurrences, emptyList(), today)

        assertEquals(2, stats.streakDays)
        assertEquals(0, stats.todayDone)
        assertEquals(1, stats.todayTotal)
        // 今天还排着、还没拍：周条最后一格是「待拍」，不是「按时」
        assertEquals(2, stats.weekFlags.last())
    }

    @Test
    fun `empty database is neutral`() {
        val stats = StatsCalculator.compute(emptyList(), emptyList(), today)
        assertEquals(0, stats.evidenceCount)
        assertEquals(100, stats.onTimePercent)
        assertEquals(0, stats.streakDays)
        assertEquals(0, stats.todayTotal)
        assertEquals(7, stats.weekFlags.size)
        // 新装还没有任何安排：周条全暗，不把「没安排」算成「漏」
        assertTrue(stats.weekFlags.all { it == -1 })
        assertEquals(0, stats.scheduledCount)
    }

    @Test
    fun `skipped counts as missed`() {
        val occurrences = listOf(
            occ(today, "VERIFIED"),
            occ(today.minusDays(1), "SKIPPED"),
        )
        val stats = StatsCalculator.compute(occurrences, emptyList(), today)
        assertEquals(1, stats.missedCount)
        assertEquals(50, stats.onTimePercent)
    }
}
