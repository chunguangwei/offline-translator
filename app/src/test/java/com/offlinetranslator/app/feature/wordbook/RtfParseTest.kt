package com.offlinetranslator.app.feature.wordbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtfParseTest {

    @Test
    fun `RTF 提纯：英文保留、控制字与字体颜色表剥离、GBK 中文还原`() {
        // 用实际 GBK 编码生成 \'hh 转义，保证与解码端一致（中文 RTF 常用 charset134=GBK）。
        val zh = "操作你好"
        val gbkHex = zh.toByteArray(charset("GBK")).joinToString("") { "\\'%02x".format(it.toInt() and 0xff) }
        val rtf = "{\\rtf1\\ansi\\ansicpg936" +
            "{\\fonttbl\\f0\\fnil\\fcharset134 STSongti-SC-Regular;}" +
            "{\\colortbl;\\red255\\green255\\blue255;}" +
            "\\f0\\fs24 Operations $gbkHex\\par hello}"

        val out = rtfToPlainText(rtf)

        assertTrue("英文应保留", out.contains("Operations"))
        assertTrue("英文应保留", out.contains("hello"))
        assertTrue("GBK 中文应还原", out.contains("操作你好"))
        assertFalse("不应残留控制字", out.contains("fcharset"))
        assertFalse("不应残留字体表", out.contains("fonttbl"))
        assertFalse("不应残留颜色表", out.contains("colortbl"))
        assertFalse("不应残留十六进制转义", out.contains("\\'"))
    }

    @Test
    fun `非 RTF 文本逻辑上不受影响（解析器对普通行只透传可见字符）`() {
        // 普通词表行（非 RTF）不会进入此函数；这里仅验证函数对纯文本字符透传。
        val out = rtfToPlainText("{\\rtf1 apple => 苹果\\par banana => 香蕉}")
        assertTrue(out.contains("apple => 苹果"))
        assertTrue(out.contains("banana => 香蕉"))
    }
}
