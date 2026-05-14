package com.offlinetranslator.app.feature.chat

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.db.ChatMessageEntity
import com.offlinetranslator.app.core.designsystem.components.GlassCard
import com.offlinetranslator.app.core.designsystem.components.ThinkingDots
import com.offlinetranslator.app.core.designsystem.components.WaveformBars

@Composable
fun ChatScreen(
    padding: PaddingValues,
    vm: ChatViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val messages by vm.messages.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Mic permission
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var hasMic by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMic = granted
        if (granted) vm.startVoiceInput()
    }

    LaunchedEffect(messages.size, ui.streamingContent) {
        if (messages.isNotEmpty() || ui.streamingContent.isNotEmpty()) {
            listState.animateScrollToItem(
                index = (messages.size - 1).coerceAtLeast(0) +
                    if (ui.streamingContent.isNotEmpty()) 1 else 0
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.chat_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            IconButton(onClick = vm::startNewSession) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.chat_new_session))
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (ui.streamingContent.isNotEmpty() || ui.isGenerating) {
                item("streaming") {
                    AssistantStreamBubble(content = ui.streamingContent, isLoading = ui.isGenerating)
                }
            }
        }

        // Inline error banner
        ui.error?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Voice indicator: shown both during recording (waveform) and during
        // post-stop transcription (animated dots). Critical for UX because
        // Gemma audio inference can take 5–30s and previously the user saw
        // *nothing* between hitting Stop and the message being sent.
        if (ui.isRecording || ui.isTranscribing) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                cornerRadius = 16.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (ui.isTranscribing)
                                stringResource(R.string.chat_voice_transcribing)
                            else stringResource(R.string.chat_voice_listening),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        // Hide language chips while transcribing — they're irrelevant
                        // and just add visual noise during the wait.
                        if (!ui.isTranscribing) {
                            FilterChip(
                                selected = ui.voiceLang == ChatVoiceLang.ZH,
                                onClick = { vm.setVoiceLang(ChatVoiceLang.ZH) },
                                label = { Text(stringResource(R.string.lang_zh)) },
                                colors = FilterChipDefaults.filterChipColors(),
                            )
                            Spacer(Modifier.width(4.dp))
                            FilterChip(
                                selected = ui.voiceLang == ChatVoiceLang.EN,
                                onClick = { vm.setVoiceLang(ChatVoiceLang.EN) },
                                label = { Text(stringResource(R.string.lang_en)) },
                                colors = FilterChipDefaults.filterChipColors(),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (ui.isRecording) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                        ) { WaveformBars(amplitudes = ui.amplitudes) }
                    } else {
                        // Transcribing — orbiting dot animation + status text.
                        com.offlinetranslator.app.core.designsystem.components.VoiceProcessingIndicator(
                            statusKeyIndex = 0,
                            statusMessages = listOf(
                                stringResource(R.string.chat_voice_transcribing_phase1),
                                stringResource(R.string.chat_voice_transcribing_phase2),
                            ),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    if (ui.voicePartial.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = ui.voicePartial,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Input bar
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            cornerRadius = 28.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    stringResource(R.string.chat_input_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                // Mic / Send / Stop button cluster
                if (ui.isGenerating) {
                    IconButton(onClick = vm::stop) {
                        Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.chat_stop), tint = MaterialTheme.colorScheme.primary)
                    }
                } else if (ui.isTranscribing) {
                    // While Gemma is transcribing the captured audio, the mic
                    // button just shows a spinner — neither recording nor
                    // sending makes sense until we have the text back.
                    IconButton(onClick = {}, enabled = false) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else if (ui.isRecording) {
                    IconButton(onClick = {
                        // Gemma audio is one-shot per utterance; transcribing
                        // happens after we stop. Show the user the spinner via
                        // isGenerating already set by send(), and route the
                        // result through the normal send pipeline.
                        scope.launch {
                            val text = vm.stopVoiceInputAndTranscribe()
                            if (text.isNotBlank()) vm.send(text)
                        }
                    }) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.chat_voice_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    if (input.isBlank()) {
                        IconButton(onClick = {
                            if (hasMic) vm.startVoiceInput()
                            else micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }) {
                            Icon(
                                Icons.Rounded.Mic,
                                contentDescription = stringResource(R.string.chat_voice_start),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val text = input
                                input = ""
                                vm.send(text)
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.chat_send),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessageEntity) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val bg = if (isUser) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        val fg = if (isUser) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (isUser) {
                // User text is rendered as-is so literal asterisks stay visible.
                Text(text = msg.content, color = fg, style = MaterialTheme.typography.bodyLarge)
            } else {
                // Assistant historical messages get the same Markdown polish
                // as the streaming bubble — strips raw `**` / `*` / backticks
                // and renders bold / italics / lists / code blocks properly.
                com.offlinetranslator.app.core.designsystem.components.MarkdownText(
                    text = msg.content,
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun AssistantStreamBubble(content: String, isLoading: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                if (content.isEmpty() && isLoading) {
                    ThinkingDots()
                } else {
                    com.offlinetranslator.app.core.designsystem.components.MarkdownText(
                        text = content,
                    )
                }
            }
        }
    }
}
