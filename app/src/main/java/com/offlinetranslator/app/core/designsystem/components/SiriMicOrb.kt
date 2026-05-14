package com.offlinetranslator.app.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.sin

/**
 * Siri-style gradient orb mic button.
 *
 * Visual goal: a "soft, premium AI voice" surface — three colored radial
 * gradients overlap inside a circle to create the iridescent look that Siri /
 * Doubao / ChatGPT voice modes use.
 *
 * Behavior:
 *  - At rest: subtle slow rotation of the gradient anchors, low opacity glow.
 *  - Recording: stronger glow, faster anchor rotation, pulse scale tied to a
 *    rolling-average of incoming PCM amplitudes (so the orb "breathes" with
 *    the user's voice).
 *  - Loading: spinner overlay (handled by parent), orb stays at rest.
 */
@Composable
fun SiriMicOrb(
    isRecording: Boolean,
    amplitudes: List<Float>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    enabled: Boolean = true,
) {
    val infinite = rememberInfiniteTransition(label = "siri-orb")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRecording) 4_000 else 8_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRecording) 1_400 else 3_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // Voice-driven scale (only meaningful when recording). Use last few
    // amplitudes for smoothing — single-frame jitter is ugly.
    val voiceLevel = if (isRecording && amplitudes.isNotEmpty()) {
        amplitudes.takeLast(6).average().toFloat().coerceIn(0f, 1f)
    } else 0f
    val targetScale = if (isRecording) 1f + voiceLevel * 0.12f else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(120),
        label = "scale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isRecording) 0.55f else 0.22f,
        animationSpec = tween(400),
        label = "glow",
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pointerInput(enabled) {
                if (enabled) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val release = event.changes.firstOrNull()?.changedToUp() == true
                            if (release) onTap()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val r = max(w, h) / 2f
            val ang = rotation * 2f * Math.PI.toFloat()

            // Outer halo — soft glow that extends beyond the orb edge.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF7C5CFF).copy(alpha = glowAlpha),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = r * 1.45f,
                ),
                radius = r * 1.45f,
                center = Offset(cx, cy),
            )

            // Three overlapping orbs of color, anchors orbit the center to
            // create the iridescent "Siri sphere" look.
            val anchors = listOf(
                Triple(Color(0xFF8E5CFF), 0.0, 0.42f),  // violet
                Triple(Color(0xFFFF5FA0), 0.66, 0.38f), // pink
                Triple(Color(0xFF3DD9FF), 1.33, 0.40f), // cyan
            )
            anchors.forEach { (color, offset, radiusFactor) ->
                val a = ang + offset.toFloat() * Math.PI.toFloat()
                val ax = cx + r * 0.30f * kotlin.math.cos(a.toDouble()).toFloat()
                val ay = cy + r * 0.30f * kotlin.math.sin(a.toDouble()).toFloat()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0f)),
                        center = Offset(ax, ay),
                        radius = r * radiusFactor * scale,
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )
            }

            // Specular highlight (top-left), gives it the glossy look.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(cx - r * 0.35f, cy - r * 0.40f),
                    radius = r * 0.55f,
                ),
                radius = r,
                center = Offset(cx, cy),
            )

            // Subtle voice-reactive ring during recording.
            if (isRecording) {
                val pulse = 0.5f + 0.5f * sin(phase.toDouble()).toFloat()
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f * (0.5f + 0.5f * voiceLevel + 0.2f * pulse)),
                    radius = r * (1.0f + 0.05f * pulse),
                    center = Offset(cx, cy),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                )
            }
        }

        Icon(
            imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.34f),
        )
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.changedToUp(): Boolean =
    !pressed && previousPressed
