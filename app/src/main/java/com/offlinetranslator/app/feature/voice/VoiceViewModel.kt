package com.offlinetranslator.app.feature.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.R
import com.offlinetranslator.app.engine.audio.PcmAudioRecorder
import com.offlinetranslator.app.engine.llm.GemmaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class VoiceMode { QuickTranslate, MeetingTranscribe }
enum class VoiceDirection { ZhToEn, EnToZh }

data class VoiceUi(
    val mode: VoiceMode = VoiceMode.QuickTranslate,
    val direction: VoiceDirection = VoiceDirection.ZhToEn,
    val isRecording: Boolean = false,
    val isLoadingModel: Boolean = false,
    /** True while we're waiting for Gemma to produce its first transcript token. */
    val isProcessing: Boolean = false,
    /** True if the model file isn’t on disk yet — the screen surfaces a
     *  “go to model page” CTA instead of trying to record. */
    val needsModelDownload: Boolean = false,
    /** Rotates 0→3 every ~1.5s so the UI can show evolving status text. */
    val processingPhase: Int = 0,
    val transcript: String = "",
    val translation: String = "",
    val amplitudes: List<Float> = emptyList(),
    val error: String? = null,
)

/**
 * Voice translation flow:
 *
 *   1. Record 16kHz PCM mono via [PcmAudioRecorder].
 *   2. On stop, package the PCM into a WAV blob and feed it directly to the
 *      Gemma multimodal engine via [GemmaEngine.generateStream] with both
 *      audio + a one-shot prompt that asks the model to "transcribe + translate".
 *   3. Stream the model's tokens into the UI.
 *
 * No third-party ASR. Gemma 3n's audio encoder handles everything end-to-end,
 * which means:
 *   - Way better punctuation, capitalization, and homophone disambiguation
 *     compared to Vosk's small models.
 *   - One model in memory instead of two.
 *   - Handles code-mixed Chinese/English utterances naturally.
 *
 * The downside is latency: cold loading Gemma is ~10–60s depending on device.
 * We mitigate by pre-warming the engine when the screen is opened.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: PcmAudioRecorder,
    private val engine: GemmaEngine,
) : ViewModel() {

    private val _ui = MutableStateFlow(VoiceUi())
    val ui = _ui.asStateFlow()

    private var ampJob: Job? = null
    private var streamJob: Job? = null
    /** Drives the rotating status messages while Gemma is grinding on audio. */
    private var phaseTickerJob: Job? = null
    private var startedAt = 0L

    fun setMode(mode: VoiceMode) { _ui.update { it.copy(mode = mode) } }

    fun setDirection(dir: VoiceDirection) {
        if (_ui.value.isRecording) return
        if (_ui.value.direction == dir) return
        _ui.update { it.copy(direction = dir) }
    }

    /**
     * Pre-warm Gemma engine in background so the first record→stop is snappy.
     * Called when the Voice screen first appears.
     */
    fun prewarmAsrModel() {
        if (_ui.value.isLoadingModel) return
        _ui.update { it.copy(isLoadingModel = true, error = null, needsModelDownload = false) }
        viewModelScope.launch(Dispatchers.IO) {
            engine.ensureLoaded()
                .onSuccess { _ui.update { it.copy(isLoadingModel = false) } }
                .onFailure { e ->
                    val missing = e is com.offlinetranslator.app.engine.llm.ModelMissingException
                    _ui.update {
                        it.copy(
                            isLoadingModel = false,
                            // Don't show a noisy error toast for the normal
                            // first-run "no model yet" case; the screen will
                            // render a friendly CTA instead.
                            error = if (missing) null else e.message,
                            needsModelDownload = missing,
                        )
                    }
                }
        }
    }

    fun startRecording() {
        if (_ui.value.isRecording) return
        if (_ui.value.isLoadingModel) return
        _ui.update {
            it.copy(
                isRecording = true,
                transcript = "",
                translation = "",
                amplitudes = emptyList(),
                error = null,
            )
        }
        startedAt = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                recorder.start()
            } catch (t: Throwable) {
                _ui.update { it.copy(isRecording = false, error = t.message) }
                return@launch
            }
            android.util.Log.i("VoiceVm", "recorder.start took ${System.currentTimeMillis() - t0}ms")
        }
        ampJob = viewModelScope.launch {
            recorder.amplitudes.collect { amp ->
                _ui.update {
                    val list = (it.amplitudes + amp).takeLast(64)
                    it.copy(amplitudes = list)
                }
            }
        }
    }

    fun stopRecording() {
        if (!_ui.value.isRecording) return
        ampJob?.cancel()
        streamJob?.cancel()
        _ui.update { it.copy(isRecording = false) }

        val t0 = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            val pcm = recorder.stop()
            android.util.Log.i("VoiceVm", "recorder.stop took ${System.currentTimeMillis() - t0}ms, pcm.size=${pcm.size}")

            val durationMs = System.currentTimeMillis() - startedAt
            if (durationMs < 500) {
                _ui.update { it.copy(error = context.getString(R.string.voice_too_short)) }
                return@launch
            }

            val peak = peakPcm(pcm)
            if (peak < 0.01f) {
                _ui.update {
                    it.copy(
                        error = "麦克未采集到足够声音（峰值=${(peak * 100).toInt()}%），请检查麦克权限与静音设置。",
                    )
                }
                return@launch
            }

            val zh = _ui.value.direction == VoiceDirection.ZhToEn

            // Show the processing indicator + start phase ticker.
            _ui.update { it.copy(isProcessing = true, processingPhase = 0, transcript = "", translation = "") }
            startPhaseTicker()

            // Make sure Gemma is loaded.
            engine.ensureLoaded().onFailure { e ->
                phaseTickerJob?.cancel()
                _ui.update { it.copy(isProcessing = false, error = e.message) }
                return@launch
            }
            if (!engine.isAudioEnabled()) {
                phaseTickerJob?.cancel()
                _ui.update {
                    it.copy(
                        isProcessing = false,
                        error = context.getString(R.string.voice_audio_unsupported),
                    )
                }
                return@launch
            }

            // Wrap raw 16k mono PCM16 into a WAV envelope (LiteRT-LM expects WAV
            // headers, not naked PCM).
            val wav = pcmToWav(pcm, sampleRate = 16_000, channels = 1, bitsPerSample = 16)

            // Single end-to-end prompt: ask Gemma to BOTH transcribe and translate
            // in one streamed pass. The output is structured as:
            //   原文: <transcript>
            //   译文: <translation>
            // The ViewModel parses each line and routes it to the correct UI slot.
            val prompt = if (zh) {
                "请先把这段语音逐字转写为中文（不要修改、不要总结），" +
                    "再翻译成自然的英文。严格按以下格式输出：\n" +
                    "原文：<中文逐字稿>\n" +
                    "译文：<英文翻译>"
            } else {
                "Please transcribe the speech verbatim in English (no rewording), " +
                    "then translate it into natural Chinese. Output strictly in this format:\n" +
                    "原文：<English transcript>\n" +
                    "译文：<中文翻译>"
            }

            val accum = StringBuilder()
            _ui.update { it.copy(transcript = "", translation = "") }
            try {
                engine.generateStream(prompt = prompt, includeAudioWav = wav).collect { token ->
                    accum.append(token)
                    val parts = parseTranscriptAndTranslation(accum.toString())
                    _ui.update {
                        it.copy(
                            // Once we get our first real transcript token, kill
                            // the processing animation — streaming text is
                            // itself the progress signal.
                            isProcessing = if (parts.first.isEmpty() && parts.second.isEmpty()) it.isProcessing else false,
                            transcript = parts.first,
                            translation = parts.second,
                        )
                    }
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(error = t.message ?: "translation failed") }
            } finally {
                phaseTickerJob?.cancel()
                _ui.update { it.copy(isProcessing = false) }
            }
        }
    }

    /**
     * Cycle [VoiceUi.processingPhase] through 0→1→2→3 every 1.5 seconds while
     * the model is grinding. The Voice screen maps each phase to a different
     * status message (“识别语音中…”, “代叠词表…”, “纳入上下文…”, “收尾中…”).
     */
    private fun startPhaseTicker() {
        phaseTickerJob?.cancel()
        phaseTickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(1500)
                _ui.update { it.copy(processingPhase = (it.processingPhase + 1) % 4) }
            }
        }
    }

    /**
     * Parse the streamed model output into (transcript, translation).
     *
     * The model is instructed to emit:
     *   原文：xxx
     *   译文：yyy
     *
     * Streaming tokens means we only see partial text. Strategy:
     *  - Find first '原文' or 'Source' marker. Everything between it and the
     *    '译文' / 'Translation' marker is the transcript.
     *  - Everything after '译文' is the translation.
     *  - Strip the labels themselves from each side.
     *  - If labels haven't appeared yet, dump everything to transcript.
     */
    private fun parseTranscriptAndTranslation(raw: String): Pair<String, String> {
        // Match "原文" or "原文：" or "Source:" (and "译文" / "Translation:")
        val srcMarker = Regex("(?im)^\\s*(原文|Source|Transcript)\\s*[：:]?\\s*")
        val tgtMarker = Regex("(?im)^\\s*(译文|Translation)\\s*[：:]?\\s*")

        val srcMatch = srcMarker.find(raw)
        val tgtMatch = tgtMarker.find(raw)

        return when {
            tgtMatch != null && srcMatch != null && srcMatch.range.first < tgtMatch.range.first -> {
                val src = raw.substring(srcMatch.range.last + 1, tgtMatch.range.first).trim()
                val tgt = raw.substring(tgtMatch.range.last + 1).trim()
                src to tgt
            }
            tgtMatch != null && srcMatch == null -> {
                val src = raw.substring(0, tgtMatch.range.first).trim()
                val tgt = raw.substring(tgtMatch.range.last + 1).trim()
                src to tgt
            }
            srcMatch != null -> {
                val src = raw.substring(srcMatch.range.last + 1).trim()
                src to ""
            }
            else -> raw.trim() to ""
        }
    }

    private fun peakPcm(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var maxAbs = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val v = if (s and 0x8000 != 0) s or 0x7fff_0000.toInt().inv() else s
            val a = if (v < 0) -v else v
            if (a > maxAbs) maxAbs = a
            i += 2
        }
        return maxAbs / 32767f
    }

    /**
     * Wrap raw little-endian PCM bytes into a minimal RIFF/WAVE container.
     *
     * Header layout (44 bytes):
     *   "RIFF"  + total-8        + "WAVE"
     *   "fmt "  + 16 + 1(PCM) + ch + sr + br + bs + bps
     *   "data"  + datalen        + ...PCM...
     */
    private fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = pcm.size + 36
        val out = java.io.ByteArrayOutputStream(44 + pcm.size)
        fun w16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        fun w32(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }
        out.write("RIFF".toByteArray()); w32(totalDataLen)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); w32(16); w16(1); w16(channels)
        w32(sampleRate); w32(byteRate); w16(blockAlign); w16(bitsPerSample)
        out.write("data".toByteArray()); w32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
