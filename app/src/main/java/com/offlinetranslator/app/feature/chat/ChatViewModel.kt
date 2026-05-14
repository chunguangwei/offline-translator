package com.offlinetranslator.app.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.core.data.db.ChatDao
import com.offlinetranslator.app.core.data.db.ChatMessageEntity
import com.offlinetranslator.app.core.data.db.ChatSessionEntity
import com.offlinetranslator.app.engine.audio.PcmAudioRecorder
import com.offlinetranslator.app.engine.llm.GemmaEngine
import com.offlinetranslator.app.engine.llm.PromptTemplates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class ChatVoiceLang { ZH, EN }

data class ChatUi(
    val sessionId: String? = null,
    val streamingContent: String = "",
    val isGenerating: Boolean = false,
    val isRecording: Boolean = false,
    /** True between user-tap-stop and Gemma's first transcript token — drives
     *  the in-chat "transcribing" indicator so the user knows the app is alive.
     *  The model can take 5–30s on a cold first audio call. */
    val isTranscribing: Boolean = false,
    val voiceLang: ChatVoiceLang = ChatVoiceLang.ZH,
    val voicePartial: String = "",
    val amplitudes: List<Float> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engine: GemmaEngine,
    private val dao: ChatDao,
    private val recorder: PcmAudioRecorder,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUi())
    val ui = _ui.asStateFlow()

    private val _sid = MutableStateFlow<String?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = _sid
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else dao.observeMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<ChatMessageEntity>())

    private var generateJob: Job? = null
    private var voiceJob: Job? = null
    private var ampJob: Job? = null

    fun setVoiceLang(lang: ChatVoiceLang) { _ui.update { it.copy(voiceLang = lang) } }

    fun startNewSession() {
        val id = UUID.randomUUID().toString()
        // Set session id synchronously so callers (e.g. send()) can use it
        // immediately. The DB row is written off-thread; foreign-key inserts
        // for messages will follow this scope's order on Room's writer.
        _sid.value = id
        _ui.update { it.copy(sessionId = id, streamingContent = "", error = null) }
        viewModelScope.launch {
            dao.upsertSession(
                ChatSessionEntity(
                    id = id,
                    title = "New chat",
                    updatedAt = System.currentTimeMillis(),
                    modelId = engine.status.value.activeModel?.id ?: "",
                )
            )
        }
    }

    fun ensureSession() {
        if (_sid.value == null) startNewSession()
    }

    fun startVoiceInput() {
        if (_ui.value.isRecording) return
        try {
            recorder.start()
        } catch (t: Throwable) {
            _ui.update { it.copy(error = t.message) }
            return
        }
        _ui.update { it.copy(isRecording = true, voicePartial = "", amplitudes = emptyList()) }
        ampJob = viewModelScope.launch {
            recorder.amplitudes.collect { amp ->
                _ui.update { it.copy(amplitudes = (it.amplitudes + amp).takeLast(48)) }
            }
        }
        // No partial transcription: Gemma audio is one-shot per utterance.
        // We just record now and transcribe in stopVoiceInput().
    }

    /**
     * Stop recording and return what was transcribed.
     *
     * Pipeline:
     *   1. recorder.stop() → raw 16k PCM
     *   2. Wrap as WAV
     *   3. Send to Gemma multimodal: “Transcribe verbatim”
     *   4. Drain stream → returned string is the final transcript.
     *
     * Returns empty string on any failure (caller can ignore).
     */
    suspend fun stopVoiceInputAndTranscribe(): String {
        if (!_ui.value.isRecording) return ""
        val pcm = recorder.stop()
        ampJob?.cancel()
        // Switch from "recording" to "transcribing" so the UI can show a
        // dedicated processing state (waveform stays hidden during this).
        _ui.update {
            it.copy(
                isRecording = false,
                isTranscribing = true,
                voicePartial = "",
                amplitudes = emptyList(),
            )
        }
        if (pcm.isEmpty()) {
            _ui.update { it.copy(isTranscribing = false) }
            return ""
        }

        engine.ensureLoaded().onFailure { e ->
            val msg = if (e is com.offlinetranslator.app.engine.llm.ModelMissingException)
                "请先到“模型”页下载语音模型后重试"
            else e.message ?: "engine not ready"
            _ui.update { it.copy(isTranscribing = false, error = msg) }
            return ""
        }
        if (!engine.isAudioEnabled()) {
            _ui.update {
                it.copy(
                    isTranscribing = false,
                    error = "当前模型未启用语音识别，请重新加载后重试",
                )
            }
            return ""
        }
        val wav = pcmToWav(pcm)
        val zh = _ui.value.voiceLang == ChatVoiceLang.ZH
        val prompt = if (zh) "请将这段语音逐字转写为中文，只输出转写结果，不要加任何说明。"
        else "Transcribe the audio verbatim in English. Output only the transcript, nothing else."
        val accum = StringBuilder()
        try {
            engine.generateStream(prompt = prompt, includeAudioWav = wav).collect { token ->
                accum.append(token)
                _ui.update { it.copy(voicePartial = accum.toString().trim()) }
            }
        } catch (t: Throwable) {
            _ui.update { it.copy(isTranscribing = false, voicePartial = "", error = t.message) }
            return ""
        }
        val finalText = accum.toString().trim()
        _ui.update { it.copy(isTranscribing = false, voicePartial = "") }
        return finalText
    }

    /** Backwards-compatible synchronous-ish stop (legacy callers). */
    fun stopVoiceInput(): String {
        if (!_ui.value.isRecording) return ""
        recorder.stop()
        ampJob?.cancel(); voiceJob?.cancel()
        val text = _ui.value.voicePartial
        _ui.update { it.copy(isRecording = false, voicePartial = "", amplitudes = emptyList()) }
        return text
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val sampleRate = 16_000
        val channels = 1
        val bps = 16
        val byteRate = sampleRate * channels * bps / 8
        val blockAlign = channels * bps / 8
        val totalDataLen = pcm.size + 36
        val out = java.io.ByteArrayOutputStream(44 + pcm.size)
        fun w16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        fun w32(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
        }
        out.write("RIFF".toByteArray()); w32(totalDataLen)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); w32(16); w16(1); w16(channels)
        w32(sampleRate); w32(byteRate); w16(blockAlign); w16(bps)
        out.write("data".toByteArray()); w32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _ui.value.isGenerating) return
        ensureSession()
        val sid = _sid.value
        if (sid == null) {
            _ui.update { it.copy(error = "会话初始化失败，请重试") }
            return
        }
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            // Snapshot history BEFORE inserting the new user message — the Room
            // flow won't have observed the insert yet, and we explicitly add the
            // user turn into the prompt below. This prevents the off-by-one
            // history bug (first reply previously had 0 history turns).
            val historyBefore = messages.value.map { it.role to it.content }
            dao.insertMessage(
                ChatMessageEntity(
                    sessionId = sid, role = "user",
                    content = content, createdAt = System.currentTimeMillis(),
                )
            )
            engine.ensureLoaded().onFailure { e ->
                val msg = if (e is com.offlinetranslator.app.engine.llm.ModelMissingException)
                    "请先到“模型”页下载模型后重试"
                else e.message
                _ui.update { it.copy(error = msg) }
                return@launch
            }
            _ui.update { it.copy(isGenerating = true, streamingContent = "", error = null) }

            val prompt = PromptTemplates.chat(historyBefore, content)
            val sb = StringBuilder()
            try {
                engine.generateStream(prompt).collect { token ->
                    sb.append(token)
                    _ui.update { it.copy(streamingContent = sb.toString()) }
                }
                dao.insertMessage(
                    ChatMessageEntity(
                        sessionId = sid, role = "assistant",
                        content = sb.toString(), createdAt = System.currentTimeMillis(),
                    )
                )
                dao.upsertSession(
                    ChatSessionEntity(
                        id = sid,
                        title = content.take(20),
                        updatedAt = System.currentTimeMillis(),
                        modelId = engine.status.value.activeModel?.id ?: "",
                    )
                )
                _ui.update { it.copy(isGenerating = false, streamingContent = "") }
            } catch (t: Throwable) {
                _ui.update { it.copy(isGenerating = false, streamingContent = "", error = t.message) }
            }
        }
    }

    fun stop() { generateJob?.cancel(); _ui.update { it.copy(isGenerating = false) } }
}
