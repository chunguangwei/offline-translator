package com.offlinetranslator.app.feature.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.core.data.AppPreferencesRepository
import com.offlinetranslator.app.core.data.db.ReviewCardDao
import com.offlinetranslator.app.core.data.db.ReviewCardEntity
import com.offlinetranslator.app.core.data.db.TranslationDao
import com.offlinetranslator.app.core.data.db.WordBookDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class LearnViewModel @Inject constructor(
    private val reviewCardDao: ReviewCardDao,
    private val wordBookDao: WordBookDao,
    private val translationDao: TranslationDao,
    private val prefs: AppPreferencesRepository,
) : ViewModel() {

    /** 一张可复习卡（已解析出正反面内容）。front=出题面，back=答案面。 */
    data class ReviewItem(val card: ReviewCardEntity, val front: String, val back: String, val note: String)

    data class BookDue(val bookId: Long, val name: String, val due: Int)

    /** 单词本详情统计：today 到期数、已掌握（box≥MAX_BOX）数及其源行 id 集合。 */
    data class BookStats(val due: Int, val mastered: Int, val masteredIds: Set<Long>)

    data class LearnUi(
        val displayStreak: Int = 0,
        val todayDue: Int = 0,
        val hardCount: Int = 0,
        val perBook: List<BookDue> = emptyList(),
        val starredDue: Int = 0,
        val loaded: Boolean = false,
    )

    private val _ui = MutableStateFlow(LearnUi())
    val ui: StateFlow<LearnUi> = _ui.asStateFlow()

    private val STARRED_DAILY_NEW_LIMIT = 10
    private val HARD_MISS_THRESHOLD = 3

    private fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    fun refresh() { viewModelScope.launch { _ui.value = computeUi() } }

    private suspend fun computeUi(): LearnUi {
        val now = System.currentTimeMillis()
        val wordCards = reviewCardDao.byType("WORD_ENTRY")
        val starredCards = reviewCardDao.byType("STARRED")
        val books = wordBookDao.observeBooks().first()
        val perBook = books.map { book ->
            val ids = wordBookDao.entriesOnce(book.id).map { it.id }.toSet()
            val cards = wordCards.filter { it.sourceId in ids }
            val due = DuePool.select(cards, now, book.dailyGoal, { it.box }, { it.dueAt }, { it.lastReviewedAt })
            BookDue(book.id, book.name, due.size)
        }
        val starredDue = DuePool.select(starredCards, now, STARRED_DAILY_NEW_LIMIT, { it.box }, { it.dueAt }, { it.lastReviewedAt }).size
        val hardCount = reviewCardDao.hardCards(HARD_MISS_THRESHOLD).size
        val p = prefs.flow.first()
        val streak = StreakLogic.displayStreak(p.streakLastDay, p.streakCurrent, todayEpochDay())
        return LearnUi(
            displayStreak = streak,
            todayDue = perBook.sumOf { it.due } + starredDue,
            hardCount = hardCount,
            perBook = perBook,
            starredDue = starredDue,
            loaded = true,
        )
    }

    /** 跨全部单词本 + 生词本的今日到期卡，解析+打散成会话。 */
    suspend fun buildTodaySession(): List<ReviewItem> {
        val now = System.currentTimeMillis()
        val wordCards = reviewCardDao.byType("WORD_ENTRY")
        val starredCards = reviewCardDao.byType("STARRED")
        val books = wordBookDao.observeBooks().first()
        val selected = mutableListOf<ReviewCardEntity>()
        for (book in books) {
            val ids = wordBookDao.entriesOnce(book.id).map { it.id }.toSet()
            val cards = wordCards.filter { it.sourceId in ids }
            selected += DuePool.select(cards, now, book.dailyGoal, { it.box }, { it.dueAt }, { it.lastReviewedAt })
        }
        selected += DuePool.select(starredCards, now, STARRED_DAILY_NEW_LIMIT, { it.box }, { it.dueAt }, { it.lastReviewedAt })
        return selected.mapNotNull { toItem(it) }.shuffled()
    }

    /** 单个单词本的今日到期会话。 */
    suspend fun buildBookSession(bookId: Long): List<ReviewItem> {
        val now = System.currentTimeMillis()
        val ids = wordBookDao.entriesOnce(bookId).map { it.id }.toSet()
        val book = wordBookDao.observeBooks().first().firstOrNull { it.id == bookId } ?: return emptyList()
        val cards = reviewCardDao.byType("WORD_ENTRY").filter { it.sourceId in ids }
        return DuePool.select(cards, now, book.dailyGoal, { it.box }, { it.dueAt }, { it.lastReviewedAt })
            .mapNotNull { toItem(it) }.shuffled()
    }

    /** 单词本详情统计：今日到期数 + 已掌握集合。 */
    suspend fun bookStats(bookId: Long): BookStats {
        val now = System.currentTimeMillis()
        val ids = wordBookDao.entriesOnce(bookId).map { it.id }.toSet()
        val cards = reviewCardDao.byType("WORD_ENTRY").filter { it.sourceId in ids }
        val daily = wordBookDao.observeBooks().first().firstOrNull { it.id == bookId }?.dailyGoal ?: 10
        val due = DuePool.select(cards, now, daily, { it.box }, { it.dueAt }, { it.lastReviewedAt }).size
        val masteredIds = cards.filter { it.box >= SrsScheduler.MAX_BOX }.map { it.sourceId }.toSet()
        return BookStats(due, masteredIds.size, masteredIds)
    }

    /** 错题本：missCount≥阈值的卡，按错次降序解析。 */
    suspend fun buildHardSession(): List<ReviewItem> =
        reviewCardDao.hardCards(HARD_MISS_THRESHOLD).mapNotNull { toItem(it) }

    /** 解析一张卡的正反面。方向 mixed：按 card.id 奇偶定向（每张稳定）。源行已删则返回 null（孤儿卡跳过）。 */
    private suspend fun toItem(card: ReviewCardEntity): ReviewItem? = when (card.sourceType) {
        "WORD_ENTRY" -> wordBookDao.entryById(card.sourceId)?.let { e ->
            if (card.id % 2 == 0L) ReviewItem(card, e.english, e.chinese, e.note)
            else ReviewItem(card, e.chinese, e.english, e.note)
        }
        "STARRED" -> translationDao.recordById(card.sourceId)?.let { r ->
            if (card.id % 2 == 0L) ReviewItem(card, r.sourceText, r.translatedText, "")
            else ReviewItem(card, r.translatedText, r.sourceText, "")
        }
        else -> null
    }

    /** 评分落库（认识/不认识）。 */
    suspend fun grade(card: ReviewCardEntity, correct: Boolean) {
        val now = System.currentTimeMillis()
        val u = SrsScheduler.schedule(card.box, card.missCount, correct, now)
        reviewCardDao.updateState(card.id, u.box, u.dueAt, u.missCount, u.lastReviewedAt)
    }

    /** 完成≥1张复习后调用：更新连续天数。 */
    suspend fun markStudiedToday() {
        val p = prefs.flow.first()
        val today = todayEpochDay()
        val s = StreakLogic.onStudied(p.streakLastDay, p.streakCurrent, today)
        prefs.setStreak(s.lastStudyDay, s.currentStreak, maxOf(p.streakLongest, s.currentStreak))
    }
}
