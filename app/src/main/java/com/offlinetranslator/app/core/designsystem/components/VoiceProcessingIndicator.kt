package com.offlinetranslator.app.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Long-running task indicator for the Voice screen.
 *
 * Shown after the user taps stop while Gemma is doing audio→text+translation
 * (this can be 5–30s on first run). The animation reassures the user the app
 * is alive:
 *   - A trio of orbiting pulse dots traces a soft circle (says "computing")
 *   - Below: a rotating status string explaining each phase
 *   - All wrapped in a translucent card so it sits naturally inside the layout
 *
 * No external assets / no Lottie — pure Compose Canvas to keep APK size flat.
 */
@Composable
fun VoiceProcessingIndicator(
    statusKeyIndex: Int,
    statusMessages: List<String>,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "voice-proc")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rot",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 1. Orbiting tri-dot animation
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(80.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val orbitR = size.width * 0.32f
                val dotR = size.width * 0.07f * pulse

                val colors = listOf(
                    Color(0xFF8E5CFF),
                    Color(0xFFFF5FA0),
                    Color(0xFF3DD9FF),
                )

                // Soft outer ring track
                drawCircle(
                    color = Color(0xFF8E5CFF).copy(alpha = 0.10f),
                    radius = orbitR + dotR,
                    center = Offset(cx, cy),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                )

                // Three dots, 120° apart, orbiting around (cx, cy).
                colors.forEachIndexed { i, color ->
                    val angle = rotation + i * (Math.PI * 2f / 3f).toFloat()
                    val x = cx + orbitR * cos(angle.toDouble()).toFloat()
                    val y = cy + orbitR * sin(angle.toDouble()).toFloat()
                    // Glow halo
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = 0.45f), Color.Transparent),
                            center = Offset(x, y),
                            radius = dotR * 3f,
                        ),
                        radius = dotR * 3f,
                        center = Offset(x, y),
                    )
                    // Solid dot
                    drawCircle(
                        color = color,
                        radius = dotR,
                        center = Offset(x, y),
                    )
                }

                // Center hint dot (steady)
                drawCircle(
                    color = Color.White.copy(alpha = 0.7f),
                    radius = dotR * 0.4f,
                    center = Offset(cx, cy),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // 2. Rotating status text
        val text = statusMessages.getOrNull(statusKeyIndex) ?: statusMessages.firstOrNull().orEmpty()
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = statusMessages.elementAtOrNull(statusKeyIndex + 1) ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
