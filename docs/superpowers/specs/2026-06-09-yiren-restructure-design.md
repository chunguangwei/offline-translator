# 译人 · 多功能重构设计（v1.1）

日期：2026-06-09
状态：待用户确认

## 1. 目标与背景

把当前"只有文本翻译单屏"的极简版，重构成一个**离线 AI 翻译 + 问答 App**，底部 4-Tab 导航。**整体 UI 贴合 logo 暖色品牌调性**（珊瑚→琥珀→品红暖渐变，简洁、专业、有自有风格，不照搬 Google 或其它产品）——参考图仅用于说明"翻译页干净输入"这一结构点，视觉风格以品牌为准。

核心约束：
- **全离线**：翻译、语音 ASR、图像识别、问答全部由本地 Gemma（`.litertlm`，E2B/E4B）完成，无第三方云服务、无额外 ASR 模型。
- **最大化复用**：chat / voice / vision / settings 模块代码已存在且能编译，引擎 `GemmaEngine` 已支持文本 + 图像(`Content.ImageBytes`) + 音频。本次主要是**重新组织 + 接线**，真正新建的只有"翻译历史"。

非目标（本次不做）：实时字幕翻译、双人对话翻译、通用识图作为独立翻译入口、生词本、云同步。

## 2. 已确认的产品决策

- **导航**：底部 4-Tab（翻译 / 问答 / 历史 / 设置）。
- **语音不是独立功能**：语音 = 翻译的一种输入方式（手动 / 粘贴 / 语音 ASR），并入翻译页；问答页也支持语音输入。不做实时字幕。
- **图片归问答**：图片主价值是识别而非翻译，放进问答模块（上传图 → Gemma 识别 + 问答），不做独立"拍照翻译"。
- **历史只存翻译记录**：问答会话另由问答页自己的会话列表承载。

## 3. 架构

### 3.1 导航骨架（`feature/shell`）
重建底部 4-Tab。复用项目原有 `Routes.kt`（`Route.Translate/Chat/...`）+ Navigation-Compose 的 `NavHost`（此前被砍成单屏，现恢复为 4 个 top-level destination）。Tab：翻译(Translate) / 问答(Chat) / 历史(History·新建) / 设置(Settings)。`MainActivity` 已套 `OfflineTranslatorTheme`，无需改。

### 3.2 翻译 Tab（主页，品牌暖色调）— 复用 + 接线
基于现有 `feature/translate/TranslateScreen` + `TranslateViewModel`。
- 语言栏 中文 ⇄ 英语；输入卡（手动输入 / **粘贴** / **🎤 语音**）；翻译按钮 → 流式译文 + 复制。
- **语音输入**：借 `voice/VoiceViewModel` 的 ASR（`prewarmAsrModel()` / `startRecording()` / `stopRecording()`，底层是 Gemma 音频编码器转写，纯离线）。录音 → 转写文本 → 填入输入框，用户可再编辑后翻译。需要 `RECORD_AUDIO` 运行时权限（仅点麦克风时申请）。
- 翻译成功后写入历史表（见 3.4）。
- 沿用已有的"模型缺失 → 引导下载"逻辑；模型下载入口移到设置 Tab（设置已含模型管理），翻译页右上角的临时模型入口可移除。

### 3.3 问答 Tab（离线 AI 问答）— 复用 chat + 加图片
基于现有 `feature/chat`（`ChatScreen` / `ChatViewModel` / `ChatDao`）。该模块**已支持文字 + 语音 ASR，且已用 Room 持久化会话**（`ChatSessionEntity` / `ChatMessageEntity`）。
- **新增图片输入**：相册选图（先做相册；相机拍照为可选后续）→ 读为 bytes → 随 prompt 一起发给 Gemma（引擎已支持 `Content.ImageBytes`）→ 识别 + 问答。借 `vision/VisionViewModel` 的图像编码/发送逻辑。
- 会话列表 / 多轮上下文沿用 chat 现有实现。

### 3.4 历史 Tab（唯一新建）
- 数据：在**现有 Room 数据库**中新增 `TranslationEntity`（id、原文、译文、源语言、目标语言、时间戳）+ `TranslationDao`（insert / observeAll / delete / clear）。复用 chat 已有的 DB 实例与 Hilt 装配方式。
- UI：`feature/history/HistoryScreen` + `HistoryViewModel`，倒序列表，每条可复制译文 / 删除；顶部"清空"。（不做点击回填到翻译页。）
- 写入点：翻译成功时由翻译链路调用 `TranslationDao.insert`。

### 3.5 设置 Tab — 复用
基于现有 `feature/settings/SettingsScreen`（主题 系统/浅/深、界面语言、模型源、模型管理/下载）。直接挂入 Tab。

### 3.6 主题与设计语言（横切要求）
- **配色** ✅ 已完成：`designsystem/theme/Color.kt` 的 `Brand*` 已从冷色(蓝/紫/薄荷)改为 logo 暖色渐变（珊瑚 `#FF6F61` 主色 / 琥珀 `#FFB152` / 品红 `#C2479B`）。各界面经 `MaterialTheme.colorScheme` 全局生效。
- **设计语言（贯穿所有 Tab）**：所有界面统一暖色品牌调性 —— 主按钮/强调色用珊瑚→品红暖渐变，圆角卡片，留白克制，**不照搬 Google 或任何外部产品的视觉**。每新增/改动一个界面都要对齐这套语言，避免出现冷色或默认 Material 紫等"出戏"配色。

## 4. 数据流

- **翻译**：输入(键盘/粘贴/ASR) → `TranslateViewModel.translate()` → `GemmaEngine.ensureLoaded()` → `generateStream()` 流式 → UI；成功 → `TranslationDao.insert`。
- **语音输入**：麦克风 → 录音 → Gemma 转写 → 文本回填输入框（不自动翻译，留给用户确认）。
- **问答**：文本/语音/图片 → `ChatViewModel` → Gemma（多模态）→ 流式回答；Room 存会话。
- **历史**：Room `TranslationDao.observeAll()` → `HistoryScreen`。

## 5. 错误处理

- 模型缺失：统一走 `ModelMissingException` → 提示"请先到设置下载模型"。
- 模型加载慢：复用已加的 `loadingModel` → "正在加载模型，请稍等…"。
- 录音权限被拒：提示需授权麦克风。
- 图片过大 / 解码失败：提示重选。
- 下载：国内(hf-mirror)优先、国外(huggingface.co)兜底（已实现）。

## 6. 分阶段交付（每阶段出可装 debug APK）

1. **阶段 1：导航骨架 + 翻译页（含语音输入）** — 地基 + 最高价值。
2. **阶段 2：设置 Tab** — 复用，成本低。
3. **阶段 3：问答 Tab（文字 / 语音 / 图片）** — 复用 chat + 接图片。
4. **阶段 4：历史 Tab** — 新建 Room 表 + 列表。

两端对齐：iOS 端按相同形态后续跟进（本设计先落 Android）。

## 7. 验证

- 每阶段：`./gradlew :app:assembleDebug` 绿 + 真机冒烟（我能编译，真机翻译/识别由用户验收）。
- 关键链路真机验收：中英互译、语音转写填入、问答多轮、图片识别问答、历史读写、深色/语言切换。
