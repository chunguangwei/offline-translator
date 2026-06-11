package com.offlinetranslator.app.feature.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.engine.llm.GemmaEngine
import com.offlinetranslator.app.engine.llm.PromptTemplates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TranslateLang { ZH, EN }

data class TranslateUi(
    val source: TranslateLang = TranslateLang.ZH,
    val target: TranslateLang = TranslateLang.EN,
    val input: String = "",
    val output: String = "",
    val isTranslating: Boolean = false,
    /** True while the engine is loading the model into memory (slow first run, CPU). */
    val loadingModel: Boolean = false,
    /** True while microphone is capturing audio. */
    val isRecording: Boolean = false,
    /** True while Gemma is transcribing the recorded audio into text. */
    val isTranscribing: Boolean = false,
    /** True when no model is on disk yet — surfaced as a top inline banner (not a snackbar). */
    val isModelMissing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TranslateViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val engine: GemmaEngine,
    private val recorder: com.offlinetranslator.app.engine.audio.PcmAudioRecorder,
    private val translationDao: com.offlinetranslator.app.core.data.db.TranslationDao,
) : ViewModel() {
    private val _ui = MutableStateFlow(TranslateUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null
    private var recordJob: Job? = null
    private var recordStartedAt = 0L

    /**
     * 翻译页出现/返回时调用：后台预热引擎并刷新「模型缺失」标志。
     * 下载完模型返回本页时，这里会把缺失横幅清掉，并提前把模型读进内存。
     */
    fun refreshModel() {
        viewModelScope.launch {
            engine.ensureLoaded()
                .onSuccess { _ui.update { it.copy(isModelMissing = false) } }
                .onFailure { e ->
                    _ui.update {
                        it.copy(isModelMissing = e is com.offlinetranslator.app.engine.llm.ModelMissingException)
                    }
                }
        }
    }

    fun setInput(text: String) { _ui.update { it.copy(input = text) } }
    fun setOutput(text: String) { _ui.update { it.copy(output = text) } }
    fun swapLang() = _ui.update {
        it.copy(source = it.target, target = it.source, input = it.output, output = it.input)
    }

    fun translate() {
        val cur = _ui.value
        if (cur.input.isBlank() || cur.isTranslating || cur.isTranscribing) return
        job?.cancel()
        _ui.update { it.copy(isTranslating = true, loadingModel = true, output = "", error = null) }
        job = viewModelScope.launch {
            engine.ensureLoaded().onFailure { e ->
                val msg = if (e is com.offlinetranslator.app.engine.llm.ModelMissingException)
                    "请先到\u201c模型\u201d页下载模型后重试"
                else e.message
                val missing = e is com.offlinetranslator.app.engine.llm.ModelMissingException
                _ui.update { it.copy(isTranslating = false, loadingModel = false, isModelMissing = missing, error = if (missing) null else msg) }
                return@launch
            }
            // 模型已就绪，进入实际翻译（生成）阶段。
            _ui.update { it.copy(loadingModel = false, isModelMissing = false) }
            val prompt = PromptTemplates.translate(cur.input, cur.source == TranslateLang.ZH)
            try {
                engine.generateStream(prompt).collect { token ->
                    _ui.update { it.copy(output = it.output + token) }
                }
                _ui.update { it.copy(isTranslating = false) }
                // 翻译成功 → 写入历史（同一原文+方向去重，只留最新一条）。
                val finalOutput = _ui.value.output.trim()
                if (finalOutput.isNotEmpty()) {
                    translationDao.deleteDuplicates(cur.input.trim(), cur.source.name, cur.target.name)
                    translationDao.insert(
                        com.offlinetranslator.app.core.data.db.TranslationEntity(
                            sourceText = cur.input.trim(),
                            translatedText = finalOutput,
                            sourceLang = cur.source.name,
                            targetLang = cur.target.name,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 用户主动取消 —— 不是错误，保留已译出的部分即可。
                throw ce
            } catch (t: Throwable) {
                _ui.update { it.copy(isTranslating = false, error = t.message) }
            }
        }
    }

    /**
     * 拍照/选图翻译：识别图中文字并逐行「原文 => 译文」对照，结果流式进译文区。
     * 输入框保持原样（图片不是文字输入）；不写翻译历史（无文字原文可去重）。
     */
    fun translateImage(uri: android.net.Uri) {
        val cur = _ui.value
        if (cur.isTranslating || cur.isTranscribing) return
        job?.cancel()
        _ui.update { it.copy(isTranslating = true, loadingModel = true, output = "", error = null) }
        job = viewModelScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.offlinetranslator.app.engine.image.decodeBitmapForGemma(context, uri)
            }
            if (bmp == null) {
                _ui.update { it.copy(isTranslating = false, loadingModel = false, error = "图片解码失败，请换一张") }
                return@launch
            }
            engine.ensureLoaded().onFailure { e ->
                val missing = e is com.offlinetranslator.app.engine.llm.ModelMissingException
                _ui.update {
                    it.copy(isTranslating = false, loadingModel = false,
                        isModelMissing = missing, error = if (missing) null else e.message)
                }
                return@launch
            }
            _ui.update { it.copy(loadingModel = false, isModelMissing = false) }
            try {
                engine.generateStream(PromptTemplates.visionTranslateText(), includeImage = bmp)
                    .collect { token -> _ui.update { it.copy(output = it.output + token) } }
                _ui.update { it.copy(isTranslating = false) }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _ui.update { it.copy(isTranslating = false, error = t.message) }
            }
        }
    }

    fun startVoiceInput() {
        val cur = _ui.value
        if (cur.isRecording || cur.isTranscribing || cur.isTranslating) return
        _ui.update { it.copy(isRecording = true, error = null) }
        recordStartedAt = System.currentTimeMillis()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                recorder.start()
            } catch (t: Throwable) {
                _ui.update { it.copy(isRecording = false, error = t.message) }
            }
        }
    }

    fun stopVoiceInput() {
        val cur = _ui.value
        if (!cur.isRecording) return
        _ui.update { it.copy(isRecording = false) }
        recordJob?.cancel()
        recordJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val pcm = recorder.stop()
            val durationMs = System.currentTimeMillis() - recordStartedAt
            if (durationMs < 500) {
                _ui.update { it.copy(error = "录音太短，请按住多说一会儿") }
                return@launch
            }
            if (com.offlinetranslator.app.engine.audio.peakPcm(pcm) < 0.01f) {
                _ui.update { it.copy(error = "没采到声音，请检查麦克风权限") }
                return@launch
            }
            _ui.update { it.copy(isTranscribing = true, loadingModel = true) }
            engine.ensureLoaded().onFailure { e ->
                val msg = if (e is com.offlinetranslator.app.engine.llm.ModelMissingException)
                    "请先到“模型”页下载模型后重试" else e.message
                val missing = e is com.offlinetranslator.app.engine.llm.ModelMissingException
                _ui.update { it.copy(isTranscribing = false, loadingModel = false, isModelMissing = missing, error = if (missing) null else msg) }
                return@launch
            }
            _ui.update { it.copy(loadingModel = false) }
            if (!engine.isAudioEnabled()) {
                _ui.update { it.copy(isTranscribing = false, error = "当前模型不支持语音输入") }
                return@launch
            }
            val wav = com.offlinetranslator.app.engine.audio.pcmToWav(pcm)
            val prompt = PromptTemplates.transcribeVerbatim(cur.source == TranslateLang.ZH)
            val accum = StringBuilder()
            try {
                engine.generateStream(prompt = prompt, includeAudioWav = wav).collect { token ->
                    accum.append(token)
                    _ui.update { it.copy(input = accum.toString().trim(), isTranscribing = false) }
                }
                _ui.update { it.copy(isTranscribing = false) }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _ui.update { it.copy(isTranscribing = false, error = t.message) }
            }
        }
    }

    fun cancel() { job?.cancel(); _ui.update { it.copy(isTranslating = false) } }
    fun clear() { cancel(); _ui.update { it.copy(input = "", output = "") } }
}
