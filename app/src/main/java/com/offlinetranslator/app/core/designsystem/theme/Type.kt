package com.offlinetranslator.app.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val baseDisplay = TextStyle(fontWeight = FontWeight.SemiBold)
private val baseTitle = TextStyle(fontWeight = FontWeight.Medium)
private val baseBody = TextStyle(fontWeight = FontWeight.Normal)

val AppTypography = Typography(
    displayLarge = baseDisplay.copy(fontSize = 48.sp, lineHeight = 56.sp),
    displayMedium = baseDisplay.copy(fontSize = 36.sp, lineHeight = 44.sp),
    displaySmall = baseDisplay.copy(fontSize = 28.sp, lineHeight = 36.sp),

    headlineLarge = baseDisplay.copy(fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = baseDisplay.copy(fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = baseTitle.copy(fontSize = 18.sp, lineHeight = 24.sp),

    titleLarge = baseTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = baseTitle.copy(fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = baseTitle.copy(fontSize = 14.sp, lineHeight = 20.sp),

    bodyLarge = baseBody.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = baseBody.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = baseBody.copy(fontSize = 12.sp, lineHeight = 16.sp),

    labelLarge = baseTitle.copy(fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = baseTitle.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = baseTitle.copy(fontSize = 11.sp, lineHeight = 14.sp),
)
