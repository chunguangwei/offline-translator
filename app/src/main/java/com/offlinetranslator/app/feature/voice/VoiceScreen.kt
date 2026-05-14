package com.offlinetranslator.app.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.designsystem.components.SiriMicOrb
import com.offlinetranslator.app.core.designsystem.components.WaveformBars

/**
 * Voice translation screen — Doubao-inspired layout.
 *
 * Visual hierarchy (top → bottom):
 *   1. Title
 *   2. Direction chips (ZH→EN / EN→ZH)
 *   3. Two stacked translucent cards: 原文 (transcript) and 译文 (translation),
 *      separated by a divider so users see the relationship at a glance.
 *   4. Bottom hero: Siri-style gradient orb + waveform during recording.
 */
@Composable
fun VoiceScreen(
    padding: PaddingValues,
    onNavigateToModels: () -> Unit = {},
    vm: VoiceViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.setMode(VoiceMode.QuickTranslate)
        vm.prewarmAsrModel()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 1. Title — slightly larger, lighter weight (Doubao prefers
        // semi-bold rather than ExtraBold for headlines).
        Text(
            text = stringResource(R.string.voice_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            ),
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.voice_subtitle),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Spacer(Modifier.height(20.dp))

        // 2. Direction chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DirectionChip(
                label = stringResource(R.string.voice_dir_zh_en),
                selected = ui.direction == VoiceDirection.ZhToEn,
                enabled = !ui.isRecording && !ui.isLoadingModel,
                onClick = { vm.setDirection(VoiceDirection.ZhToEn) },
                modifier = Modifier.weight(1f),
            )
            DirectionChip(
                label = stringResource(R.string.voice_dir_en_zh),
                selected = ui.direction == VoiceDirection.EnToZh,
                enabled = !ui.isRecording && !ui.isLoadingModel,
                onClick = { vm.setDirection(VoiceDirection.EnToZh) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(20.dp))

        // 3. Result area: 原文 + 译文 in two stacked cards.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val empty = ui.transcript.isEmpty() && ui.translation.isEmpty() &&
                !ui.isRecording && !ui.isProcessing
            when {
                ui.needsModelDownload -> ModelMissingHint(onNavigateToModels)
                empty -> EmptyHint(hasPermission = hasPermission)
                ui.isProcessing && ui.transcript.isEmpty() && ui.translation.isEmpty() -> {
                    val msgs = stringArrayResource(R.array.voice_processing_phases)
                    com.offlinetranslator.app.core.designsystem.components.VoiceProcessingIndicator(
                        statusKeyIndex = ui.processingPhase.coerceAtMost(msgs.lastIndex),
                        statusMessages = msgs.toList(),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> {
                    ResultCard(
                        label = stringResource(R.string.voice_label_source),
                        content = ui.transcript.ifEmpty { "…" },
                        isSource = true,
                    )
                    ResultCard(
                        label = stringResource(R.string.voice_label_target),
                        content = ui.translation.ifEmpty { stringResource(R.string.voice_translating_hint) },
                        isSource = false,
                    )
                }
            }
        }

        // 4. Waveform — only while recording, positioned just above the orb.
        AnimatedVisibility(
            visible = ui.isRecording,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                WaveformBars(amplitudes = ui.amplitudes, modifier = Modifier.fillMaxWidth())
            }
        }

        // 5. Bottom hero — Siri orb mic.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !hasPermission -> {
                    Button(
                        onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                    ) {
                        Text(
                            stringResource(R.string.voice_grant_permission),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                ui.needsModelDownload -> {
                    Button(
                        onClick = onNavigateToModels,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                    ) {
                        Text(
                            stringResource(R.string.voice_go_download_model),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                ui.isLoadingModel -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.voice_loading_model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SiriMicOrb(
                            isRecording = ui.isRecording,
                            amplitudes = ui.amplitudes,
                            onTap = {
                                if (ui.isRecording) vm.stopRecording() else vm.startRecording()
                            },
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (ui.isRecording) stringResource(R.string.voice_tap_to_stop)
                            else stringResource(R.string.voice_tap_to_record_short),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ui.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 原文 / 译文 card. Doubao-style: wide rounded rectangle, soft inner color
 * tinted by source/target role. Source bg is neutral, target bg is primary-tinted.
 */
@Composable
private fun ResultCard(
    label: String,
    content: String,
    isSource: Boolean,
) {
    val bg = if (isSource) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    } else {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
            ),
        ).let { null } // sentinel so we can branch with a Color modifier below.
    }
    val labelColor = if (isSource)
        MaterialTheme.colorScheme.onSurfaceVariant
    else
        MaterialTheme.colorScheme.primary
    val contentColor = if (isSource)
        MaterialTheme.colorScheme.onSurface
    else
        MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .let { m ->
                if (isSource) {
                    m.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                } else {
                    m.background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                            ),
                        ),
                    )
                }
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 12.dp)
                        .clip(CircleShape)
                        .background(labelColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                    ),
                    color = labelColor,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp,
                    fontWeight = if (isSource) FontWeight.Normal else FontWeight.Medium,
                    lineHeight = 26.sp,
                ),
                color = contentColor,
            )
        }
    }
}

@Composable
private fun DirectionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
            }
        },
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun EmptyHint(hasPermission: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (hasPermission) stringResource(R.string.voice_tap_to_record)
            else stringResource(R.string.voice_no_permission),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Friendly empty-state when the model file isn't on disk yet (typical first
 * install). Bigger, illustrated hint + clear next-step text. The actual CTA
 * button is rendered at the bottom of the screen so it stays in thumb reach.
 */
@Composable
private fun ModelMissingHint(@Suppress("UNUSED_PARAMETER") onNavigate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Big rounded icon badge — same gradient as the brand orb so it feels
        // like part of the app rather than a generic empty state.
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.voice_model_missing_title),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.voice_model_missing_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
