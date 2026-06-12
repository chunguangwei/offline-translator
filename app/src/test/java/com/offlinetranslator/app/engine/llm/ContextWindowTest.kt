package com.offlinetranslator.app.engine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTest {

    @Test
    fun `预算内全量保留`() {
        val turns = listOf("user" to "你好", "assistant" to "你好！有什么可以帮你？")
        assertEquals(turns, ContextWindow.fitBudget(turns))
    }

    @Test
    fun `超预算从最旧裁剪`() {
        val turns = listOf(
            "user" to "a".repeat(2000),
            "assistant" to "b".repeat(2000),
            "user" to "c".repeat(500),
        )
        val fitted = ContextWindow.fitBudget(turns) // 预算 3000
        assertEquals(2, fitted.size)
        assertEquals("b".repeat(2000), fitted[0].second) // 最旧的被裁
        assertEquals("c".repeat(500), fitted[1].second)
    }

    @Test
    fun `单条超预算也至少保留一条`() {
        val turns = listOf("user" to "x".repeat(9000))
        assertEquals(1, ContextWindow.fitBudget(turns).size)
    }

    @Test
    fun `空列表安全`() {
        assertTrue(ContextWindow.fitBudget(emptyList()).isEmpty())
    }

    @Test
    fun `压缩触发条件`() {
        assertFalse(ContextWindow.shouldCompress(20, 100))      // 恰好 20 条不触发
        assertTrue(ContextWindow.shouldCompress(21, 100))       // 超 20 条触发
        assertFalse(ContextWindow.shouldCompress(3, 3000))      // 恰好预算不触发
        assertTrue(ContextWindow.shouldCompress(3, 3001))       // 超字数触发（会话"满了"）
    }
}
