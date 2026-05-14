package com.offlinetranslator.app.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.core.data.AppPreferencesRepository
import com.offlinetranslator.app.core.designsystem.theme.ThemeMode
import com.offlinetranslator.app.core.i18n.AppLanguage
import com.offlinetranslator.app.core.i18n.LocaleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val prefs: AppPreferencesRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = prefs.flow
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    init {
        // Apply persisted locale on cold start.
        viewModelScope.launch {
            prefs.flow.collect { p ->
                if (p.language != AppLanguage.SYSTEM && LocaleManager.current() != p.language) {
                    LocaleManager.apply(p.language)
                }
            }
        }
    }
}
