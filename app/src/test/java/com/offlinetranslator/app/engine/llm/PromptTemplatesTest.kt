package com.offlinetranslator.app.engine.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class PromptTemplatesTest {

    private lateinit var original: Locale

    @Before
    fun saveLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    // ── trimAtStop ──

    @Test
    fun `无停止符原样返回`() {
        assertEquals("Hello, world", PromptTemplates.trimAtStop("Hello, world"))
    }

    @Test
    fun `在最早的停止符处截断`() {
        assertEquals("你好", PromptTemplates.trimAtStop("你好<turn|>多余内容<eos>"))
        assertEquals("abc", PromptTemplates.trimAtStop("abc<eos>def<turn|>"))
    }

    @Test
    fun `开头即停止符返回空串`() {
        assertEquals("", PromptTemplates.trimAtStop("<turn|>xxx"))
    }

    // ── chat 模板拼装 ──

    @Test
    fun `chat 包含系统提示与全部历史且当前轮收尾`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val history = listOf("user" to "第一问", "assistant" to "第一答")
        val prompt = PromptTemplates.chat(history, "第二问")
        // 系统提示在最前
        assertTrue(prompt.indexOf(PromptTemplates.chatSystem()) < prompt.indexOf("第一问"))
        // 历史按顺序齐全（双方消息都在 —— 用户曾要求的关键行为）
        assertTrue(prompt.indexOf("第一问") < prompt.indexOf("第一答"))
        assertTrue(prompt.indexOf("第一答") < prompt.indexOf("第二问"))
        // 以模型回合开符收尾（等待生成）
        assertTrue(prompt.endsWith("<|turn>model\n"))
    }

    @Test
    fun `历史不再被内部截断`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val history = (1..12).map { (if (it % 2 == 1) "user" else "assistant") to "消息$it" }
        val prompt = PromptTemplates.chat(history, "新问题")
        // 12 条历史全部在场（曾有 takeLast(8) 截断 bug）
        (1..12).forEach { assertTrue("缺少消息$it", prompt.contains("消息$it")) }
    }

    // ── 语言跟随 ──

    @Test
    fun `中文环境系统提示为中文`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertTrue(PromptTemplates.chatSystem().contains("简体中文"))
    }

    @Test
    fun `英文环境系统提示为英文`() {
        Locale.setDefault(Locale.US)
        assertTrue(PromptTemplates.chatSystem().contains("on-device"))
    }

    // ── 角色预设 ──

    @Test
    fun `角色列表与模板一一对应且互不相同`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val prompts = PromptTemplates.chatRoles.map { PromptTemplates.chatSystem(it) }
        assertEquals(PromptTemplates.chatRoles.size, prompts.toSet().size)
    }

    @Test
    fun `图片标注 - 空文案只留标记`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals("（发送了一张图片）", PromptTemplates.historyImageNote(""))
        assertTrue(PromptTemplates.historyImageNote("看这个").contains("看这个"))
    }
}
