package com.offlinetranslator.app.feature.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.engine.llm.GemmaEngine
import com.offlinetranslator.app.engine.llm.PromptTemplates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class VisionUi(
    val imageUri: Uri? = null,
    val bitmap: Bitmap? = null,
    val prompt: String = "",
    val answer: String = "",
    val isAnswering: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class VisionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: GemmaEngine,
) : ViewModel() {

    private val _ui = MutableStateFlow(VisionUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null

    fun setImage(uri: Uri?) {
        _ui.update { it.copy(imageUri = uri, bitmap = null, answer = "", error = null) }
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = decode(uri)
            _ui.update { it.copy(bitmap = bmp) }
        }
    }

    fun setPrompt(p: String) { _ui.update { it.copy(prompt = p) } }

    /**
     * Cancel any in-flight generation. Triggered when the user taps the stop
     * button while the model is still answering. Keeps whatever was already
     * streamed and lets the user edit the prompt + retry.
     */
    fun stop() {
        job?.cancel()
        job = null
        _ui.update { it.copy(isAnswering = false) }
    }

    fun ask() {
        val cur = _ui.value
        val bmp = cur.bitmap ?: return
        if (cur.isAnswering) return
        job?.cancel()
        _ui.update { it.copy(isAnswering = true, answer = "", error = null) }
        job = viewModelScope.launch(Dispatchers.IO) {
            val info = engine.ensureLoaded().getOrElse { e ->
                val msg = if (e is com.offlinetranslator.app.engine.llm.ModelMissingException)
                    "请先到\u201c模型\u201d页下载模型后重试"
                else e.message
                _ui.update { it.copy(isAnswering = false, error = msg) }
                return@launch
            }
            // Pre-flight: refuse if active model can't actually do image input,
            // saving the user a 30s wait + cryptic C++ error.
            if (!info.supportsImage) {
                _ui.update {
                    it.copy(
                        isAnswering = false,
                        error = "当前模型「${info.displayName}」不支持图像识别，" +
                            "请到\"模型\"页切换到支持图像的 .task 模型。",
                    )
                }
                return@launch
            }
            val prompt = if (cur.prompt.contains("翻译") || cur.prompt.lowercase().contains("translate")) {
                PromptTemplates.visionTranslateText()
            } else {
                PromptTemplates.visionDescribe(cur.prompt)
            }
            val sb = StringBuilder()
            try {
                engine.generateStream(prompt, includeImage = bmp).collect { token ->
                    sb.append(token)
                    _ui.update { it.copy(answer = sb.toString()) }
                }
                _ui.update { it.copy(isAnswering = false) }
            } catch (ce: CancellationException) {
                // User tapped Stop — keep partial answer, just clear the
                // "answering" flag. Don't surface the cancellation as an error.
                _ui.update { it.copy(isAnswering = false) }
                throw ce // honor coroutine cancellation contract
            } catch (t: Throwable) {
                _ui.update { it.copy(isAnswering = false, error = t.message) }
            }
        }
    }

    private suspend fun decode(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                val original = BitmapFactory.decodeStream(input) ?: return@withContext null
                resizeForGemma(original)
            }
        }.getOrNull()
    }

    private fun resizeForGemma(src: Bitmap, target: Int = 768): Bitmap {
        val (w, h) = src.width to src.height
        val scale = target.toFloat() / maxOf(w, h)
        if (scale >= 1f) return src
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(src, nw, nh, true)
        // createScaledBitmap may return the SAME instance when no scaling is
        // needed; only recycle if a new bitmap was actually allocated.
        if (resized !== src) {
            runCatching { src.recycle() }
        }
        return resized
    }
}
