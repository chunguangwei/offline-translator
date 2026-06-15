package com.offlinetranslator.app.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.offlinetranslator.app.core.designsystem.theme.ThemeMode
import com.offlinetranslator.app.core.i18n.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

enum class ModelSource { HF_DIRECT, HF_MIRROR, CUSTOM, LOCAL_ONLY }
enum class InferenceBackend { AUTO, GPU, CPU }

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val activeModelId: String? = null,
    val defaultModelId: String = "gemma-4-e2b-litertlm",
    val modelSource: ModelSource = ModelSource.HF_MIRROR,
    val backend: InferenceBackend = InferenceBackend.AUTO,
    val firstLaunch: Boolean = true,
    /** Custom download base URL (used when [modelSource] = CUSTOM). Should end with `/`. */
    val customMirrorBase: String = "",
    /** 问答角色预设 id（default/translator/grammar/polish/speaking），见 PromptTemplates.chatSystem。 */
    val chatRole: String = "default",
    val streakLastDay: Long = 0,
    val streakCurrent: Int = 0,
    val streakLongest: Int = 0,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val srsBackfilled: Boolean = false,
)

@Singleton
class AppPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val LANG = stringPreferencesKey("language")
        val ACTIVE_MODEL = stringPreferencesKey("active_model_id")
        val DEFAULT_MODEL = stringPreferencesKey("default_model_id")
        val MODEL_SRC = stringPreferencesKey("model_source")
        val BACKEND = stringPreferencesKey("backend")
        val FIRST = booleanPreferencesKey("first_launch")
        val CUSTOM_MIRROR = stringPreferencesKey("custom_mirror_base")
        val CHAT_ROLE = stringPreferencesKey("chat_role")
        val STREAK_LAST_DAY = longPreferencesKey("streak_last_day")
        val STREAK_CURRENT = intPreferencesKey("streak_current")
        val STREAK_LONGEST = intPreferencesKey("streak_longest")
        val REMINDER_ON = booleanPreferencesKey("reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val REMINDER_MIN = intPreferencesKey("reminder_minute")
        val SRS_BACKFILLED = booleanPreferencesKey("srs_backfilled")
    }

    val flow: Flow<AppPreferences> = context.appDataStore.data.map { prefs ->
        AppPreferences(
            themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: "SYSTEM") }
                .getOrDefault(ThemeMode.SYSTEM),
            language = AppLanguage.fromTag(prefs[Keys.LANG]),
            activeModelId = prefs[Keys.ACTIVE_MODEL],
            defaultModelId = prefs[Keys.DEFAULT_MODEL] ?: "gemma-4-e2b-litertlm",
            modelSource = runCatching { ModelSource.valueOf(prefs[Keys.MODEL_SRC] ?: "HF_MIRROR") }
                .getOrDefault(ModelSource.HF_MIRROR),
            backend = runCatching { InferenceBackend.valueOf(prefs[Keys.BACKEND] ?: "AUTO") }
                .getOrDefault(InferenceBackend.AUTO),
            firstLaunch = prefs[Keys.FIRST] ?: true,
            customMirrorBase = prefs[Keys.CUSTOM_MIRROR] ?: "",
            chatRole = prefs[Keys.CHAT_ROLE] ?: "default",
            streakLastDay = prefs[Keys.STREAK_LAST_DAY] ?: 0,
            streakCurrent = prefs[Keys.STREAK_CURRENT] ?: 0,
            streakLongest = prefs[Keys.STREAK_LONGEST] ?: 0,
            reminderEnabled = prefs[Keys.REMINDER_ON] ?: false,
            reminderHour = prefs[Keys.REMINDER_HOUR] ?: 20,
            reminderMinute = prefs[Keys.REMINDER_MIN] ?: 0,
            srsBackfilled = prefs[Keys.SRS_BACKFILLED] ?: false,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.appDataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setLanguage(lang: AppLanguage) = context.appDataStore.edit { it[Keys.LANG] = lang.tag }
    suspend fun setActiveModel(id: String?) = context.appDataStore.edit {
        if (id == null) it.remove(Keys.ACTIVE_MODEL) else it[Keys.ACTIVE_MODEL] = id
    }
    suspend fun setDefaultModel(id: String) = context.appDataStore.edit { it[Keys.DEFAULT_MODEL] = id }
    suspend fun setModelSource(src: ModelSource) = context.appDataStore.edit { it[Keys.MODEL_SRC] = src.name }
    suspend fun setBackend(b: InferenceBackend) = context.appDataStore.edit { it[Keys.BACKEND] = b.name }
    suspend fun setFirstLaunch(v: Boolean) = context.appDataStore.edit { it[Keys.FIRST] = v }
    suspend fun setCustomMirrorBase(url: String) = context.appDataStore.edit {
        it[Keys.CUSTOM_MIRROR] = url.trim()
    }
    suspend fun setChatRole(role: String) = context.appDataStore.edit { it[Keys.CHAT_ROLE] = role }
    suspend fun setStreak(lastDay: Long, current: Int, longest: Int) = context.appDataStore.edit {
        it[Keys.STREAK_LAST_DAY] = lastDay; it[Keys.STREAK_CURRENT] = current; it[Keys.STREAK_LONGEST] = longest
    }
    suspend fun setReminder(enabled: Boolean, hour: Int, minute: Int) = context.appDataStore.edit {
        it[Keys.REMINDER_ON] = enabled; it[Keys.REMINDER_HOUR] = hour; it[Keys.REMINDER_MIN] = minute
    }
    suspend fun setSrsBackfilled(v: Boolean) = context.appDataStore.edit { it[Keys.SRS_BACKFILLED] = v }
}
