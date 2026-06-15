package com.offlinetranslator.app.feature.wordbook

import org.junit.Assert.assertEquals
import org.junit.Test

class DedupTest {

    private fun d(english: String, chinese: String = "中文", note: String = "") =
        VocabDraft(english, chinese, note)

    @Test
    fun `批次内同英文（任意大小写空白）只保留首次出现`() {
        val input = listOf(
            d("apple", chinese = "苹果"),
            d("Apple", chinese = "苹果2"),
            d(" apple ", chinese = "苹果3"),
        )
        val out = dedupDrafts(input, emptySet())
        assertEquals(1, out.size)
        assertEquals("苹果", out[0].chinese) // 保留第一条
    }

    @Test
    fun `existingKeys（归一化）排除草稿`() {
        val input = listOf(d("Apple"), d("banana"))
        val out = dedupDrafts(input, setOf("apple"))
        assertEquals(listOf("banana"), out.map { it.english })
    }

    @Test
    fun `大小写与空白不敏感 折叠为一条`() {
        val input = listOf(d("Apple"), d(" apple "), d("APPLE"))
        val out = dedupDrafts(input, emptySet())
        assertEquals(1, out.size)
        assertEquals("Apple", out[0].english)
    }

    @Test
    fun `空或纯空白英文被丢弃`() {
        val input = listOf(d(""), d("   "), d("hello"))
        val out = dedupDrafts(input, emptySet())
        assertEquals(listOf("hello"), out.map { it.english })
    }

    @Test
    fun `保持原始顺序`() {
        val input = listOf(d("cat"), d("apple"), d("banana"), d("Apple"))
        val out = dedupDrafts(input, emptySet())
        assertEquals(listOf("cat", "apple", "banana"), out.map { it.english })
    }

    @Test
    fun `空输入返回空输出`() {
        assertEquals(emptyList<VocabDraft>(), dedupDrafts(emptyList(), emptySet()))
    }

    @Test
    fun `空 existingKeys 时所有词通过（仍去重）`() {
        val input = listOf(d("one"), d("two"), d("two"), d("three"))
        val out = dedupDrafts(input, emptySet())
        assertEquals(listOf("one", "two", "three"), out.map { it.english })
    }
}
