package com.offlinetranslator.app.core.designsystem.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = SurfaceLight,
    primaryContainer = BrandPrimary.copy(alpha = 0.16f),
    onPrimaryContainer = BrandPrimaryDark,
    secondary = BrandSecondary,
    onSecondary = SurfaceLight,
    secondaryContainer = BrandSecondary.copy(alpha = 0.16f),
    onSecondaryContainer = BrandSecondaryDark,
    tertiary = BrandTertiary,
    background = BgLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = BgLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OnSurfaceVariantLight.copy(alpha = 0.4f),
    outlineVariant = OutlineLight,
    error = StatusError,
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = OnSurfaceDark,
    primaryContainer = BrandPrimary.copy(alpha = 0.24f),
    onPrimaryContainer = BrandPrimary,
    secondary = BrandSecondary,
    onSecondary = OnSurfaceDark,
    secondaryContainer = BrandSecondary.copy(alpha = 0.24f),
    onSecondaryContainer = BrandSecondary,
    tertiary = BrandTertiary,
    background = BgDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OnSurfaceVariantDark.copy(alpha = 0.4f),
    outlineVariant = OutlineDark,
    error = StatusError,
)

@Composable
fun OfflineTranslatorTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // We use enableEdgeToEdge() in MainActivity, so we only adjust the
            // status/nav bar icon contrast here (color is already transparent).
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val ctx = LocalContext.current
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalSupportsBlur provides supportsBlur,
                LocalIsDark provides darkTheme,
                content = content,
            )
        }
    )
}

val LocalSupportsBlur = androidx.compose.runtime.staticCompositionLocalOf { false }
val LocalIsDark = androidx.compose.runtime.staticCompositionLocalOf { false }
