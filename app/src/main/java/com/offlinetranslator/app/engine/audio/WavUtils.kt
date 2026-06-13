package com.offlinetranslator.app.engine.audio

import java.io.ByteArrayOutputStream

/**
 * 把小端 PCM16 原始字节封装成最小 RIFF/WAVE 容器（44 字节头 + 数据）。
 * LiteRT-LM 的音频输入需要 WAV 头，而非裸 PCM。
 * 实现抽取自已在生产中验证过的 VoiceViewModel.pcmToWav。
 */
fun pcmToWav(
    pcm: ByteArray,
    sampleRate: Int = 16_000,
    channels: Int = 1,
    bitsPerSample: Int = 16,
): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val totalDataLen = pcm.size + 36
    val out = ByteArrayOutputStream(44 + pcm.size)
    fun w16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
    fun w32(v: Int) {
        out.write(v and 0xff); out.write((v shr 8) and 0xff)
        out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
    }
    out.write("RIFF".toByteArray()); w32(totalDataLen)
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); w32(16); w16(1); w16(channels)
    w32(sampleRate); w32(byteRate); w16(blockAlign); w16(bitsPerSample)
    out.write("data".toByteArray()); w32(pcm.size); out.write(pcm)
    return out.toByteArray()
}

/** 返回 PCM16 的峰值幅度（0f~1f），用于判断麦克是否采到声音。 */
fun peakPcm(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    var maxAbs = 0
    var i = 0
    while (i + 1 < pcm.size) {
        // 高字节 toInt() 已带符号扩展，s 即正确的有符号 16 位样本，无需再手工补符号。
        val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
        val a = if (s < 0) -s else s
        if (a > maxAbs) maxAbs = a
        i += 2
    }
    return maxAbs / 32767f
}
