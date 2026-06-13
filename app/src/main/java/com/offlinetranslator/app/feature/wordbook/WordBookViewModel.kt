package com.offlinetranslator.app.feature.wordbook

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.core.data.db.WordBookDao
import com.offlinetranslator.app.core.data.db.WordBookEntity
import com.offlinetranslator.app.core.data.db.WordEntryEntity
import com.offlinetranslator.app.engine.llm.GemmaEngine
import com.offlinetranslator.app.engine.llm.PromptTemplates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 提取出的词条草稿（预览阶段，可删错项后再入库）。 */
data class VocabDraft(val english: String, val chinese: String, val note: String)

data class ImportUi(
    val isExtracting: Boolean = false,
    /** 正在加载模型（首载提示）。 */
    val loadingModel: Boolean = false,
    val extractedCount: Int = 0,
    val drafts: List<VocabDraft> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class WordBookViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: GemmaEngine,
    private val dao: WordBookDao,
) : ViewModel() {

    val books = dao.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<WordBookEntity>())

    private val _import = MutableStateFlow(ImportUi())
    val importUi = _import.asStateFlow()

    private var extractJob: Job? = null

    fun entries(bookId: Long) = dao.observeEntries(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<WordEntryEntity>())

    // ── 导入：读文件 ──

    /** 读取用户选择的文本文件内容（失败返回 null）。 */
    suspend fun readTextFile(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ── 导入：AI 提取 ──

    /**
     * 用 Gemma 从文本提取词条：按 ~1200 字符分块逐块提取，流式解析
     * `english => 中文 => 注释` 行，实时累计进预览列表（同英文去重）。
     */
    fun extract(text: String) {
        if (_import.value.isExtracting) return
        extractJob?.cancel()
        _import.value = ImportUi(isExtracting = true, loadingModel = true)
        extractJob = viewModelScope.launch {
            engine.ensureLoaded().onFailure { e ->
                val missing = e is com.offlinetranslator.app.engine.llm.ModelMissingException
                _import.update {
                    it.copy(isExtracting = false, loadingModel = false,
                        error = if (missing) "请先到「设置 → 模型管理」下载模型" else e.message)
                }
                return@launch
            }
            _import.update { it.copy(loadingModel = false) }

            val seen = LinkedHashMap<String, VocabDraft>() // key=english 小写，保持出现顺序
            try {
                for (chunk in chunked(text)) {
                    val sb = StringBuilder()
                    // 提取是确定性任务（照搬词表、补释义），低温防止乱编/漏词，与 iOS 对齐。
                    engine.generateStream(PromptTemplates.extractVocab(chunk), temperature = 0.2f).collect { token ->
                        sb.append(token)
                        // 流式解析完整行，实时刷预览。
                        var nl = sb.indexOf("\n")
                        while (nl >= 0) {
                            parseLine(sb.substring(0, nl))?.let { d ->
                                seen.putIfAbsent(d.english.lowercase(), d)
                            }
                            sb.delete(0, nl + 1)
                            nl = sb.indexOf("\n")
                        }
                        _import.update {
                            it.copy(extractedCount = seen.size, drafts = seen.values.toList())
                        }
                    }
                    parseLine(sb.toString())?.let { d -> seen.putIfAbsent(d.english.lowercase(), d) }
                    _import.update { it.copy(extractedCount = seen.size, drafts = seen.values.toList()) }
                }
                _import.update { it.copy(isExtracting = false) }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _import.update { it.copy(isExtracting = false, error = t.message) }
            }
        }
    }

    fun cancelExtract() {
        extractJob?.cancel()
        _import.update { it.copy(isExtracting = false, loadingModel = false) }
    }

    fun removeDraft(d: VocabDraft) {
        _import.update { ui ->
            val next = ui.drafts - d
            ui.copy(drafts = next, extractedCount = next.size)
        }
    }

    fun resetImport() {
        extractJob?.cancel()
        _import.value = ImportUi()
    }

    /** 保存为新单词本。 */
    fun saveBook(name: String, purpose: String, dailyGoal: Int, onDone: () -> Unit) {
        val drafts = _import.value.drafts
        if (name.isBlank() || drafts.isEmpty()) return
        viewModelScope.launch {
            val bookId = dao.insertBook(
                WordBookEntity(
                    name = name.trim(), purpose = purpose.trim(),
                    dailyGoal = dailyGoal, createdAt = System.currentTimeMillis(),
                )
            )
            dao.insertEntries(drafts.map {
                WordEntryEntity(
                    bookId = bookId, english = it.english, chinese = it.chinese,
                    note = it.note, createdAt = System.currentTimeMillis(),
                )
            })
            resetImport()
            onDone()
        }
    }

    fun deleteBook(id: Long) {
        viewModelScope.launch {
            dao.deleteEntries(id)
            dao.deleteBook(id)
        }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { dao.deleteEntry(id) }
    }

    // ── 测试 ──

    /**
     * 组测试批次。
     * @param dailyOnly true=今日学习（未掌握词优先最久没见的，取 dailyGoal 个）；
     *                  false=全量抽查（整本随机洗牌，含已掌握）。
     */
    suspend fun buildQuizBatch(book: WordBookEntity, dailyOnly: Boolean): List<WordEntryEntity> {
        val all = dao.entriesOnce(book.id)
        return if (dailyOnly) {
            all.filter { it.proficiency < 3 }
                .sortedBy { it.lastSeenAt } // 优先没学过/最久没见
                .take(book.dailyGoal)
                .shuffled()
        } else {
            all.shuffled()
        }
    }

    /** 自评：认识 +1（封顶 3），不认识归 0。 */
    fun grade(entry: WordEntryEntity, known: Boolean) {
        viewModelScope.launch {
            val p = if (known) (entry.proficiency + 1).coerceAtMost(3) else 0
            dao.updateProficiency(entry.id, p, System.currentTimeMillis())
        }
    }

    // ── util ──

    private fun chunked(text: String, size: Int = 1200): List<String> {
        val lines = text.lines()
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in lines) {
            if (cur.length + line.length + 1 > size && cur.isNotEmpty()) {
                chunks.add(cur.toString())
                cur.clear()
            }
            cur.appendLine(line)
        }
        if (cur.isNotBlank()) chunks.add(cur.toString())
        return chunks
    }

    private fun parseLine(raw: String): VocabDraft? {
        val line = PromptTemplates.trimAtStop(raw).trim()
        if (!line.contains("=>")) return null
        val parts = line.split("=>").map { it.trim().trim('「', '」', '"') }
        val english = parts.getOrNull(0).orEmpty()
        val chinese = parts.getOrNull(1).orEmpty()
        val note = parts.getOrNull(2).orEmpty()
        // 防呆：英文列必须含字母且不过长；中文列非空。
        if (english.none { it.isLetter() } || english.length > 60 || chinese.isBlank()) return null
        return VocabDraft(english, chinese, note)
    }
}
