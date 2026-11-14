package com.pingwu.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiVerdictParserTest {

    @Test
    fun parsesPlainJson() {
        val verdict = AiVerdictParser.parse(
            """{"pass": true, "count": 4, "confidence": "high", "reason": "四片白色药片都在画面里"}""",
        )
        assertTrue(verdict!!.pass)
        assertEquals(4, verdict.count)
        assertEquals("high", verdict.confidence)
        assertEquals("四片白色药片都在画面里", verdict.reason)
    }

    @Test
    fun parsesCodeFencedJson() {
        val verdict = AiVerdictParser.parse(
            """
            ```json
            {"pass": false, "count": 2, "confidence": "medium", "reason": "只看到两片，数量不对"}
            ```
            """.trimIndent(),
        )
        assertFalse(verdict!!.pass)
        assertEquals(2, verdict.count)
        assertEquals("只看到两片，数量不对", verdict.reason)
    }

    @Test
    fun rejectsAnswerWithoutPassField() {
        assertNull(AiVerdictParser.parse("""{"count": 4, "reason": "看起来像药片"}"""))
        assertNull(AiVerdictParser.parse("照片里有四片白色药片"))
    }

    @Test
    fun unescapesQuotesAndNewlinesInReason() {
        val verdict = AiVerdictParser.parse(
            """{"pass": true, "reason": "看到\"四片\"\n都清楚"}""",
        )
        assertEquals("看到\"四片\"\n都清楚", verdict!!.reason)
    }

    @Test
    fun missingCountIsNull() {
        val verdict = AiVerdictParser.parse("""{"pass": true, "reason": "对上了"}""")
        assertTrue(verdict!!.pass)
        assertNull(verdict.count)
        assertNull(verdict.confidence)
    }

    @Test
    fun emptyReasonGetsFallback() {
        val ok = AiVerdictParser.parse("""{"pass": true, "reason": ""}""")
        assertEquals("看起来对上了。", ok!!.reason)
        val fail = AiVerdictParser.parse("""{"pass": false}""")
        assertEquals("没对上。", fail!!.reason)
    }
}
