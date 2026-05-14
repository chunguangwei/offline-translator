package com.offlinetranslator.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.core.data.AppPreferences
import com.offlinetranslator.app.core.data.AppPreferencesRepository
import com.offlinetranslator.app.core.data.InferenceBackend
import com.offlinetranslator.app.core.data.ModelSource
import com.offlinetranslator.app.core.designsystem.theme.ThemeMode
import com.offlinetranslator.app.core.i18n.AppLanguage
import com.offlinetranslator.app.core.i18n.LocaleManager
import com.offlinetranslator.app.engine.llm.EngineStatus
import com.offlinetranslator.app.engine.llm.GemmaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferencesRepository,
    private val engine: GemmaEngine,
) : ViewModel() {

    val state: StateFlow<AppPreferences> = prefs.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    /** Live engine status (for showing actual backend used after fallback). */
    val engineStatus: StateFlow<EngineStatus> = engine.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineStatus())

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefs.setTheme(mode) }
    fun setLanguage(lang: AppLanguage) = viewModelScope.launch {
        prefs.setLanguage(lang)
        LocaleManager.apply(lang)
    }
    fun setModelSource(src: ModelSource) = viewModelScope.launch { prefs.setModelSource(src) }
    fun setBackend(b: InferenceBackend) = viewModelScope.launch {
        if (prefs.flow.first().backend == b) return@launch
        prefs.setBackend(b)
        // The engine was loaded with the previous backend; unload so the next
        // ensureLoaded() picks up the new preference. We don't proactively
        // reload here because that's the user's next action.
        engine.unload()
    }
    fun setCustomMirrorBase(url: String) = viewModelScope.launch { prefs.setCustomMirrorBase(url) }
}
