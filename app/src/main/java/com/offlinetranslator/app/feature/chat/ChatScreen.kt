package com.offlinetranslator.app.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.db.ChatMessageEntity
import androidx.compose.ui.text.font.FontWeight
import com.offlinetranslator.app.core.designsystem.components.GlassCard
import com.offlinetranslator.app.core.designsystem.components.ThinkingDots
import com.offlinetranslator.app.core.designsystem.components.WaveformBars
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    padding: PaddingValues,
    vm: ChatViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(false) }
    // 全屏图片预览：Bitmap（发送前的附件）或 String 文件路径（气泡里的图）。
    var previewImage by remember { mutableStateOf<Any?>(null) }
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
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> vm.attachImage(uri) }

    // 自动「贴底」滚动：流式回复时新 token 在气泡底部，必须滚到列表绝对底部，
    // 否则长回复越往后生成的内容会落到输入框后面看不到（仅对齐气泡顶部时的老问题）。
    // 用一个超过任何气泡高度的大 offset，列表会被钳到内容末尾 = 滚到最底，
    // 最新一行始终停在输入框上方。streaming 项的存在条件要和下方列表保持一致
    // （isGenerating 也算，覆盖"思考中"无文本的瞬间），否则会差一格。
    LaunchedEffect(messages.size, ui.streamingContent, ui.isGenerating) {
        val hasStreaming = ui.streamingContent.isNotEmpty() || ui.isGenerating
        val count = messages.size + if (hasStreaming) 1 else 0
        if (count > 0) {
            listState.scrollToItem(count - 1, scrollOffset = 1_000_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            // 标准 IME 链：先消费 Scaffold 已经发的系统栏 insets，再补键盘高度，
            // 键盘弹起时输入条上浮、列表收缩，内容不会被顶进状态栏。
            .consumeWindowInsets(padding)
            .imePadding(),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSessions = true }) {
                    Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.chat_sessions))
                }
                IconButton(onClick = vm::startNewSession) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.chat_new_session))
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            // 底部留缓冲：最后一条消息与输入框之间有间距，不会贴死/被压住。
            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, onImageClick = { path -> previewImage = path })
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

        // Attached image preview (cleared after send).
        ui.attachedBitmap?.let { bmp ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { previewImage = bmp },
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = vm::clearImage) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.chat_image_remove))
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
                if (!ui.isRecording && !ui.isGenerating && !ui.isTranscribing) {
                    IconButton(onClick = {
                        photoPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }) {
                        Icon(
                            Icons.Rounded.AddPhotoAlternate,
                            contentDescription = stringResource(R.string.chat_attach_image),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
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
                        // happens after we stop. 转写结果回填输入框，让用户
                        // 确认/修改后再自己点发送（与翻译页一致），不直接发。
                        scope.launch {
                            val text = vm.stopVoiceInputAndTranscribe()
                            if (text.isNotBlank()) input = text
                        }
                    }) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.chat_voice_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    // 麦克风：空闲时始终显示（包括已附图时，可对着图说问题）。
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
                    // 有文字或有附图即可发送。
                    if (input.isNotBlank() || ui.attachedBitmap != null) {
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

    // 全屏图片预览（发送前附件 / 气泡图都走这里）。
    previewImage?.let { img ->
        FullScreenImageViewer(image = img, onDismiss = { previewImage = null })
    }

    // 会话抽屉：列出全部会话，点击切换、垃圾桶删除（当前会话高亮）。
    if (showSessions) {
        ModalBottomSheet(onDismissRequest = { showSessions = false }) {
            Text(
                text = stringResource(R.string.chat_sessions),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .padding(bottom = 24.dp),
                ) {
                    items(sessions, key = { it.id }) { s ->
                        val isCurrent = s.id == ui.sessionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.openSession(s.id)
                                    showSessions = false
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = s.title.ifBlank { stringResource(R.string.chat_session_untitled) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = sessionTimeFmt.format(Date(s.updatedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.deleteSession(s.id) }) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.chat_session_delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val sessionTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
private fun MessageBubble(msg: ChatMessageEntity, onImageClick: (String) -> Unit = {}) {
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
                // 用户气泡：有图先渲染图片，再渲染文字（文字可为空）。
                Column(horizontalAlignment = Alignment.End) {
                    msg.imageUri?.let { path ->
                        BubbleImage(path, onClick = { onImageClick(path) })
                        if (msg.content.isNotEmpty()) Spacer(Modifier.height(6.dp))
                    }
                    if (msg.content.isNotEmpty()) {
                        // User text is rendered as-is so literal asterisks stay visible.
                        Text(text = msg.content, color = fg, style = MaterialTheme.typography.bodyLarge)
                    }
                }
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

/** 从本地文件路径异步解码并显示对话气泡里的图片（点击全屏预览）。 */
@Composable
private fun BubbleImage(path: String, onClick: () -> Unit = {}) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }
    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = stringResource(R.string.chat_image_preview),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        )
    }
}

/**
 * 全屏图片预览：暗底 + 适配屏幕，点任意处关闭。
 * [image] 支持 Bitmap（发送前附件）或 String 文件路径（气泡图）。
 */
@Composable
private fun FullScreenImageViewer(image: Any, onDismiss: () -> Unit) {
    val bitmap: android.graphics.Bitmap? = when (image) {
        is android.graphics.Bitmap -> image
        is String -> produceState<android.graphics.Bitmap?>(initialValue = null, image) {
            value = withContext(Dispatchers.IO) {
                runCatching { android.graphics.BitmapFactory.decodeFile(image) }.getOrNull()
            }
        }.value
        else -> null
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.chat_image_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 12.dp),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
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
