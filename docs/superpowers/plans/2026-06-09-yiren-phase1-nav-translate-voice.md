# 译人 阶段 1：4-Tab 导航骨架 + 翻译页语音输入 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把单屏极简版改成底部 4-Tab 骨架（翻译/问答/历史/设置），并给翻译页加入「语音输入（离线 ASR）」这一输入方式。

**Architecture:** 用 Navigation-Compose 的底部 `NavigationBar` + `NavHost` 重建 4 个 top-level 目的地。本阶段只「翻译」是完整功能；问答/历史/设置先用占位屏，由后续阶段替换为真实界面。翻译页复用现有 `TranslateScreen`/`TranslateViewModel`，新增麦克风按钮：录音(`PcmAudioRecorder`,16k PCM)→ 封 WAV → 喂 `GemmaEngine.generateStream(includeAudioWav=)` 做**纯逐字转写**（不翻译）→ 转写文本回填输入框，用户确认后再走原有翻译流程。模型下载入口本阶段仍保留在翻译页右上角（阶段 2 迁入设置）。

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Navigation-Compose, Hilt, Kotlin Coroutines/Flow, LiteRT-LM (Gemma `.litertlm`，含音频编码器)。

**验证模型（重要）：** 本项目无单元测试基建（无 `app/src/test`、无 JUnit），且 Gemma 引擎无法在 JVM 单测中运行。因此每个任务的验证门是 **`./gradlew :app:compileDebugKotlin` / `:app:assembleDebug` 编译通过 + 真机冒烟**，不写 xUnit 测试。新增的纯函数（`WavUtils`）直接从已在生产中验证过的 `VoiceViewModel` 实现抽取，行为已知。

**提交策略：** 按用户要求，真机验证通过前不提交。以下各任务的「Commit」步骤可在整个阶段真机验收后统一执行（或按需分批）。环境构建需先 `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`。

---

### Task 1: 抽取共享 WAV 工具（`WavUtils.kt`）

把 `VoiceViewModel` 里私有的 `pcmToWav` / `peakPcm` 抽成顶层函数，供翻译页语音输入复用（DRY）。`VoiceViewModel` 的私有副本本阶段不动（其所属 voice 模块本阶段不挂载）。

**Files:**
- Create: `app/src/main/java/com/offlinetranslator/app/engine/audio/WavUtils.kt`

- [ ] **Step 1: 创建 WavUtils.kt**

```kotlin
package com.offlinetranslator.app.engine.audio

import java.io.ByteArrayOutputStream

/**
 * 把小端 PCM16 原始字节封装成最小 RIFF/WAVE 容器（44 字节头 + 数据）。
 * LiteRT-LM 的音频输入需要 WAV 头，而非裸 PCM。
 * 实现抽取自已在生产中验证过的 VoiceViewModel.pcmToWav。
 */
fun pcmToWav(
    pcm: ByteArray,
    sampleRate: Int = 16_000,
    channels: Int = 1,
    bitsPerSample: Int = 16,
): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val totalDataLen = pcm.size + 36
    val out = ByteArrayOutputStream(44 + pcm.size)
    fun w16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
    fun w32(v: Int) {
        out.write(v and 0xff); out.write((v shr 8) and 0xff)
        out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
    }
    out.write("RIFF".toByteArray()); w32(totalDataLen)
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); w32(16); w16(1); w16(channels)
    w32(sampleRate); w32(byteRate); w16(blockAlign); w16(bitsPerSample)
    out.write("data".toByteArray()); w32(pcm.size); out.write(pcm)
    return out.toByteArray()
}

/** 返回 PCM16 的峰值幅度（0f~1f），用于判断麦克是否采到声音。 */
fun peakPcm(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    var maxAbs = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
        val v = if (s and 0x8000 != 0) s or 0x7fff_0000.toInt().inv() else s
        val a = if (v < 0) -v else v
        if (a > maxAbs) maxAbs = a
        i += 2
    }
    return maxAbs / 32767f
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -Dkotlin.compiler.execution.strategy=in-process 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit（可延后）**

```bash
git add app/src/main/java/com/offlinetranslator/app/engine/audio/WavUtils.kt
git commit -m "refactor: 抽取共享 WAV 工具供翻译页语音输入复用"
```

---

### Task 2: ASR-only 转写 Prompt

给 `PromptTemplates` 加一个「只逐字转写、不翻译」的提示词（区别于 voice 模块的转写+翻译合一）。

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/engine/llm/PromptTemplates.kt`

- [ ] **Step 1: 新增 transcribeVerbatim**

在 `PromptTemplates` 对象内追加：

```kotlin
/**
 * 纯逐字转写（ASR），不翻译。用于翻译页的语音输入：把语音转成文字回填输入框，
 * 由用户确认后再走正常翻译流程。
 */
fun transcribeVerbatim(sourceIsZh: Boolean): String =
    if (sourceIsZh)
        "请把这段语音逐字转写为简体中文。只输出转写出的文字本身，" +
            "不要翻译、不要解释、不要加任何前后缀或标点说明。"
    else
        "Transcribe this speech verbatim in English. Output ONLY the transcribed " +
            "text itself — do not translate, explain, or add any prefix/suffix."
```

> 注：`PromptTemplates` 现有 `translate(text, sourceIsZh)`。保持同样的 `sourceIsZh: Boolean` 约定。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -Dkotlin.compiler.execution.strategy=in-process 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit（可延后）**

```bash
git add app/src/main/java/com/offlinetranslator/app/engine/llm/PromptTemplates.kt
git commit -m "feat: 新增纯逐字转写 prompt（翻译页语音输入用）"
```

---

### Task 3: TranslateViewModel 语音输入逻辑

注入 `PcmAudioRecorder`，新增录音→转写→回填输入框的逻辑。复用已有的 `loadingModel`（模型加载提示）。

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/translate/TranslateViewModel.kt`

- [ ] **Step 1: UI 状态加录音/转写标志**

在 `TranslateUi` 内 `loadingModel` 之后追加两个字段：

```kotlin
    /** True while microphone is capturing audio. */
    val isRecording: Boolean = false,
    /** True while Gemma is transcribing the recorded audio into text. */
    val isTranscribing: Boolean = false,
```

- [ ] **Step 2: 构造函数注入 recorder**

把构造函数改为：

```kotlin
@HiltViewModel
class TranslateViewModel @Inject constructor(
    private val engine: GemmaEngine,
    private val recorder: com.offlinetranslator.app.engine.audio.PcmAudioRecorder,
) : ViewModel() {
```

- [ ] **Step 3: 新增 startVoiceInput / stopVoiceInput**

在 `cancel()`/`clear()` 附近追加（`recordJob` 字段加在 `private var job: Job? = null` 旁）：

```kotlin
    private var recordJob: Job? = null
    private var recordStartedAt = 0L

    fun startVoiceInput() {
        val cur = _ui.value
        if (cur.isRecording || cur.isTranscribing || cur.isTranslating) return
        _ui.update { it.copy(isRecording = true, error = null) }
        recordStartedAt = System.currentTimeMillis()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                recorder.start()
            } catch (t: Throwable) {
                _ui.update { it.copy(isRecording = false, error = t.message) }
            }
        }
    }

    fun stopVoiceInput() {
        val cur = _ui.value
        if (!cur.isRecording) return
        _ui.update { it.copy(isRecording = false) }
        recordJob?.cancel()
        recordJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val pcm = recorder.stop()
            val durationMs = System.currentTimeMillis() - recordStartedAt
            if (durationMs < 500) {
                _ui.update { it.copy(error = "录音太短，请按住多说一会儿") }
                return@launch
            }
            if (com.offlinetranslator.app.engine.audio.peakPcm(pcm) < 0.01f) {
                _ui.update { it.copy(error = "没采到声音，请检查麦克风权限") }
                return@launch
            }
            _ui.update { it.copy(isTranscribing = true, loadingModel = true) }
            engine.ensureLoaded().onFailure { e ->
                val msg = if (e is com.offlinetranslator.app.engine.llm.ModelMissingException)
                    "请先到“模型”页下载模型后重试" else e.message
                _ui.update { it.copy(isTranscribing = false, loadingModel = false, error = msg) }
                return@launch
            }
            _ui.update { it.copy(loadingModel = false) }
            if (!engine.isAudioEnabled()) {
                _ui.update { it.copy(isTranscribing = false, error = "当前模型不支持语音输入") }
                return@launch
            }
            val wav = com.offlinetranslator.app.engine.audio.pcmToWav(pcm)
            val prompt = PromptTemplates.transcribeVerbatim(cur.source == TranslateLang.ZH)
            val accum = StringBuilder()
            try {
                engine.generateStream(prompt = prompt, includeAudioWav = wav).collect { token ->
                    accum.append(token)
                    // 转写结果实时回填输入框，转写中状态在首个 token 后关闭。
                    _ui.update { it.copy(input = accum.toString().trim(), isTranscribing = false) }
                }
                _ui.update { it.copy(isTranscribing = false) }
            } catch (t: Throwable) {
                _ui.update { it.copy(isTranscribing = false, error = t.message) }
            }
        }
    }
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -Dkotlin.compiler.execution.strategy=in-process 2>&1 | tail -8`
Expected: `BUILD SUCCESSFUL`（若报 `Dispatchers`/`Job` 已 import 重复，删冗余 import 即可）

- [ ] **Step 5: Commit（可延后）**

```bash
git add app/src/main/java/com/offlinetranslator/app/feature/translate/TranslateViewModel.kt
git commit -m "feat: 翻译页语音输入（录音→离线转写→回填输入框）"
```

---

### Task 4: 字符串资源（麦克风 + 历史/问答 Tab）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: 中文 strings.xml**

在 `translate_loading_model` 一行后追加：

```xml
    <string name="translate_voice">语音输入</string>
    <string name="translate_recording">正在录音，再次点击结束</string>
    <string name="translate_transcribing">正在识别语音…</string>
    <string name="nav_history">历史</string>
    <string name="nav_qa">问答</string>
    <string name="placeholder_coming_soon">功能开发中，敬请期待</string>
```

- [ ] **Step 2: 英文 values-en/strings.xml**

在 `translate_loading_model`（英文）一行后追加：

```xml
    <string name="translate_voice">Voice input</string>
    <string name="translate_recording">Recording… tap again to stop</string>
    <string name="translate_transcribing">Transcribing…</string>
    <string name="nav_history">History</string>
    <string name="nav_qa">Q&amp;A</string>
    <string name="placeholder_coming_soon">Coming soon</string>
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:processDebugResources -Dkotlin.compiler.execution.strategy=in-process 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit（可延后）**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml
git commit -m "chore: 新增语音输入与历史/问答 Tab 字符串"
```

---

### Task 5: 翻译页麦克风按钮 + 录音权限

在 `SourceCard` 底部操作行（粘贴/清空旁）加 🎤 按钮，处理 `RECORD_AUDIO` 运行时权限，并在录音/识别时给视觉反馈。`RECORD_AUDIO` 已在 AndroidManifest 声明。

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/translate/TranslateScreen.kt`

- [ ] **Step 1: 新增 import**

在文件 import 区追加：

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
```

- [ ] **Step 2: 在 TranslateScreen 顶部建权限 launcher，并把回调透传给 SourceCard**

在 `TranslateScreen` 的 `Box(...) {` 之前、`LaunchedEffect(ui.error)` 附近追加：

```kotlin
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startVoiceInput() }

    fun onMicClick() {
        when {
            ui.isRecording -> vm.stopVoiceInput()
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> vm.startVoiceInput()
            else -> micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
```

- [ ] **Step 3: 把麦克风状态/回调传进 SourceCard 调用**

把现有 `SourceCard(...)` 调用改为追加三个参数：

```kotlin
                    SourceCard(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        lang = ui.source,
                        text = ui.input,
                        onTextChange = vm::setInput,
                        isRecording = ui.isRecording,
                        isTranscribing = ui.isTranscribing,
                        onMic = { onMicClick() },
                        onPaste = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                            if (t.isNotEmpty()) vm.setInput(t)
                        },
                        onClear = vm::clear,
                    )
```

- [ ] **Step 4: SourceCard 签名 + 底部行加麦克风按钮**

把 `private fun SourceCard(` 签名改为：

```kotlin
private fun SourceCard(
    modifier: Modifier,
    lang: TranslateLang,
    text: String,
    onTextChange: (String) -> Unit,
    isRecording: Boolean,
    isTranscribing: Boolean,
    onMic: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
```

在 SourceCard 底部 `Row(...horizontalArrangement = Arrangement.End...)` 内、`IconButton(onClick = onPaste)` **之前**插入麦克风按钮：

```kotlin
                IconButton(onClick = onMic) {
                    Icon(
                        imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = stringResource(R.string.translate_voice),
                        tint = if (isRecording) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
```

并在 SourceCard 的 `CardHeader(...)` 之后、输入框 `Box` 之前插入一行状态提示（录音/识别时显示）：

```kotlin
            if (isRecording || isTranscribing) {
                Text(
                    text = stringResource(
                        if (isRecording) R.string.translate_recording
                        else R.string.translate_transcribing
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -Dkotlin.compiler.execution.strategy=in-process 2>&1 | tail -8`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit（可延后）**

```bash
git add app/src/main/java/com/offlinetranslator/app/feature/translate/TranslateScreen.kt
git commit -m "feat: 翻译页麦克风按钮 + 录音权限 + 录音/识别状态提示"
```

---

### Task 6: 4-Tab 导航骨架

`Routes.kt` 加 `Route.History` 并把 `topLevelDestinations` 改为 4 个 Tab；新建占位屏；重写 `AppShell` 为底部 `NavigationBar` + `NavHost`（翻译真实+其余占位，Models 作为非 Tab 路由仍由翻译页右上角进入）。

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/shell/Routes.kt`
- Create: `app/src/main/java/com/offlinetranslator/app/feature/shell/PlaceholderScreen.kt`
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/shell/AppShell.kt`

- [ ] **Step 1: Routes.kt — 加 History 路由 + 改 4 Tab**

在 `sealed class Route` 内 `Models` 之后加：

```kotlin
    data object History : Route("history")
```

import 区加历史图标：

```kotlin
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.rounded.History
```

把 `topLevelDestinations` 整体替换为 4 个：

```kotlin
val topLevelDestinations = listOf(
    TopLevelDestination(Route.Translate, R.string.nav_translate, Icons.Rounded.SwapHoriz, Icons.Outlined.SwapHoriz),
    TopLevelDestination(Route.Chat, R.string.nav_qa, Icons.Rounded.Forum, Icons.Outlined.Forum),
    TopLevelDestination(Route.History, R.string.nav_history, Icons.Rounded.History, Icons.Outlined.History),
    TopLevelDestination(Route.Settings, R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
)
```

> 其余未用到的图标 import（GraphicEq/Image/Widgets 等）保留无妨；如编译警告未使用，可删。

- [ ] **Step 2: 新建 PlaceholderScreen.kt**

```kotlin
package com.offlinetranslator.app.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.offlinetranslator.app.R

/** 占位屏：问答/历史等尚未在本阶段落地的 Tab 先用它，后续阶段替换为真实界面。 */
@Composable
fun PlaceholderScreen(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.placeholder_coming_soon),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 3: 重写 AppShell.kt 为底部 4-Tab + NavHost**

```kotlin
package com.offlinetranslator.app.feature.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.offlinetranslator.app.feature.models.ModelsScreen
import com.offlinetranslator.app.feature.translate.TranslateScreen

/**
 * 底部 4-Tab 骨架：翻译 / 问答 / 历史 / 设置。
 * 本阶段只「翻译」是完整功能；问答/历史/设置先用 PlaceholderScreen，
 * 由后续阶段替换为真实界面。模型下载页(ModelsScreen)作为非 Tab 路由，
 * 仍由翻译页右上角进入。
 */
@Composable
fun AppShell() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // Models 等非 Tab 路由进入时隐藏底栏。
            if (topLevelDestinations.any { it.route.path == currentRoute }) {
                NavigationBar {
                    topLevelDestinations.forEach { dest ->
                        val selected = currentRoute == dest.route.path
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(dest.route.path) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = stringResource(dest.labelRes),
                                )
                            },
                            label = {
                                Text(stringResource(dest.labelRes), style = MaterialTheme.typography.labelSmall)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = Route.Translate.path,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Route.Translate.path) {
                TranslateScreen(
                    padding = innerPadding,
                    onOpenModels = {
                        nav.navigate(Route.Models.path) { launchSingleTop = true }
                    },
                )
            }
            composable(Route.Chat.path) { PlaceholderScreen(innerPadding) }
            composable(Route.History.path) { PlaceholderScreen(innerPadding) }
            composable(Route.Settings.path) { PlaceholderScreen(innerPadding) }
            composable(Route.Models.path) { ModelsScreen(padding = innerPadding) }
        }
    }
}

/** Helper composable used when a screen wants to consume scaffold padding. */
@Composable
fun Modifier.consumeScaffoldPadding(padding: PaddingValues): Modifier =
    this.padding(padding)
```

- [ ] **Step 4: 整体编译**

Run: `./gradlew :app:compileDebugKotlin -Dkotlin.compiler.execution.strategy=in-process 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit（可延后）**

```bash
git add app/src/main/java/com/offlinetranslator/app/feature/shell/
git commit -m "feat: 4-Tab 导航骨架（翻译/问答/历史/设置），其余 Tab 占位"
```

---

### Task 7: 出包 + 真机冒烟验收

- [ ] **Step 1: 打 debug APK 到桌面**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug -Dkotlin.compiler.execution.strategy=in-process 2>&1 | tail -5
cp app/build/outputs/apk/debug/app-debug.apk ~/Desktop/译人-阶段1-debug.apk
```
Expected: `BUILD SUCCESSFUL` + 桌面出现 APK

- [ ] **Step 2: 真机冒烟清单（用户执行）**
  1. 装包打开 → 底部出现 4 个 Tab：翻译 / 问答 / 历史 / 设置；切到问答/历史/设置显示「功能开发中」。
  2. 翻译页右上角「模型」可进入下载页（未下模型时）。
  3. 下好 E2B 模型后，文本「你好世界」→ 出「Hello world」。
  4. 点麦克风 → 授权 → 录一句中文 → 输入框自动回填转写文字 → 点翻译出译文。
  5. 全程配色为暖色（珊瑚/品红），无冷色出戏。

---

## Self-Review（对照 spec 阶段 1 范围）

- 4-Tab 导航骨架 ✅ Task 6（翻译真实 + 其余占位 + Models 非 Tab 路由）。
- 翻译页保留 GT 式干净输入 + 语音输入 ✅ Task 1/2/3/5（录音→离线转写→回填）。
- 语音=输入方式而非独立页 ✅（纯转写回填，不自动翻译、不做字幕）。
- 暖色品牌主题 ✅ 已在前序工作落地，本阶段沿用。
- 模型缺失/加载提示/下载兜底 ✅ 复用现有逻辑。
- 占位符扫描：无 TBD/TODO，代码均为完整可粘贴实现。
- 类型一致：`startVoiceInput`/`stopVoiceInput`、`isRecording`/`isTranscribing`、`pcmToWav`/`peakPcm`、`transcribeVerbatim(sourceIsZh)` 在各任务间命名一致。
- 阶段 2-4（设置/问答+图片/历史）不在本计划内，各自单独出计划。
