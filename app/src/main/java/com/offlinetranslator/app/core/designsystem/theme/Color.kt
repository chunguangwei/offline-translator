package com.offlinetranslator.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// Brand — 暖色主题，对齐 logo 渐变（#FFB152 琥珀 → #FF6F61 珊瑚 → #C2479B 品红）。
// 各界面通过 MaterialTheme.colorScheme.primary/secondary/tertiary 取色，改这里即全局换肤。
val BrandPrimary = Color(0xFFFF6F61)       // 珊瑚（主色，logo 渐变中段）
val BrandPrimaryDark = Color(0xFFE2564A)   // 深珊瑚（容器/暗态强调）
val BrandSecondary = Color(0xFFFFB152)     // 琥珀（logo 渐变起点）
val BrandSecondaryDark = Color(0xFFE08A2E)
val BrandTertiary = Color(0xFFC2479B)      // 品红（logo 渐变末端，与主色构成暖色渐变）

// Light
val BgLight = Color(0xFFF7F8FB)
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF0E1118)
val OnSurfaceVariantLight = Color(0xFF5C6573)
val OutlineLight = Color(0x22000000)

// Dark
val BgDark = Color(0xFF0E1118)
val SurfaceDark = Color(0xFF161A24)
val OnSurfaceDark = Color(0xFFF2F4F8)
val OnSurfaceVariantDark = Color(0xFF9AA1AF)
val OutlineDark = Color(0x33FFFFFF)

// Status
val StatusSuccess = Color(0xFF34D399)
val StatusWarning = Color(0xFFFBBF24)
val StatusError = Color(0xFFF87171)

// Gradient stops
val GradientBlueStart = Color(0xFF5B9BFF)
val GradientBlueEnd = Color(0xFFA78BFA)
val GradientPinkStart = Color(0xFFFFB4E1)
val GradientPinkEnd = Color(0xFFFF8FBE)
val GradientMintStart = Color(0xFFA8F2D5)
val GradientMintEnd = Color(0xFF22D3B5)
val GradientAmberStart = Color(0xFFFFE6A0)
val GradientAmberEnd = Color(0xFFFFB347)
