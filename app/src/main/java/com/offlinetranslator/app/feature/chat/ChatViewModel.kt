package com.offlinetranslator.app.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.offlinetranslator.app.core.data.db.ChatDao
import com.offlinetranslator.app.core.data.db.ChatMessageEntity
import com.offlinetranslator.app.core.data.db.ChatSessionEntity
import com.offlinetranslator.app.engine.audio.PcmAudioRecorder
import com.offlinetranslator.app.engine.audio.pcmToWav
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
import kotlinx.coroutines.flow.map
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
    /** 待发送的图片附件（单轮视觉问答用，不持久化）。 */
    val attachedBitmap: Bitmap? = null,
    val error: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: GemmaEngine,
    private val dao: ChatDao,
    private val recorder: PcmAudioRecorder,
    private val prefs: com.offlinetranslator.app.core.data.AppPreferencesRepository,
) : ViewModel() {

    private companion object {
        /** 未压缩消息超过这个条数 → 触发上下文压缩。 */
        const val COMPRESS_AFTER_MESSAGES = 20
        /**
         * 未压缩消息总字数预算。超过即视为会话"满了"→ 触发压缩；同时也是单轮
         * prompt 携带原文的硬上限（压缩没跟上时从最旧的开始裁）。模型窗口
         * 4096 token，预留生成空间后中文按 ≈1 字/token 取 3000。
         */
        const val CONTEXT_CHAR_BUDGET = 3000
        /** 压缩后保留的最近原文条数（其余并入摘要）。 */
        const val KEEP_RAW_AFTER_COMPRESS = 6
    }

    private val _ui = MutableStateFlow(ChatUi())
    val ui = _ui.asStateFlow()

    private val _sid = MutableStateFlow<String?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = _sid
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else dao.observeMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<ChatMessageEntity>())

    /** 全部会话列表（倒序），供会话抽屉展示/切换/删除。 */
    val sessions = dao.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<ChatSessionEntity>())

    /** 当前角色预设（default/translator/grammar/polish/speaking），持久化在 DataStore。 */
    val chatRole = prefs.flow
        .map { it.chatRole }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    fun setChatRole(role: String) {
        viewModelScope.launch { prefs.setChatRole(role) }
    }


    private var generateJob: Job? = null
    private var voiceJob: Job? = null
    private var ampJob: Job? = null

    fun openSession(id: String) {
        if (_ui.value.isGenerating) return
        _sid.value = id
        _ui.update { it.copy(sessionId = id, streamingContent = "", error = null, attachedBitmap = null) }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            // 先删该会话消息引用的本地图片文件，避免孤儿 JPEG 累积占空间。
            withContext(Dispatchers.IO) {
                dao.messagesOnce(id).mapNotNull { it.imageUri }.forEach { path ->
                    runCatching { java.io.File(path).delete() }
                }
            }
            dao.deleteMessages(id)
            dao.deleteSession(id)
            if (_sid.value == id) {
                _sid.value = null
                _ui.update { it.copy(sessionId = null, streamingContent = "") }
            }
        }
    }

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

    fun attachImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = com.offlinetranslator.app.engine.image.decodeBitmapForGemma(context, uri)
            if (bmp == null) _ui.update { it.copy(error = "图片解码失败，请换一张") }
            else _ui.update { it.copy(attachedBitmap = bmp, error = null) }
        }
    }

    fun clearImage() { _ui.update { it.copy(attachedBitmap = null) } }

    /** 把附图保存到 app 私有目录，返回绝对路径（用于持久化到消息并在气泡渲染）。失败返回 null。 */
    private fun saveImageToFile(bmp: Bitmap): String? = runCatching {
        val dir = java.io.File(context.filesDir, "chat_images").apply { mkdirs() }
        val f = java.io.File(dir, "${UUID.randomUUID()}.jpg")
        java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        f.absolutePath
    }.getOrNull()

    fun send(text: String) {
        val content = text.trim()
        val image = _ui.value.attachedBitmap
        if ((content.isEmpty() && image == null) || _ui.value.isGenerating) return
        // 附件已捕获到局部变量，发送即清掉输入区预览（图片随消息进气泡展示），
        // 不等模型处理完 —— 与主流 IM/助手类应用一致。
        if (image != null) _ui.update { it.copy(attachedBitmap = null) }
        ensureSession()
        val sid = _sid.value
        if (sid == null) {
            _ui.update { it.copy(error = "会话初始化失败，请重试") }
            return
        }
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            // 上下文 = （若有）旧消息压缩摘要 + 摘要之后的全部消息原文——用户与 AI
            // 双方都带上，模型才能延续自己说过的话。从 DB 直读快照（而非 UI Flow），
            // 切换会话后立即提问也不会拿到旧列表；此时新用户轮尚未入库，prompt 末尾
            // 会显式追加它，不会重复。仅当总字数超预算（压缩还没跟上）才裁掉最旧的兜底。
            val session = dao.getSession(sid)
            val unsummarized = dao.messagesOnce(sid).drop(session?.summarizedCount ?: 0)
            // 摘要桥接/图片标注文案统一收口在 PromptTemplates（随应用语言中英切换）。
            val summaryTurns = session?.summary?.takeIf { it.isNotBlank() }
                ?.let { PromptTemplates.summaryBridgeTurns(it) } ?: emptyList()
            // 带图轮次在历史里显式标注，模型才知道哪轮发过图、图和哪句话对应。
            val rawTurns = unsummarized.map { m ->
                m.role to if (m.imageUri != null) PromptTemplates.historyImageNote(m.content)
                else m.content
            }
            val historyBefore = summaryTurns + fitContextBudget(rawTurns)
            // 文件写入/JPEG 压缩是阻塞 IO，挪到 IO 线程，别卡主线程。
            val imagePath = if (image != null) withContext(Dispatchers.IO) { saveImageToFile(image) } else null
            // 有图就让图片承担展示，文字可为空；图存失败才退回文字占位。
            val userTurnContent = if (content.isEmpty() && image != null && imagePath == null) "🖼 [图片]" else content
            dao.insertMessage(
                ChatMessageEntity(
                    sessionId = sid, role = "user",
                    content = userTurnContent, imageUri = imagePath,
                    createdAt = System.currentTimeMillis(),
                )
            )
            engine.ensureLoaded().onFailure { e ->
                val msg = if (e is com.offlinetranslator.app.engine.llm.ModelMissingException)
                    "请先到“模型”页下载模型后重试"
                else e.message
                _ui.update { it.copy(error = msg) }
                return@launch
            }
            // 图片识别预检：模型不支持图像就给清晰提示，别让用户白等。
            if (image != null && engine.status.value.activeModel?.supportsImage == false) {
                _ui.update { it.copy(error = "当前模型不支持图像识别，请在「设置」切换支持图像的模型", attachedBitmap = null) }
                return@launch
            }
            // 本轮没新附图时，回看上下文窗口内最近一张已发图片并重新喂给模型——
            // 否则"图里第二个字是什么"这类追问模型根本看不到图。图片消息被压缩
            // 进摘要后自然退出窗口、停止重喂（控制每轮视觉前缀的耗时代价）。
            val refedImage = if (image == null && engine.status.value.activeModel?.supportsImage != false) {
                unsummarized.lastOrNull { it.imageUri != null }?.imageUri?.let { path ->
                    withContext(Dispatchers.IO) {
                        runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
                    }
                }
            } else null
            val sendImage = image ?: refedImage
            _ui.update { it.copy(isGenerating = true, streamingContent = "", error = null) }

            val role = chatRole.value
            val prompt = when {
                image != null -> PromptTemplates.chatWithImage(historyBefore, content, role = role)
                refedImage != null -> PromptTemplates.chatWithImage(historyBefore, content, refed = true, role = role)
                else -> PromptTemplates.chat(historyBefore, content, role = role)
            }
            val sb = StringBuilder()
            try {
                val stream = if (sendImage != null) engine.generateStream(prompt, includeImage = sendImage)
                             else engine.generateStream(prompt)
                stream.collect { token ->
                    sb.append(token)
                    _ui.update { it.copy(streamingContent = sb.toString()) }
                }
                dao.insertMessage(
                    ChatMessageEntity(
                        sessionId = sid, role = "assistant",
                        content = sb.toString(), createdAt = System.currentTimeMillis(),
                    )
                )
                // 用 UPDATE 而非 REPLACE，避免把会话的压缩摘要字段抹掉。
                dao.updateSessionMeta(
                    id = sid,
                    title = userTurnContent.take(20),
                    updatedAt = System.currentTimeMillis(),
                    modelId = engine.status.value.activeModel?.id ?: "",
                )
                _ui.update { it.copy(isGenerating = false, streamingContent = "", attachedBitmap = null) }
                maybeCompressContext(sid)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 用户点了暂停 —— 不是错误。已生成的部分由 stop() 落库。
                throw ce
            } catch (t: Throwable) {
                _ui.update { it.copy(isGenerating = false, streamingContent = "", error = t.message) }
            }
        }
    }

    /**
     * 上下文裁剪兜底：正常情况下双方消息全量进上下文；只有当压缩还没跟上、
     * 总字数已超预算时，才从最旧的开始裁（最近的消息永远保留）。
     */
    private fun fitContextBudget(turns: List<Pair<String, String>>): List<Pair<String, String>> {
        var total = turns.sumOf { it.second.length }
        var result = turns
        while (result.size > 1 && total > CONTEXT_CHAR_BUDGET) {
            total -= result.first().second.length
            result = result.drop(1)
        }
        return result
    }

    /**
     * 上下文压缩：未压缩消息超过 [COMPRESS_AFTER_MESSAGES] 条、或总字数超出
     * [CONTEXT_CHAR_BUDGET]（会话"满了"）时，把较早的部分（保留最近
     * [KEEP_RAW_AFTER_COMPRESS] 条原文）连同旧摘要一起压成 ≤200 字新摘要存到
     * 会话上。回答完成后后台静默执行，失败不影响对话（下轮再试）。
     */
    private fun maybeCompressContext(sid: String) {
        viewModelScope.launch {
            runCatching {
                val session = dao.getSession(sid) ?: return@launch
                val all = dao.messagesOnce(sid)
                val unsummarized = all.drop(session.summarizedCount)
                val totalChars = unsummarized.sumOf { it.content.length }
                if (unsummarized.size <= COMPRESS_AFTER_MESSAGES &&
                    totalChars <= CONTEXT_CHAR_BUDGET
                ) return@launch
                val toCompress = unsummarized.dropLast(KEEP_RAW_AFTER_COMPRESS)
                if (toCompress.isEmpty()) return@launch
                val convText = toCompress.joinToString("\n") {
                    (if (it.role == "user") "用户：" else "助手：") + it.content
                }
                val prompt = PromptTemplates.summarize(convText, session.summary)
                val sb = StringBuilder()
                engine.generateStream(prompt).collect { sb.append(it) }
                val newSummary = PromptTemplates.trimAtStop(sb.toString()).trim()
                if (newSummary.isNotEmpty()) {
                    dao.updateSummary(sid, newSummary, session.summarizedCount + toCompress.size)
                }
            }
        }
    }

    fun stop() {
        generateJob?.cancel()
        // 暂停：把已经流式出来的部分作为助手回复存下来，别丢。
        val partial = _ui.value.streamingContent
        val sid = _sid.value
        if (partial.isNotBlank() && sid != null) {
            viewModelScope.launch {
                dao.insertMessage(
                    ChatMessageEntity(
                        sessionId = sid, role = "assistant",
                        content = partial, createdAt = System.currentTimeMillis(),
                    )
                )
                // 暂停也算一次活跃，把会话顶到列表最前（与正常完成一致）。
                dao.touchSession(sid, System.currentTimeMillis())
            }
        }
        _ui.update { it.copy(isGenerating = false, streamingContent = "", attachedBitmap = null) }
    }
}
