package com.offlinetranslator.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.offlinetranslator.app.core.designsystem.theme.LocalIsDark
import com.offlinetranslator.app.core.designsystem.theme.LocalSupportsBlur

/**
 * Glassmorphism card.
 * - On Android 12+: real backdrop blur via [Modifier.blur] is applied externally
 *   (use [BlurredBackground] for full-screen). Card itself uses translucent fill.
 * - On older devices: gracefully degrades to translucent solid + soft border.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    tint: Color? = null,
    borderWidth: Dp = 1.dp,
    content: @Composable () -> Unit,
) {
    val isDark = LocalIsDark.current
    val baseTint = tint ?: if (isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color.White.copy(alpha = 0.65f)
    }
    val borderColor = if (isDark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.85f)
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        baseTint,
                        baseTint.copy(alpha = (baseTint.alpha * 0.7f).coerceIn(0f, 1f)),
                    )
                ),
                shape = shape,
            )
            .border(borderWidth, borderColor, shape)
            .padding(contentPadding),
    ) {
        content()
    }
}

/**
 * Aurora gradient background — soft pastel blobs to give the page personality.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isDark = LocalIsDark.current
    val brush = if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF0E1118),
                Color(0xFF1B2240),
                Color(0xFF12172A),
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFEAF1FF),
                Color(0xFFF7F8FB),
                Color(0xFFFFF1F8),
            )
        )
    }
    Box(modifier = modifier.background(brush)) {
        content()
    }
}

@Composable
fun rememberSupportsBlur(): Boolean = LocalSupportsBlur.current
