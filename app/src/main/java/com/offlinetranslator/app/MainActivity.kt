package com.offlinetranslator.app

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.offlinetranslator.app.core.designsystem.theme.OfflineTranslatorTheme
import com.offlinetranslator.app.feature.shell.AppShell
import com.offlinetranslator.app.feature.shell.AppShellViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity host. Compose Navigation drives all screens.
 *
 * Inherits [AppCompatActivity] (instead of `ComponentActivity`) so that
 * `AppCompatDelegate.setApplicationLocales()` reliably propagates the per-app
 * language preference on **all API levels we support (26+)**:
 *
 *  - On API 33+, the framework's `LocaleManager` natively handles the change,
 *    but AppCompat is still the canonical entry point that ties our settings
 *    UI to the system per-app language picker.
 *  - On API 32 and below, AppCompat is the *only* path that works — without
 *    it, calling `setApplicationLocales` is silently ignored.
 *
 * We removed `locale` and `layoutDirection` from `<activity android:configChanges>`
 * in the manifest so Android recreates the Activity on locale change, which
 * is what makes the in-memory Compose tree pick up the new resource bundle.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppShellViewModel = hiltViewModel()
            val themeMode by vm.themeMode.collectAsState()
            OfflineTranslatorTheme(themeMode = themeMode) {
                AppShell()
            }
        }
    }
}
