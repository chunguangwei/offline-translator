# 译人 阶段 3：问答 Tab（文字/语音/图片离线问答）实现计划

> 隶属设计：`docs/superpowers/specs/2026-06-09-yiren-restructure-design.md` 阶段 3。
> 验证门：`./gradlew :app:assembleDebug` 编译 + 真机冒烟。提交：真机验证前不入库。

**Goal:** 把现有 `chat` 模块（已支持文字 + 语音 ASR + Room 会话持久化）挂到「问答」Tab，并新增**图片上传 → Gemma 识别 + 问答**。

**Architecture:** 图片附件状态放在 `ChatViewModel.ChatUi.attachedBitmap`，`send(text)` 内部判断有无附图：有图走 vision prompt（`visionDescribe`）+ `engine.generateStream(includeImage=bmp)`，无图走原文本链路。图片仅作单轮上下文、**不写入 Room**（只存问答文字，避免 schema 迁移）。相册用系统 PhotoPicker（`PickVisualMedia`，免运行时权限）。解码复用新建的共享 `ImageUtils`（抽取自 vision 的 decode/resize）。

---

### Task 1: 共享图片解码工具

**Files:** Create `app/src/main/java/com/offlinetranslator/app/engine/image/ImageUtils.kt`

- [ ] Step 1: 创建（抽取自已验证的 VisionViewModel.decode/resizeForGemma）
```kotlin
package com.offlinetranslator.app.engine.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/** 把相册 Uri 解码为适合 Gemma 视觉输入的 Bitmap（最长边缩到 target px）。失败返回 null。 */
fun decodeBitmapForGemma(context: Context, uri: Uri, target: Int = 768): Bitmap? =
    runCatching {
        context.contentResolver.openInputStream(uri).use { input ->
            val original = BitmapFactory.decodeStream(input) ?: return null
            resizeForGemma(original, target)
        }
    }.getOrNull()

private fun resizeForGemma(src: Bitmap, target: Int): Bitmap {
    val w = src.width
    val h = src.height
    val scale = target.toFloat() / maxOf(w, h)
    if (scale >= 1f) return src
    val nw = (w * scale).toInt().coerceAtLeast(1)
    val nh = (h * scale).toInt().coerceAtLeast(1)
    val resized = Bitmap.createScaledBitmap(src, nw, nh, true)
    if (resized !== src) runCatching { src.recycle() }
    return resized
}
```
- [ ] Step 2: 编译 `:app:compileDebugKotlin` → SUCCESSFUL

---

### Task 2: ChatViewModel 加图片附件

**Files:** Modify `app/src/main/java/com/offlinetranslator/app/feature/chat/ChatViewModel.kt`

- [ ] Step 1: import
```kotlin
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```
- [ ] Step 2: `ChatUi` 加字段（在 `error` 前）
```kotlin
    /** 待发送的图片附件（单轮视觉问答用，不持久化）。 */
    val attachedBitmap: Bitmap? = null,
```
- [ ] Step 3: 构造函数注入 context
```kotlin
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: GemmaEngine,
    private val dao: ChatDao,
    private val recorder: PcmAudioRecorder,
) : ViewModel() {
```
- [ ] Step 4: 新增 attachImage / clearImage（放在 send 之前）
```kotlin
    fun attachImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = withContext(Dispatchers.IO) {
                com.offlinetranslator.app.engine.image.decodeBitmapForGemma(context, uri)
            }
            if (bmp == null) _ui.update { it.copy(error = "图片解码失败，请换一张") }
            else _ui.update { it.copy(attachedBitmap = bmp, error = null) }
        }
    }

    fun clearImage() { _ui.update { it.copy(attachedBitmap = null) } }
```
- [ ] Step 5: `send(text)` 支持图片。把方法体开头与生成段替换：现有
```kotlin
    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _ui.value.isGenerating) return
```
改为
```kotlin
    fun send(text: String) {
        val content = text.trim()
        val image = _ui.value.attachedBitmap
        if ((content.isEmpty() && image == null) || _ui.value.isGenerating) return
```
并把生成 prompt/stream 段（`val prompt = PromptTemplates.chat(historyBefore, content)` 到 `engine.generateStream(prompt).collect{...}`）替换为：
```kotlin
            val userTurn = if (content.isEmpty()) "🖼 [图片]" else content
            // 用户消息先入库（图片本身不持久化，仅存文字/标记）。
            // 注意：上面已用占位 content 入库的逻辑保持不变，但当 content 为空时
            // 需要存 userTurn —— 见下方说明。
            val prompt = if (image != null) PromptTemplates.visionDescribe(content)
                         else PromptTemplates.chat(historyBefore, content)
            val sb = StringBuilder()
            try {
                val stream = if (image != null)
                    engine.generateStream(prompt, includeImage = image)
                else
                    engine.generateStream(prompt)
                stream.collect { token ->
                    sb.append(token)
                    _ui.update { it.copy(streamingContent = sb.toString()) }
                }
```
- [ ] Step 6: 修正用户消息入库使其在纯图片时也有内容。把现有
```kotlin
            dao.insertMessage(
                ChatMessageEntity(
                    sessionId = sid, role = "user",
                    content = content, createdAt = System.currentTimeMillis(),
                )
            )
```
改为
```kotlin
            val userTurnContent = if (content.isEmpty() && _ui.value.attachedBitmap != null) "🖼 [图片]" else content
            dao.insertMessage(
                ChatMessageEntity(
                    sessionId = sid, role = "user",
                    content = userTurnContent, createdAt = System.currentTimeMillis(),
                )
            )
```
（删除 Step 5 中重复的 `userTurn` 行，仅保留此处 `userTurnContent`；prompt 仍用 `content` 作为问题文本。）
- [ ] Step 7: 生成成功后清掉附图。在 `_ui.update { it.copy(isGenerating = false, streamingContent = "") }`（成功分支）改为
```kotlin
                _ui.update { it.copy(isGenerating = false, streamingContent = "", attachedBitmap = null) }
```
- [ ] Step 8: 编译 → SUCCESSFUL

---

### Task 3: ChatScreen 图片按钮 + 预览

**Files:** Modify `app/src/main/java/com/offlinetranslator/app/feature/chat/ChatScreen.kt`

- [ ] Step 1: import
```kotlin
import androidx.compose.foundation.Image
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image as ImageIcon
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
```
- [ ] Step 2: 在 `micLauncher` 之后加相册 picker
```kotlin
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> vm.attachImage(uri) }
```
- [ ] Step 3: 在「Input bar」GlassCard 之前插入附图预览
```kotlin
        ui.attachedBitmap?.let { bmp ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = vm::clearImage) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.chat_image_remove))
                }
            }
        }
```
- [ ] Step 4: 在 Input bar 的 `Row{ BasicTextField ... }` 里，`BasicTextField` 之前插入图片按钮（仅在非录音/非生成时显示）
```kotlin
                if (!ui.isRecording && !ui.isGenerating && !ui.isTranscribing) {
                    IconButton(onClick = {
                        photoPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }) {
                        Icon(ImageIcon, contentDescription = stringResource(R.string.chat_attach_image),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
```
- [ ] Step 5: 让「有附图但无文字」也能发送。把发送按钮分支条件 `if (input.isBlank())`（mic）/ `else`（send）调整：当 `ui.attachedBitmap != null` 时即使 input 为空也显示发送按钮。即把
```kotlin
                } else {
                    if (input.isBlank()) {
```
改为
```kotlin
                } else {
                    if (input.isBlank() && ui.attachedBitmap == null) {
```
（send 按钮 onClick 不变：`vm.send(input); input = ""` —— 图片从 VM 状态读取。注意纯图片时 input 为空，send(text="") 仍会因 attachedBitmap 非空而执行。）
- [ ] Step 6: 编译 → SUCCESSFUL

---

### Task 4: 字符串 + 标题对齐「问答」

**Files:** `values/strings.xml` + `values-en/strings.xml`

- [ ] Step 1: 中文追加（chat_title 若为「对话」改为「问答」）
```xml
    <string name="chat_attach_image">添加图片</string>
    <string name="chat_image_remove">移除图片</string>
```
- [ ] Step 2: 英文追加
```xml
    <string name="chat_attach_image">Add image</string>
    <string name="chat_image_remove">Remove image</string>
```
- [ ] Step 3: 把 `chat_title` 改为「问答」/「Q&amp;A」（若现值不同）。
- [ ] Step 4: `:app:processDebugResources` → SUCCESSFUL

---

### Task 5: AppShell 挂真实问答

**Files:** Modify `AppShell.kt`

- [ ] Step 1: import `com.offlinetranslator.app.feature.chat.ChatScreen`
- [ ] Step 2: `composable(Route.Chat.path) { PlaceholderScreen(innerPadding) }` → `composable(Route.Chat.path) { ChatScreen(padding = innerPadding) }`
- [ ] Step 3: 编译 → SUCCESSFUL

---

### Task 6: 出包 + 冒烟

- [ ] `:app:assembleDebug` → `cp .../app-debug.apk ~/Desktop/译人-阶段3-debug.apk`
- [ ] 冒烟：问答 Tab 真实显示；文字问答；🎤 语音问答；📷 选图 → 预览 → 提问/直接发 → Gemma 识别作答；新会话；切 Tab 不崩。

## Self-Review
- 问答 Tab 真实化 ✅ Task5；文字/语音已有；图片 ✅ Task1-3；标题对齐 ✅ Task4。
- 图片不持久化（单轮），已在架构说明；纯图片可发送（Task2 Step5 + Task3 Step5 条件一致）。
- 复用：解码抽取 ImageUtils；vision 私有副本暂不动（vision 未挂载）。
- 命名一致：`attachImage`/`clearImage`/`attachedBitmap`/`decodeBitmapForGemma`。
- 历史 Tab 仍占位（阶段 4）。
