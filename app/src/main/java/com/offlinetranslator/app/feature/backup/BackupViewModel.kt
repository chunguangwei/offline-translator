package com.offlinetranslator.app.feature.backup

import android.content.Context
import com.offlinetranslator.app.core.data.AppPreferencesRepository
import com.offlinetranslator.app.core.data.db.ChatDao
import com.offlinetranslator.app.core.data.db.ChatMessageEntity
import com.offlinetranslator.app.core.data.db.ChatSessionEntity
import com.offlinetranslator.app.core.data.db.ReviewCardDao
import com.offlinetranslator.app.core.data.db.ReviewCardEntity
import com.offlinetranslator.app.core.data.db.TranslationDao
import com.offlinetranslator.app.core.data.db.TranslationEntity
import com.offlinetranslator.app.core.data.db.WordBookDao
import com.offlinetranslator.app.core.data.db.WordBookEntity
import com.offlinetranslator.app.core.data.db.WordEntryEntity
import com.offlinetranslator.app.core.i18n.AppLanguage
import com.offlinetranslator.app.feature.learn.cancelDaily
import com.offlinetranslator.app.feature.learn.scheduleDaily
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 备份 IO：把当前 DB/prefs 收集成 [Backup] 模型并经 [BackupCodec] 编解码，
 * 还原时按 [MergePlan] 去重合并写回 DB。纯 IO 协调，编解码/合并逻辑全在 codec。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wordBookDao: WordBookDao,
    private val reviewCardDao: ReviewCardDao,
    private val translationDao: TranslationDao,
    private val chatDao: ChatDao,
    private val prefs: AppPreferencesRepository,
) : ViewModel() {

    sealed interface RestoreResult {
        data class Success(
            val books: Int,
            val entries: Int,
            val translations: Int,
            val chats: Int,
        ) : RestoreResult

        data class Failure(val reason: String) : RestoreResult
    }

    /** 收集当前 DB/prefs → 备份模型。 */
    suspend fun buildBackup(): Backup = withContext(Dispatchers.IO) {
        // ----- word books -----
        val wordCards = reviewCardDao.byType("WORD_ENTRY").associateBy { it.sourceId }
        val books = wordBookDao.observeBooks().first().map { book ->
            val entries = wordBookDao.entriesOnce(book.id).map { entry ->
                val card = wordCards[entry.id]
                BackupEntry(
                    english = entry.english,
                    chinese = entry.chinese,
                    note = entry.note,
                    createdAt = entry.createdAt,
                    srs = card?.let {
                        BackupSrs(it.box, it.dueAt, it.missCount, it.lastReviewedAt)
                    },
                )
            }
            BackupBook(
                name = book.name,
                purpose = book.purpose,
                dailyGoal = book.dailyGoal,
                createdAt = book.createdAt,
                entries = entries,
            )
        }

        // ----- translations -----
        val starredCards = reviewCardDao.byType("STARRED").associateBy { it.sourceId }
        val translations = translationDao.observeAll().first().map { t ->
            BackupTranslation(
                sourceText = t.sourceText,
                translatedText = t.translatedText,
                sourceLang = t.sourceLang,
                targetLang = t.targetLang,
                createdAt = t.createdAt,
                starred = t.starred,
                srs = if (t.starred) {
                    starredCards[t.id]?.let { BackupSrs(it.box, it.dueAt, it.missCount, it.lastReviewedAt) }
                } else {
                    null
                },
            )
        }

        // ----- chats -----
        val chats = chatDao.observeSessions().first().map { s ->
            val messages = chatDao.messagesOnce(s.id).map { m ->
                BackupMessage(role = m.role, content = m.content, createdAt = m.createdAt)
            }
            BackupChat(
                id = s.id,
                title = s.title,
                updatedAt = s.updatedAt,
                modelId = s.modelId,
                summary = s.summary,
                summarizedCount = s.summarizedCount,
                messages = messages,
            )
        }

        // ----- config -----
        val p = prefs.flow.first()
        val config = BackupConfig(
            reminderEnabled = p.reminderEnabled,
            reminderHour = p.reminderHour,
            reminderMinute = p.reminderMinute,
            uiLanguage = if (p.language == AppLanguage.SYSTEM) null else p.language.tag,
            activeModelId = p.activeModelId,
            streakLastDay = p.streakLastDay,
            streakCurrent = p.streakCurrent,
            streakLongest = p.streakLongest,
        )

        Backup(
            format = BACKUP_FORMAT,
            version = BACKUP_VERSION,
            exportedAt = System.currentTimeMillis(),
            platform = "android",
            wordBooks = books,
            translations = translations,
            chats = chats,
            config = config,
        )
    }

    suspend fun buildBackupJson(): String = BackupCodec.encode(buildBackup())

    suspend fun restore(json: String): RestoreResult = withContext(Dispatchers.IO) {
        val incoming = BackupCodec.decode(json).getOrElse {
            return@withContext RestoreResult.Failure("无法识别的备份文件")
        }

        val current = buildBackup()
        val plan = BackupCodec.merge(current, incoming)
        val now = System.currentTimeMillis()

        // New books → create book + entries + cards.
        for (book in plan.newBooks) {
            val bookId = wordBookDao.insertBook(
                WordBookEntity(
                    name = book.name,
                    purpose = book.purpose,
                    dailyGoal = book.dailyGoal,
                    createdAt = book.createdAt,
                ),
            )
            addEntriesWithCards(bookId, book.entries, now)
        }

        // Entries appended to existing books.
        if (plan.entriesToExistingBook.isNotEmpty()) {
            val booksByName = wordBookDao.observeBooks().first()
            for ((bookName, entries) in plan.entriesToExistingBook) {
                val bookId = booksByName.firstOrNull { it.name.trim() == bookName.trim() }?.id
                    ?: continue
                addEntriesWithCards(bookId, entries, now)
            }
        }

        // New translations (+ starred srs cards).
        for (t in plan.newTranslations) {
            val tid = translationDao.insert(
                TranslationEntity(
                    sourceText = t.sourceText,
                    translatedText = t.translatedText,
                    sourceLang = t.sourceLang,
                    targetLang = t.targetLang,
                    createdAt = t.createdAt,
                    starred = t.starred,
                ),
            )
            if (t.starred && t.srs != null) {
                reviewCardDao.insertAll(
                    listOf(
                        ReviewCardEntity(
                            sourceType = "STARRED",
                            sourceId = tid,
                            box = t.srs.box,
                            dueAt = t.srs.dueAt,
                            missCount = t.srs.missCount,
                            lastReviewedAt = t.srs.lastReviewedAt,
                            createdAt = now,
                        ),
                    ),
                )
            }
        }

        // New chats.
        for (chat in plan.newChats) {
            chatDao.upsertSession(
                ChatSessionEntity(
                    id = chat.id,
                    title = chat.title,
                    updatedAt = chat.updatedAt,
                    modelId = chat.modelId,
                    summary = chat.summary,
                    summarizedCount = chat.summarizedCount,
                ),
            )
            for (m in chat.messages) {
                chatDao.insertMessage(
                    ChatMessageEntity(
                        sessionId = chat.id,
                        role = m.role,
                        content = m.content,
                        thinking = null,
                        imageUri = null,
                        createdAt = m.createdAt,
                    ),
                )
            }
        }

        // Config.
        plan.resolvedConfig?.let { c ->
            prefs.setReminder(c.reminderEnabled, c.reminderHour, c.reminderMinute)
            prefs.setStreak(c.streakLastDay, c.streakCurrent, c.streakLongest)
            if (c.activeModelId != null) prefs.setActiveModel(c.activeModelId)
            c.uiLanguage?.let { tag ->
                val lang = AppLanguage.fromTag(tag)
                if (lang != AppLanguage.SYSTEM) prefs.setLanguage(lang)
            }
            if (c.reminderEnabled) {
                scheduleDaily(context, c.reminderHour, c.reminderMinute)
            } else {
                cancelDaily(context)
            }
        }

        RestoreResult.Success(
            books = plan.addedBooks,
            entries = plan.addedEntries,
            translations = plan.addedTranslations,
            chats = plan.addedChats,
        )
    }

    /** 插入词条并按英文匹配回填 SRS 卡片（唯一索引去重）。 */
    private suspend fun addEntriesWithCards(bookId: Long, entries: List<BackupEntry>, now: Long) {
        wordBookDao.insertEntries(
            entries.map {
                WordEntryEntity(
                    bookId = bookId,
                    english = it.english,
                    chinese = it.chinese,
                    note = it.note,
                    createdAt = it.createdAt,
                )
            },
        )
        val byKey = wordBookDao.entriesOnce(bookId).associateBy { it.english.trim().lowercase() }
        val cards = entries.mapNotNull { e ->
            byKey[e.english.trim().lowercase()]?.let { row ->
                ReviewCardEntity(
                    sourceType = "WORD_ENTRY",
                    sourceId = row.id,
                    box = e.srs?.box ?: 0,
                    dueAt = e.srs?.dueAt ?: now,
                    missCount = e.srs?.missCount ?: 0,
                    lastReviewedAt = e.srs?.lastReviewedAt ?: 0,
                    createdAt = now,
                )
            }
        }
        if (cards.isNotEmpty()) reviewCardDao.insertAll(cards)
    }
}
