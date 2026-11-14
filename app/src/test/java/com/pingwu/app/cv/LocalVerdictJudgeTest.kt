package com.pingwu.app.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVerdictJudgeTest {

    @Test
    fun `passes when count matches and quality is ok`() {
        val verdict = LocalVerdictJudge.judge(count = 4, expected = 4, qualityOk = true)
        assertTrue(verdict.pass)
        assertEquals(null, verdict.reason)
    }

    @Test
    fun `rejects when count differs`() {
        val verdict = LocalVerdictJudge.judge(count = 3, expected = 4, qualityOk = true)
        assertFalse(verdict.pass)
        assertEquals("本机看到 3颗，和应拍的 4颗对不上。", verdict.reason)
    }

    @Test
    fun `uses the item unit for non medicine items`() {
        val verdict = LocalVerdictJudge.judge(count = 1, expected = 2, qualityOk = true, unit = "件")
        assertFalse(verdict.pass)
        assertEquals("本机看到 1件，和应拍的 2件对不上。", verdict.reason)
    }

    @Test
    fun `rejects when quality is poor even with right count`() {
        val verdict = LocalVerdictJudge.judge(count = 4, expected = 4, qualityOk = false, qualityIssue = "TOO_DARK")
        assertFalse(verdict.pass)
        assertEquals("数量对上了，但照片不够清楚（TOO_DARK）。", verdict.reason)
    }

    @Test
    fun `quality reason falls back when issue is unknown`() {
        val verdict = LocalVerdictJudge.judge(count = 2, expected = 2, qualityOk = false)
        assertFalse(verdict.pass)
        assertEquals("数量对上了，但照片不够清楚。", verdict.reason)
    }
}
