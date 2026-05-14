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
    val error: String? = null,
)

@HiltViewModel
class TranslateViewModel @Inject constructor(
    private val engine: GemmaEngine,
) : ViewModel() {
    private val _ui = MutableStateFlow(TranslateUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null

    fun setInput(text: String) { _ui.update { it.copy(input = text) } }
    fun setOutput(text: String) { _ui.update { it.copy(output = text) } }
    fun swapLang() = _ui.update {
        it.copy(source = it.target, target = it.source, input = it.output, output = it.input)
    }

    fun translate() {
        val cur = _ui.value
        if (cur.input.isBlank() || cur.isTranslating) return
        job?.cancel()
        _ui.update { it.copy(isTranslating = true, output = "", error = null) }
        job = viewModelScope.launch {
            engine.ensureLoaded().onFailure { e ->
                val msg = if (e is com.offlinetranslator.app.engine.llm.ModelMissingException)
                    "请先到\u201c模型\u201d页下载模型后重试"
                else e.message
                _ui.update { it.copy(isTranslating = false, error = msg) }
                return@launch
            }
            val prompt = PromptTemplates.translate(cur.input, cur.source == TranslateLang.ZH)
            try {
                engine.generateStream(prompt).collect { token ->
                    _ui.update { it.copy(output = it.output + token) }
                }
                _ui.update { it.copy(isTranslating = false) }
            } catch (t: Throwable) {
                _ui.update { it.copy(isTranslating = false, error = t.message) }
            }
        }
    }

    fun cancel() { job?.cancel(); _ui.update { it.copy(isTranslating = false) } }
    fun clear() { cancel(); _ui.update { it.copy(input = "", output = "") } }
}
