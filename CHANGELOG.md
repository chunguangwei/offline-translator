# 译人 — Changelog

所有版本变更记录在此，格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) + [SemVer](https://semver.org/)。

---

## [1.0.6] — 2026-06-11

### 修复
- **问答图片可点击全屏预览**（发送前附件缩略图与气泡内图片均可）
- **图片识别"看不到图"**：自动档的视觉/音频编码器改为 CPU 优先——部分设备 GPU 驱动上
  编码器初始化成功但推理输出异常，模型表现为反复索要图片；图片指令同步改为命令式
  （"图片已随消息附上，请直接回答"）
- **回复语言跟随应用语言**：中文环境使用中文系统提示词，默认简体中文回答
  （此前中文提问也常被英文回答）；摘要桥接、图片标注文案同步中英切换
- 视觉输入分辨率 768→896，密集文字（海报/文档）识别细节更足

---

## [1.0.5] — 2026-06-10

### 新增
- **底部 4-Tab 重构**：翻译 / 问答 / 历史 / 设置全部真实化，UI 贴合 logo 暖色调（珊瑚/琥珀/品红）
- **品牌启动页**：暖色渐变 + 眨眼 logo 角色动画 + 轮换功能标语；支持远程换图
  （改 `branding/splash/splash.json` 即可，**无需发版**；离线/拉取失败自动回落内置动画）
- **翻译**：语音输入（Gemma 离线转写回填输入框）、翻译历史（同句去重）、模型缺失顶部内联横幅
- **问答**：图片上传识别问答（图随消息进气泡）、会话管理（抽屉切换/删除）、
  上下文压缩（>20 条或会话满时摘要压缩，上下文含用户与 AI 双方全部消息）、
  图片追问自动重喂原图、暂停保留已生成部分
- **设置页「关于与许可」**：版本 / 版权 / 专有许可声明 / GitHub 链接 / 第三方致谢
- 模型下载国内（hf-mirror）/ 国外（huggingface）双源互为兜底

### 修复
- 输入法弹起时内容被顶进状态栏（adjustResize + IME insets）
- 问答流式回复尾部被输入框遮挡（贴底滚动）
- 暂停回答误报 "StandaloneCoroutine was cancelled"
- 删除会话不清理图片文件；录音器跨页冲突；底栏首帧闪烁
- launcher 图标 `evenodd` 资源链接错误

### 变更
- `LICENSE` 专有许可正式入库；README 许可声明与之一致（原误标 Apache 2.0）

---

## [1.0.4] — 2026-05-22

### 新增
- **应用内自动更新**：随 GitHub Release 更新
  - 冷启动静默检查最新版本，发现新版才弹窗（无更新时完全静默）
  - 设置页新增「应用更新」区块，可手动「检查更新」并查看当前版本
  - 一键下载签名 APK → 经 FileProvider 调起系统安装器覆盖安装（首次会引导开启「安装未知应用」权限）
- **GitHub Actions 发布流水线**：推送 `vX.Y.Z` 标签即自动用固定密钥签名构建 release APK 并发布到 GitHub Release，保证所有版本签名一致、可被自动更新覆盖安装

### 修复
- `.gitignore` 的 `models/` 通配误伤源码包 `feature/models/`，导致全新检出缺失文件而编译失败；改为 `/models/` 仅锚定仓库根的大模型目录

---

## [1.0.3] — 2026-05-14

### 修复
- **设置页"应用语言"切换无效**（切到 English 后 UI 保持中文）
  - **根因 1**：`MainActivity` 继承 `ComponentActivity`，而非 `AppCompatActivity`。`AppCompatDelegate.setApplicationLocales()` 在 API 32 及以下**必须通过 AppCompat 基类**才能生效；`ComponentActivity` 上调用静默失败。
  - **根因 2**：`AndroidManifest` `<activity>` 的 `android:configChanges` 包含 `locale|layoutDirection`，告诉系统"locale 变更时不要重建 Activity"。结果即使 locale 设置成功，Compose 树也不会重新加载 string resources，界面永远不刷新。
  - **根因 3**：缺少 `AppLocalesMetadataHolderService` 服务注册，AppCompat 无法将语言偏好持久化到自身存储，导致下次启动复位。
  - **修复方案（三处同步改）**：
    1. `MainActivity` 基类改为 `AppCompatActivity`（Hilt `@AndroidEntryPoint` 完全兼容，Compose `setContent` 无需改动）
    2. 从 `configChanges` 移除 `locale|layoutDirection`，保留 `orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode`
    3. 在 `AndroidManifest.xml` `<application>` 内注册服务：
       ```xml
       <service android:name="androidx.appcompat.app.AppLocalesMetadataHolderService"
                android:enabled="false" android:exported="false">
           <meta-data android:name="autoStoreLocales" android:value="true" />
       </service>
       ```
  - **效果**：切语言 → Activity 自动重建 → 全量 UI（Tab 标题 / 按钮 / 提示文案）立刻变为目标语言，重启后保持。

---

## [1.0.2] — 2026-05-14

### 新增
- `EngineState.MODEL_MISSING` — 区分"尚未下载模型"和真正的"引擎加载失败"两种状态
- `ModelMissingException` — 类型化异常，让各 ViewModel 能分支处理"无模型"场景

### 改进
- **首次安装体验（全面优化）**：
  - 设置页：引擎状态行新增 `MODEL_MISSING` 分支，显示"尚未下载模型，请到模型页下载"（蓝色提示，非红色报错）
  - 语音页：无模型时展示渐变麦克风图标 + 标题文案 + **"去下载模型"** CTA 按钮，点击跳转模型 Tab（`AppShell` 注入 `onNavigateToModels` lambda）；底部主按钮同步变为"去下载模型"而非禁用的麦克风
  - 对话 / 翻译 / 图像页：`ModelMissingException` 错误文案统一为"请先到「模型」页下载模型后重试"，不再暴露本地文件路径
- README GitHub 上传清单细化到具体子目录及功能说明
- 新增 CHANGELOG（本文件）

### 修复
- `EngineStatus` 构造参数名错误（`error` → `errorMessage`）

---

## [1.0.1] — 2026-05-14

### 移除
- **Vosk 第三方 ASR 完全下线**：
  - 删除 `engine/asr/VoskAsrEngine.kt` 及 `engine/asr/` 目录
  - 移除 `com.alphacephei:vosk-android:0.3.47` + `net.java.dev.jna:jna` 两个依赖
  - 删除 Vosk 国内镜像下载链路
  - APK 体积减小约 6 MB

### 新增
- **语音模块端到端 Gemma 化**：
  - 录音 → WAV 包装（自实现 44 字节 RIFF header，无额外依赖）→ Gemma audio encoder → 流式输出
  - 单次推理同时返回"原文：xxx\n译文：yyy"，正则增量解析 → 双卡片实时渲染
  - 收到第一帧 token 立即关闭等待动画，避免与流式文字重叠
  - `pcmToWav()` 内联工具函数（VoiceViewModel + ChatViewModel 各含一份）
- **语音处理等待动画**（`VoiceProcessingIndicator`）：
  - 三色（紫/粉/青）圆点绕中心 1.6s/圈轨道动画
  - 0.9s 呼吸缩放叠加
  - 四阶段文案自动轮换（每 1.5s）：识别语音 → 整理语义 → 翻译润色 → 即将完成
  - 纯 Compose Canvas 自绘，无 Lottie 依赖
- **对话页转录状态**：
  - `ChatUi` 新增 `isTranscribing` 字段
  - 停止录音 → 即刻切换到转录动画（不再静默等待）
  - 麦克风按钮三态：录音中 / 转录中（spinner，不可点）/ 正常

### 改进
- **波形行业级重写**（`WaveformBars.kt`）：
  - **包络跟随器**：attack=0.65（快速响应）+ release=0.12（缓慢衰减），消除 bar 跳变感
  - **静音呼吸基线**：无声时显示 5%~9% 高度的正弦滚动波纹（1.8s/圈），表现为"始终在听"
  - **60Hz 逐帧重绘**：`withFrameNanos` 确保包络衰减逐帧丝滑，不依赖 amplitude 更新频率
  - **视觉升级**：primary→tertiary 垂直渐变 + `StrokeCap.Round` + 高振幅径向 glow + 时间衰减（旧 bar 略低）
  - 镜像对称（上下各 50%），标准语音备忘录风格
- ProGuard 规则补全：LiteRT-LM / TFLite / MediaPipe / Compose / Coroutines / Room 全套 keep 规则
- Release 构建无密钥时自动 fallback 到 debug 签名，确保侧载可用
- Release 关闭 R8 minify（避免 LiteRT JNI 反射类被混淆破坏）
- `GemmaEngine.audioEnabled` 字段 + `isAudioEnabled()` 公开方法
- `GemmaEngine.generateStream` 新增 `includeAudioWav: ByteArray?` 参数
- 音频后端 init 失败时自动降级（GPU audio → CPU audio → 无 audio），不 crash
- `ModelRegistry` 两个模型均标记 `supportsAudio = true`
- versionCode 1 → 2，versionName 1.0.0 → 1.0.1

---

## [1.0.0] — 2026-05-13

### 初版功能

**翻译（Translate Tab）**
- 双卡片 + 渐变 swap 动画，中英互译，本地流式推理
- 输入历史本地缓存

**AI 对话（Chat Tab）**
- 完整多轮对话 UI + Markdown 渲染 + "思考中"动画
- KV-cache 加速多轮推理
- 长按消息复制 / 重新生成
- 麦克风输入（当时基于 Vosk 实时 ASR）

**语音（Voice Tab）**
- 录音波形 + 离线 ASR（Vosk）+ Gemma 翻译双模式

**图像（Vision Tab）**
- 相机 / 相册 → Gemma 多模态推理
- 四类快捷问法（描述 / 翻译 / 解题 / 识物）

**模型（Models Tab）**
- HF 直连 / HF 中国源 / 自定义 / 仅本地 四源切换
- HTTP Range 断点续传 + WorkManager 后台下载

**设置（Settings Tab）**
- 推理后端切换（Auto / GPU / CPU）+ 状态指示
- 主题：浅色 / 深色 / 跟随系统
- 应用语言切换（含 v1.0.3 修复前的未完全生效版本）

**基础能力**
- GMS-free，兼容华为 / 荣耀等无 Google 设备
- 完全离线推理，零云端上传
- 玻璃拟态（GlassCard）+ 极光背景（AuroraBackground）视觉风格
- 国内 Maven 镜像，构建无需代理
