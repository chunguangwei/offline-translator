package com.offlinetranslator.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    val items: StateFlow<List<TranslationEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: Long) { viewModelScope.launch { dao.delete(id) } }
    fun clear() { viewModelScope.launch { dao.clearAll() } }
}
