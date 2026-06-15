package com.offlinetranslator.app.feature.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 备份编解码 + 合并去重纯函数。无 Android DB/IO/UI 依赖，
 * 仅用 [org.json] 处理跨平台 JSON v1。
 */
object BackupCodec {

    // ---------- encode ----------

    fun encode(backup: Backup): String {
        val root = JSONObject()
        root.put("format", backup.format)
        root.put("version", backup.version)
        root.put("exportedAt", backup.exportedAt)
        root.put("platform", backup.platform)

        val books = JSONArray()
        for (book in backup.wordBooks) books.put(encodeBook(book))
        root.put("wordBooks", books)

        val translations = JSONArray()
        for (t in backup.translations) translations.put(encodeTranslation(t))
        root.put("translations", translations)

        val chats = JSONArray()
        for (c in backup.chats) chats.put(encodeChat(c))
        root.put("chats", chats)

        backup.config?.let { root.put("config", encodeConfig(it)) }

        return root.toString(2)
    }

    private fun encodeSrs(srs: BackupSrs): JSONObject = JSONObject().apply {
        put("box", srs.box)
        put("dueAt", srs.dueAt)
        put("missCount", srs.missCount)
        put("lastReviewedAt", srs.lastReviewedAt)
    }

    private fun encodeEntry(entry: BackupEntry): JSONObject = JSONObject().apply {
        put("english", entry.english)
        put("chinese", entry.chinese)
        put("note", entry.note)
        put("createdAt", entry.createdAt)
        entry.srs?.let { put("srs", encodeSrs(it)) }
    }

    private fun encodeBook(book: BackupBook): JSONObject = JSONObject().apply {
        put("name", book.name)
        put("purpose", book.purpose)
        put("dailyGoal", book.dailyGoal)
        put("createdAt", book.createdAt)
        val entries = JSONArray()
        for (e in book.entries) entries.put(encodeEntry(e))
        put("entries", entries)
    }

    private fun encodeTranslation(t: BackupTranslation): JSONObject = JSONObject().apply {
        put("sourceText", t.sourceText)
        put("translatedText", t.translatedText)
        put("sourceLang", t.sourceLang)
        put("targetLang", t.targetLang)
        put("createdAt", t.createdAt)
        put("starred", t.starred)
        t.srs?.let { put("srs", encodeSrs(it)) }
    }

    private fun encodeMessage(m: BackupMessage): JSONObject = JSONObject().apply {
        put("role", m.role)
        put("content", m.content)
        put("createdAt", m.createdAt)
    }

    private fun encodeChat(c: BackupChat): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        put("updatedAt", c.updatedAt)
        put("modelId", c.modelId)
        c.summary?.let { put("summary", it) }
        put("summarizedCount", c.summarizedCount)
        val messages = JSONArray()
        for (m in c.messages) messages.put(encodeMessage(m))
        put("messages", messages)
    }

    private fun encodeConfig(c: BackupConfig): JSONObject = JSONObject().apply {
        put("reminderEnabled", c.reminderEnabled)
        put("reminderHour", c.reminderHour)
        put("reminderMinute", c.reminderMinute)
        c.uiLanguage?.let { put("uiLanguage", it) }
        c.activeModelId?.let { put("activeModelId", it) }
        put("streakLastDay", c.streakLastDay)
        put("streakCurrent", c.streakCurrent)
        put("streakLongest", c.streakLongest)
    }

    // ---------- decode ----------

    fun decode(json: String): Result<Backup> {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return Result.failure(IllegalArgumentException("非法 JSON: ${e.message}"))
        }

        val format = root.optString("format", "")
        if (format != BACKUP_FORMAT) {
            return Result.failure(IllegalArgumentException("格式不匹配: format=$format"))
        }
        val version = root.optInt("version", 0)
        if (version !in 1..BACKUP_VERSION) {
            return Result.failure(IllegalArgumentException("不支持的版本: version=$version"))
        }

        return try {
            val backup = Backup(
                format = format,
                version = version,
                exportedAt = root.optLong("exportedAt", 0L),
                platform = root.optString("platform", ""),
                wordBooks = decodeBooks(root.optJSONArray("wordBooks")),
                translations = decodeTranslations(root.optJSONArray("translations")),
                chats = decodeChats(root.optJSONArray("chats")),
                config = root.optJSONObject("config")?.let { decodeConfig(it) },
            )
            Result.success(backup)
        } catch (e: JSONException) {
            Result.failure(IllegalArgumentException("解析失败: ${e.message}"))
        }
    }

    private fun decodeSrs(obj: JSONObject?): BackupSrs? {
        if (obj == null) return null
        return BackupSrs(
            box = obj.optInt("box", 0),
            dueAt = obj.optLong("dueAt", 0L),
            missCount = obj.optInt("missCount", 0),
            lastReviewedAt = obj.optLong("lastReviewedAt", 0L),
        )
    }

    private fun decodeEntry(obj: JSONObject): BackupEntry = BackupEntry(
        english = obj.optString("english", ""),
        chinese = obj.optString("chinese", ""),
        note = obj.optString("note", ""),
        createdAt = obj.optLong("createdAt", 0L),
        srs = decodeSrs(obj.optJSONObject("srs")),
    )

    private fun decodeBooks(arr: JSONArray?): List<BackupBook> {
        if (arr == null) return emptyList()
        val out = ArrayList<BackupBook>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val entriesArr = obj.optJSONArray("entries")
            val entries = if (entriesArr == null) emptyList() else
                (0 until entriesArr.length()).map { decodeEntry(entriesArr.getJSONObject(it)) }
            out.add(
                BackupBook(
                    name = obj.optString("name", ""),
                    purpose = obj.optString("purpose", ""),
                    dailyGoal = obj.optInt("dailyGoal", 0),
                    createdAt = obj.optLong("createdAt", 0L),
                    entries = entries,
                )
            )
        }
        return out
    }

    private fun decodeTranslations(arr: JSONArray?): List<BackupTranslation> {
        if (arr == null) return emptyList()
        val out = ArrayList<BackupTranslation>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            out.add(
                BackupTranslation(
                    sourceText = obj.optString("sourceText", ""),
                    translatedText = obj.optString("translatedText", ""),
                    sourceLang = obj.optString("sourceLang", ""),
                    targetLang = obj.optString("targetLang", ""),
                    createdAt = obj.optLong("createdAt", 0L),
                    starred = obj.optBoolean("starred", false),
                    srs = decodeSrs(obj.optJSONObject("srs")),
                )
            )
        }
        return out
    }

    private fun decodeChats(arr: JSONArray?): List<BackupChat> {
        if (arr == null) return emptyList()
        val out = ArrayList<BackupChat>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val msgArr = obj.optJSONArray("messages")
            val messages = if (msgArr == null) emptyList() else
                (0 until msgArr.length()).map {
                    val m = msgArr.getJSONObject(it)
                    BackupMessage(
                        role = m.optString("role", ""),
                        content = m.optString("content", ""),
                        createdAt = m.optLong("createdAt", 0L),
                    )
                }
            out.add(
                BackupChat(
                    id = obj.optString("id", ""),
                    title = obj.optString("title", ""),
                    updatedAt = obj.optLong("updatedAt", 0L),
                    modelId = obj.optString("modelId", ""),
                    summary = if (obj.has("summary") && !obj.isNull("summary")) obj.optString("summary") else null,
                    summarizedCount = obj.optInt("summarizedCount", 0),
                    messages = messages,
                )
            )
        }
        return out
    }

    private fun decodeConfig(obj: JSONObject): BackupConfig = BackupConfig(
        reminderEnabled = obj.optBoolean("reminderEnabled", false),
        reminderHour = obj.optInt("reminderHour", 0),
        reminderMinute = obj.optInt("reminderMinute", 0),
        uiLanguage = if (obj.has("uiLanguage") && !obj.isNull("uiLanguage")) obj.optString("uiLanguage") else null,
        activeModelId = if (obj.has("activeModelId") && !obj.isNull("activeModelId")) obj.optString("activeModelId") else null,
        streakLastDay = obj.optLong("streakLastDay", 0L),
        streakCurrent = obj.optInt("streakCurrent", 0),
        streakLongest = obj.optInt("streakLongest", 0),
    )

    // ---------- merge ----------

    fun merge(current: Backup, incoming: Backup): MergePlan {
        // Books by trimmed name.
        val currentBooksByKey = current.wordBooks.associateBy { it.name.trim() }
        val newBooks = ArrayList<BackupBook>()
        val entriesToExistingBook = LinkedHashMap<String, List<BackupEntry>>()

        for (book in incoming.wordBooks) {
            val key = book.name.trim()
            val existing = currentBooksByKey[key]
            if (existing == null) {
                newBooks.add(book)
            } else {
                val existingEntryKeys = existing.entries
                    .map { it.english.trim().lowercase() }
                    .toHashSet()
                val toAdd = book.entries.filter {
                    it.english.trim().lowercase() !in existingEntryKeys
                }
                if (toAdd.isNotEmpty()) {
                    entriesToExistingBook[book.name] = toAdd
                }
            }
        }

        // Translations by (sourceText, sourceLang, targetLang).
        val currentTransKeys = current.translations
            .map { transKey(it.sourceText, it.sourceLang, it.targetLang) }
            .toHashSet()
        val newTranslations = incoming.translations.filter {
            transKey(it.sourceText, it.sourceLang, it.targetLang) !in currentTransKeys
        }

        // Chats by id.
        val currentChatIds = current.chats.map { it.id }.toHashSet()
        val newChats = incoming.chats.filter { it.id !in currentChatIds }

        // Config / streak resolution.
        val resolvedConfig = incoming.config?.let { inc ->
            val cur = current.config
            val resolvedLongest = maxOf(cur?.streakLongest ?: 0, inc.streakLongest)
            val (resolvedLastDay, resolvedCurrent) =
                if (cur == null || inc.streakLastDay > cur.streakLastDay) {
                    inc.streakLastDay to inc.streakCurrent
                } else {
                    cur.streakLastDay to cur.streakCurrent
                }
            inc.copy(
                streakLastDay = resolvedLastDay,
                streakCurrent = resolvedCurrent,
                streakLongest = resolvedLongest,
            )
        }

        val addedEntries = newBooks.sumOf { it.entries.size } +
            entriesToExistingBook.values.sumOf { it.size }

        return MergePlan(
            newBooks = newBooks,
            entriesToExistingBook = entriesToExistingBook,
            newTranslations = newTranslations,
            newChats = newChats,
            resolvedConfig = resolvedConfig,
            addedBooks = newBooks.size,
            addedEntries = addedEntries,
            addedTranslations = newTranslations.size,
            addedChats = newChats.size,
        )
    }

    private fun transKey(sourceText: String, sourceLang: String, targetLang: String): String =
        "$sourceText $sourceLang $targetLang"
}
