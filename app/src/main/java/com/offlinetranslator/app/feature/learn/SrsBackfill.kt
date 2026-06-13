package com.offlinetranslator.app.feature.learn

import com.offlinetranslator.app.core.data.AppPreferencesRepository
import com.offlinetranslator.app.core.data.db.ReviewCardDao
import com.offlinetranslator.app.core.data.db.ReviewCardEntity
import com.offlinetranslator.app.core.data.db.TranslationDao
import com.offlinetranslator.app.core.data.db.WordBookDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** 首次启动把现有单词本词条 + 已星标历史回填成 SRS 卡。幂等：prefs 标志 + 唯一索引双重守护。 */
@Singleton
class SrsBackfill @Inject constructor(
    private val prefs: AppPreferencesRepository,
    private val reviewCardDao: ReviewCardDao,
    private val wordBookDao: WordBookDao,
    private val translationDao: TranslationDao,
) {
    suspend fun runIfNeeded() {
        if (prefs.flow.first().srsBackfilled) return
        val now = System.currentTimeMillis()
        val cards = mutableListOf<ReviewCardEntity>()
        for (book in wordBookDao.observeBooks().first()) {
            wordBookDao.entriesOnce(book.id).forEach {
                cards += SrsScheduler.backfillCard("WORD_ENTRY", it.id, now)
            }
        }
        translationDao.starredOnce().forEach {
            cards += SrsScheduler.backfillCard("STARRED", it.id, now)
        }
        reviewCardDao.insertAll(cards)   // IGNORE 冲突 → 幂等
        prefs.setSrsBackfilled(true)
    }
}
