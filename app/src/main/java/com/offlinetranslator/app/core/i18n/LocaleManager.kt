package com.offlinetranslator.app.core.i18n

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

enum class AppLanguage(val tag: String) {
    SYSTEM(""), ZH("zh-CN"), EN("en-US");

    companion object {
        fun fromTag(tag: String?): AppLanguage = when (tag) {
            null, "" -> SYSTEM
            "zh", "zh-CN", "zh-Hans" -> ZH
            "en", "en-US" -> EN
            else -> SYSTEM
        }
    }
}

object LocaleManager {
    @Volatile
    private var inited = false

    fun init(@Suppress("unused") context: Context) {
        // Reserved for future locale persistence init.
        inited = true
    }

    fun apply(language: AppLanguage) {
        val locales = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.create(Locale.forLanguageTag(language.tag))
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun current(): AppLanguage {
        val list = AppCompatDelegate.getApplicationLocales()
        if (list.isEmpty) return AppLanguage.SYSTEM
        return AppLanguage.fromTag(list[0]?.toLanguageTag())
    }
}
