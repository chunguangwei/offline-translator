package com.offlinetranslator.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.core.data.db.ReviewCardDao
import com.offlinetranslator.app.core.data.db.ReviewCardEntity
import com.offlinetranslator.app.core.data.db.TranslationDao
import com.offlinetranslator.app.core.data.db.TranslationEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val dao: TranslationDao,
    private val reviewCardDao: ReviewCardDao,
) : ViewModel() {

    val items: StateFlow<List<TranslationEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch {
            dao.delete(id)
            // 历史记录删除时一并清掉对应的生词本复习卡，保持复习池同步。
            reviewCardDao.deleteBySource("STARRED", id)
        }
    }

    fun clear() { viewModelScope.launch { dao.clearAll() } }

    /** 收藏/取消收藏进生词本（单词练习的素材来源）。 */
    fun setStarred(id: Long, starred: Boolean) {
        viewModelScope.launch {
            dao.setStarred(id, starred)
            // 星标 -> 建卡；取消星标 -> 删卡，保持统一复习池同步。
            val now = System.currentTimeMillis()
            if (starred) {
                reviewCardDao.insertAll(
                    listOf(
                        ReviewCardEntity(
                            sourceType = "STARRED",
                            sourceId = id,
                            box = 0,
                            dueAt = now,
                            missCount = 0,
                            lastReviewedAt = 0,
                            createdAt = now,
                        )
                    )
                )
            } else {
                reviewCardDao.deleteBySource("STARRED", id)
            }
        }
    }
}
