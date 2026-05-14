package com.offlinetranslator.app.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Voice-reactive waveform — industry-grade rendering.
 *
 * Features beyond a naive amplitude-bar display (Siri / iOS Voice Memos /
 * Doubao / WeChat-voice all do these):
 *
 * 1. **Mic-reactive**: every new amplitude from [PcmAudioRecorder] feeds a
 *    rolling history, but the UI doesn't render it directly — instead each
 *    bar runs an *envelope follower* (attack + slow decay), so spikes pop
 *    instantly while quiet patches fade gracefully.
 *
 * 2. **Idle breathing**: when the mic hasn't produced audio yet, OR the user
 *    is between phrases, the bars don't go flat (which feels broken). They
 *    fall back to a soft sine-wave baseline so users always *see* the app
 *    listening.
 *
 * 3. **Mirrored**: bars draw symmetrically up + down from the center line —
 *    the standard "voice memo" look.
 *
 * 4. **Gradient + glow**: each bar is painted with a primary→tertiary
 *    vertical gradient so it reads as part of the brand, not a plain stroke.
 *
 * The end effect: it's clearly responsive when you talk, but never looks
 * dead when you pause for a beat.
 */
@Composable
fun WaveformBars(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barCount: Int = 36,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    // Smoothed amplitude per bar — these are the values we actually paint.
    val smoothed = remember(barCount) { FloatArray(barCount) }

    // Phase used for the idle baseline sine wave. Drives a slow rolling
    // shimmer when there is no real input.
    val infinite = rememberInfiniteTransition(label = "waveform-idle")
    val idlePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2f).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // Recompute on each frame so the envelope decays smoothly even when
    // amplitudes update at a lower rate (~50/s) than the screen refresh
    // (60–120/s).
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                ticker = now
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f
        val gap = 4f
        val barWidth = max(1f, (w - gap * (barCount - 1)) / barCount)

        // 1. Pull a windowed slice of the most recent amplitudes.
        val window: List<Float> = if (amplitudes.size >= barCount) {
            amplitudes.takeLast(barCount)
        } else {
            List(barCount - amplitudes.size) { 0f } + amplitudes
        }

        // 2. Detect "silent" — when the recent peak is too low we'll draw the
        // idle baseline animation on top of the (near-flat) signal.
        val recentPeak = window.takeLast(8).maxOrNull() ?: 0f
        val silenceMix = (1f - (recentPeak / 0.08f).coerceIn(0f, 1f))

        // 3. Per-bar envelope follower:
        //   target = current input
        //   if target > smoothed: smoothed jumps up fast (attack)
        //   else                : smoothed decays slowly (release)
        val attack = 0.65f
        val release = 0.12f
        for (i in 0 until barCount) {
            val target = window[i].coerceIn(0f, 1f)
            val cur = smoothed[i]
            smoothed[i] = if (target > cur) {
                cur + (target - cur) * attack
            } else {
                cur + (target - cur) * release
            }
        }

        // 4. Paint bars: each bar's height = max(idleBaseline, envelope).
        val gradient = Brush.verticalGradient(
            colors = listOf(primary, tertiary),
            startY = 0f,
            endY = h,
        )

        for (i in 0 until barCount) {
            // Idle baseline: a soft sine-wave that rolls across the bars,
            // peaks ~6% bar height — visible but never distracting.
            val phase = idlePhase + i * 0.32f
            val idle = 0.05f + 0.04f * (sin(phase.toDouble()).toFloat() + 1f) / 2f
            // Older bars (further left) decay slightly so the wave reads
            // "newest at right".
            val ageDecay = 0.55f + 0.45f * (i.toFloat() / (barCount - 1))

            val live = smoothed[i] * ageDecay
            // Mix the live envelope and idle baseline by silence ratio:
            //   loud  → silenceMix=0 → all live
            //   quiet → silenceMix=1 → both visible (idle floor)
            val amp = max(live, idle * silenceMix.coerceAtLeast(0.5f))
                .coerceIn(0f, 1f)

            val barHeight = (h * 0.9f * amp).coerceAtLeast(barWidth)
            val x = i * (barWidth + gap)
            val cx = x + barWidth / 2f

            // Soft glow halo behind each tall bar — reads as a "lit" bar.
            if (live > 0.25f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primary.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(cx, centerY),
                        radius = barHeight * 0.85f,
                    ),
                    radius = barHeight * 0.85f,
                    center = Offset(cx, centerY),
                )
            }

            drawLine(
                brush = gradient,
                start = Offset(cx, centerY - barHeight / 2f),
                end = Offset(cx, centerY + barHeight / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
        // Touch ticker so Compose recomposes on every frame.
        @Suppress("UNUSED_EXPRESSION") ticker
    }
}
