package com.offlinetranslator.app.feature.wordbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupExistingTest {

    private fun row(id: Long, key: String, box: Int = 0, createdAt: Long = 0) =
        DedupRow(id, key, box, createdAt)

    @Test
    fun `同英文键多条只保留box最高的返回其余id`() {
        val rows = listOf(
            row(1, "apple", box = 0, createdAt = 100),
            row(2, "apple", box = 3, createdAt = 200),
            row(3, "apple", box = 1, createdAt = 300),
        )
        val remove = entriesToRemoveForDedup(rows)
        assertEquals(setOf(1L, 3L), remove) // 保留 box 最高的 id=2
    }

    @Test
    fun `box并列时保留createdAt最早的返回其余`() {
        val rows = listOf(
            row(1, "apple", box = 2, createdAt = 300),
            row(2, "apple", box = 2, createdAt = 100), // 最早，保留
            row(3, "apple", box = 2, createdAt = 200),
        )
        val remove = entriesToRemoveForDedup(rows)
        assertEquals(setOf(1L, 3L), remove)
    }

    @Test
    fun `无重复返回空集`() {
        val rows = listOf(
            row(1, "apple", box = 0),
            row(2, "banana", box = 1),
            row(3, "cherry", box = 2),
        )
        assertTrue(entriesToRemoveForDedup(rows).isEmpty())
    }

    @Test
    fun `多组重复独立处理`() {
        val rows = listOf(
            row(1, "apple", box = 0, createdAt = 100),
            row(2, "apple", box = 3, createdAt = 200), // apple 保留
            row(3, "banana", box = 1, createdAt = 50),  // banana 保留(box 高)
            row(4, "banana", box = 0, createdAt = 60),
            row(5, "cherry", box = 0, createdAt = 10),   // cherry 唯一，保留
        )
        val remove = entriesToRemoveForDedup(rows)
        assertEquals(setOf(1L, 4L), remove)
    }

    @Test
    fun `单条输入返回空集`() {
        assertTrue(entriesToRemoveForDedup(listOf(row(1, "apple", box = 0))).isEmpty())
    }

    @Test
    fun `空输入返回空集`() {
        assertTrue(entriesToRemoveForDedup(emptyList()).isEmpty())
    }
}
