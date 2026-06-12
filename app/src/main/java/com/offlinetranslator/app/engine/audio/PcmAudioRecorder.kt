package com.offlinetranslator.app.engine.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

/**
 * 16-kHz mono PCM16 audio recorder. Emits 20ms frames + amplitude updates.
 */
@Singleton
class PcmAudioRecorder @Inject constructor() {

    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private var record: AudioRecord? = null
    private var recordJob: Job? = null
    private var scope: CoroutineScope? = null

    private val _frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 256)
    val frames: SharedFlow<ShortArray> = _frames.asSharedFlow()

    private val _amplitudes = MutableSharedFlow<Float>(extraBufferCapacity = 256)
    val amplitudes: SharedFlow<Float> = _amplitudes.asSharedFlow()

    private val captured = ByteArrayOutputStream()
    val isRecording: Boolean get() = record != null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        // 单例被翻译/问答两页共用：上一个录音还没停（如录着音切页）就先停掉丢弃，
        // 保证本次 start 拿到干净的新录音，而非未定义行为。
        if (record != null) stop()
        captured.reset()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufSize = (minBuf * 2).coerceAtLeast(sampleRate / 5) // ~200 ms buffer

        // 部分 vivo 等 ROM 对 VOICE_RECOGNITION 音源限制（初始化静默失败或录不到声），
        // 逐源回退到 MIC；初始化与启动状态都显式校验，失败抛可读错误而非静默空录。
        var rec: AudioRecord? = null
        for (source in intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )) {
            val candidate = AudioRecord(source, sampleRate, channelConfig, encoding, bufSize)
            android.util.Log.i("OT-Mic", "source=$source state=${candidate.state}")
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate.release()
                continue
            }
            candidate.startRecording()
            android.util.Log.i("OT-Mic", "source=$source recordingState=${candidate.recordingState}")
            if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { candidate.stop() }
                candidate.release()
                continue
            }
            rec = candidate
            break
        }
        record = rec ?: throw IllegalStateException("麦克风启动失败：请检查录音权限或是否被其他应用占用")
            .also { android.util.Log.e("OT-Mic", "all sources failed") }
        scope = CoroutineScope(Dispatchers.IO)
        recordJob = scope?.launch {
            val frame = ShortArray(sampleRate / 50) // 20 ms
            while (isActive && record != null) {
                val read = record?.read(frame, 0, frame.size) ?: -1
                if (read > 0) {
                    val copy = frame.copyOf(read)
                    _frames.tryEmit(copy)
                    captured.write(shortsToBytesLE(copy))
                    _amplitudes.tryEmit(calcAmp(copy, read))
                }
            }
        }
    }

    fun stop(): ByteArray {
        recordJob?.cancel()
        scope?.cancel()
        scope = null
        recordJob = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        return captured.toByteArray()
    }

    private fun calcAmp(data: ShortArray, len: Int): Float {
        if (len == 0) return 0f
        // 先去直流偏置再取平均幅度：部分 vivo 等机型麦克风采样整体偏移，
        // 直接算绝对值平均会恒定爆表，波形顶死不随声音动（用户真机实测）。
        var mean = 0L
        for (i in 0 until len) mean += data[i].toLong()
        val dc = (mean / len).toInt()
        var sum = 0L
        for (i in 0 until len) sum += abs(data[i] - dc).toLong()
        val avg = sum.toFloat() / len
        return min(avg / 2_500f, 1f)
    }

    private fun shortsToBytesLE(data: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in data) buf.putShort(s)
        return buf.array()
    }
}
