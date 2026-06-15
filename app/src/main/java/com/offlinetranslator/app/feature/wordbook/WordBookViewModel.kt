package com.offlinetranslator.app.feature.wordbook

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.core.data.db.ReviewCardDao
import com.offlinetranslator.app.core.data.db.ReviewCardEntity
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 提取出的词条草稿（预览阶段，可删错项后再入库）。 */
data class VocabDraft(val english: String, val chinese: String, val note: String)

/** 批次内按英文键（trim+lowercase）去重 + 排除 existingKeys，保持原顺序；空英文丢弃。 */
fun dedupDrafts(drafts: List<VocabDraft>, existingKeys: Set<String>): List<VocabDraft> {
    val seen = HashSet(existingKeys)
    val out = ArrayList<VocabDraft>()
    for (d in drafts) {
        val k = d.english.trim().lowercase()
        if (k.isEmpty()) continue
        if (seen.add(k)) out.add(d)
    }
    return out
}

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
    private val reviewCardDao: ReviewCardDao,
) : ViewModel() {

    val books = dao.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<WordBookEntity>())

    private val _import = MutableStateFlow(ImportUi())
    val importUi = _import.asStateFlow()

    private var extractJob: Job? = null

    // 每本单词本的词条流按 bookId 缓存：同一本始终返回同一个 StateFlow。
    // 否则每次 Compose 重组都新建一条 stateIn（重发 emptyList→list，且泄漏 5s 上游协程），
    // 与详情页 LaunchedEffect 形成无限重组 → 闪烁、协程爆炸最终崩溃。
    private val entryFlows = mutableMapOf<Long, StateFlow<List<WordEntryEntity>>>()

    fun entries(bookId: Long): StateFlow<List<WordEntryEntity>> = entryFlows.getOrPut(bookId) {
        dao.observeEntries(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<WordEntryEntity>())
    }

    // ── 导入：读文件 ──

    /** 读取用户选择的文本文件内容（失败返回 null）。RTF（备忘录/Pages 导出）自动提纯。 */
    suspend fun readTextFile(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.trimStart().startsWith("{\\rtf")) rtfToPlainText(raw) else raw
        }?.takeIf { it.isNotBlank() }
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
                                seen.putIfAbsent(d.english.trim().lowercase(), d)
                            }
                            sb.delete(0, nl + 1)
                            nl = sb.indexOf("\n")
                        }
                        _import.update {
                            it.copy(extractedCount = seen.size, drafts = seen.values.toList())
                        }
                    }
                    parseLine(sb.toString())?.let { d -> seen.putIfAbsent(d.english.trim().lowercase(), d) }
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
        val drafts = dedupDrafts(_import.value.drafts, emptySet())
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
            // 为该本所有词条建 SRS 复习卡（insertEntries 不回传 id，需查回）。
            val entries = dao.entriesOnce(bookId) // 该本全部词条（含新插入的 id）
            val now = System.currentTimeMillis()
            reviewCardDao.insertAll(entries.map {
                ReviewCardEntity(
                    sourceType = "WORD_ENTRY", sourceId = it.id,
                    box = 0, dueAt = now, missCount = 0, lastReviewedAt = 0, createdAt = now,
                )
            })
            resetImport()
            onDone()
        }
    }

    /** 把当前导入草稿(_import.drafts)合并去重后加入已有单词本，并为新词建 SRS 卡。 */
    fun addExtractedToBook(bookId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            val existingKeys = dao.entriesOnce(bookId).map { it.english.trim().lowercase() }.toSet()
            val toAdd = dedupDrafts(_import.value.drafts, existingKeys)
            if (toAdd.isNotEmpty()) {
                val now = System.currentTimeMillis()
                dao.insertEntries(toAdd.map {
                    WordEntryEntity(
                        bookId = bookId, english = it.english, chinese = it.chinese,
                        note = it.note, createdAt = now,
                    )
                })
                // insertAll 对 (sourceType, sourceId) 唯一索引用 IGNORE，已有卡的词条会被忽略，仅新词建卡。
                val cardNow = System.currentTimeMillis()
                reviewCardDao.insertAll(dao.entriesOnce(bookId).map {
                    ReviewCardEntity(
                        sourceType = "WORD_ENTRY", sourceId = it.id,
                        box = 0, dueAt = cardNow, missCount = 0, lastReviewedAt = 0, createdAt = cardNow,
                    )
                })
            }
            resetImport()
            onDone()
        }
    }

    /** 手动加一条；英文键在本内已存在则返回 false 不加。en/zh 必填。回调在主线程回传结果。 */
    fun addManualEntry(bookId: Long, english: String, chinese: String, note: String, onResult: (Boolean) -> Unit) {
        val en = english.trim()
        val zh = chinese.trim()
        val nt = note.trim()
        if (en.isBlank() || zh.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val dup = dao.entriesOnce(bookId).any { it.english.trim().lowercase() == en.lowercase() }
            if (dup) {
                onResult(false)
                return@launch
            }
            val now = System.currentTimeMillis()
            dao.insertEntries(listOf(
                WordEntryEntity(bookId = bookId, english = en, chinese = zh, note = nt, createdAt = now)
            ))
            reviewCardDao.insertAll(dao.entriesOnce(bookId).map {
                ReviewCardEntity(
                    sourceType = "WORD_ENTRY", sourceId = it.id,
                    box = 0, dueAt = now, missCount = 0, lastReviewedAt = 0, createdAt = now,
                )
            })
            onResult(true)
        }
    }

    fun updateEntry(id: Long, english: String, chinese: String, note: String) {
        viewModelScope.launch {
            dao.updateEntry(id, english.trim(), chinese.trim(), note.trim())
        }
    }

    fun updateBook(id: Long, name: String, purpose: String, dailyGoal: Int) {
        viewModelScope.launch {
            dao.updateBook(id, name.trim(), purpose.trim(), dailyGoal.coerceAtLeast(1))
        }
    }

    fun deleteBook(id: Long) {
        viewModelScope.launch {
            // 先删该本所有词条对应的复习卡，再删词条/本，保持 review_card 同步。
            val ids = dao.entriesOnce(id).map { it.id }
            reviewCardDao.deleteBySourceIds("WORD_ENTRY", ids)
            dao.deleteEntries(id)
            dao.deleteBook(id)
        }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            reviewCardDao.deleteBySource("WORD_ENTRY", id)
            dao.deleteEntry(id)
        }
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
            // 全量抽查仅练手，不改 SRS 档位（dueAt/box 不动），避免打乱节奏
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

/**
 * 最小 RTF → 纯文本（Android 无内置 RTF 解析器）。处理：分组 {}、控制字 \word[N]、
 * 段落/制表符、忽略目的组（fonttbl/colortbl 等与 \*）、\uN 十进制 Unicode、
 * 以及连续 \'hh 十六进制字节按 GBK 解码（中文 RTF 常用 charset134=GBK）。尽力而为。
 */
internal fun rtfToPlainText(rtf: String): String {
    val out = StringBuilder()
    val hex = ArrayList<Byte>()
    fun flushHex() {
        if (hex.isNotEmpty()) {
            runCatching { out.append(String(hex.toByteArray(), charset("GBK"))) }
            hex.clear()
        }
    }
    val skipWords = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header",
        "footer", "object", "themedata", "datastore", "latentstyles", "rsidtbl")
    var i = 0
    val n = rtf.length
    var depth = 0
    var skipDepth = -1            // ≥0 表示正在跳过某目的组（含此深度内全部内容）
    var ucSkip = 1               // \ucN：\uN 之后要跳过的回退字符数
    while (i < n) {
        when (val c = rtf[i]) {
            '{' -> { flushHex(); depth++; i++ }
            '}' -> { flushHex(); if (skipDepth in 0..depth) skipDepth = -1; depth--; i++ }
            '\\' -> {
                if (i + 1 >= n) { i++; continue }
                val d = rtf[i + 1]
                when {
                    d == '\'' -> { // \'hh 十六进制字节
                        val b = if (i + 3 < n) rtf.substring(i + 2, i + 4).toIntOrNull(16) else null
                        if (b != null && skipDepth < 0) hex.add(b.toByte())
                        i += 4
                    }
                    d == '\\' || d == '{' || d == '}' -> { flushHex(); if (skipDepth < 0) out.append(d); i += 2 }
                    d == '*' -> { flushHex(); if (skipDepth < 0) skipDepth = depth; i += 2 } // 忽略目的组
                    d.isLetter() -> {
                        flushHex()
                        var j = i + 1
                        while (j < n && rtf[j].isLetter()) j++
                        val word = rtf.substring(i + 1, j)
                        var k = j
                        val neg = k < n && rtf[k] == '-'
                        if (neg) k++
                        val numStart = k
                        while (k < n && rtf[k].isDigit()) k++
                        val param = if (k > numStart) rtf.substring(numStart, k).toInt().let { if (neg) -it else it } else null
                        var next = if (k < n && rtf[k] == ' ') k + 1 else k // 一个分隔空格被吞掉
                        when (word) {
                            "par", "line", "sect", "pard" -> if (skipDepth < 0) out.append('\n')
                            "tab" -> if (skipDepth < 0) out.append('\t')
                            "uc" -> ucSkip = param ?: 1
                            "u" -> {
                                if (skipDepth < 0 && param != null) {
                                    val code = if (param < 0) param + 65536 else param
                                    out.append(if (code == 8232 || code == 8233) '\n' else code.toChar())
                                }
                                var s = 0
                                while (s < ucSkip && next < n) {
                                    if (rtf[next] == '\\' && next + 1 < n && rtf[next + 1] == '\'') next += 4 else next += 1
                                    s++
                                }
                            }
                            in skipWords -> if (skipDepth < 0) skipDepth = depth
                            else -> { /* 其余控制字丢弃 */ }
                        }
                        i = next
                    }
                    else -> { flushHex(); i += 2 } // 控制符号 \~ \- 等
                }
            }
            '\r', '\n' -> i++ // RTF 源码里的换行无意义
            else -> { flushHex(); if (skipDepth < 0) out.append(c); i++ }
        }
    }
    flushHex()
    return out.toString().trim()
}
