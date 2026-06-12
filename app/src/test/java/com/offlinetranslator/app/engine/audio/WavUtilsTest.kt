package com.offlinetranslator.app.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavUtilsTest {

    @Test
    fun `wav 头为 44 字节且魔数正确`() {
        val pcm = ByteArray(320) // 10ms @16k mono 16bit
        val wav = pcmToWav(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
    }

    @Test
    fun `头部字段 - 采样率与数据长度小端正确`() {
        val pcm = ByteArray(1000)
        val wav = pcmToWav(pcm)
        fun le32(off: Int): Int =
            (wav[off].toInt() and 0xff) or ((wav[off + 1].toInt() and 0xff) shl 8) or
                ((wav[off + 2].toInt() and 0xff) shl 16) or ((wav[off + 3].toInt() and 0xff) shl 24)
        assertEquals(16_000, le32(24))      // sample rate
        assertEquals(pcm.size, le32(40))    // data chunk size
        assertEquals(pcm.size + 36, le32(4)) // riff size
    }

    @Test
    fun `peakPcm - 静音为 0 满幅接近 1`() {
        assertEquals(0f, peakPcm(ByteArray(64)), 0.0001f)
        // 一个满幅样本（little-endian 0x7FFF）
        val loud = ByteArray(64)
        loud[10] = 0xFF.toByte()
        loud[11] = 0x7F
        assertTrue(peakPcm(loud) > 0.99f)
    }

    @Test
    fun `peakPcm - 空与单字节安全`() {
        assertEquals(0f, peakPcm(ByteArray(0)), 0.0001f)
        assertEquals(0f, peakPcm(ByteArray(1)), 0.0001f)
    }
}
