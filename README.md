# 译人 · Yiren

> 完全离线 / 端侧推理的 AI 助手 Android 应用 — 集合 **翻译 / AI 对话 / 语音转写 / 拍照看图 / 模型管理** 五大能力，零依赖 Google 服务（GMS-free），适配华为 / 荣耀等无 GMS 设备。

**当前版本：1.0.4**　|　最低系统：Android 8.0（API 26）　|　支持 arm64-v8a / armeabi-v7a

---

## 目录

- [✨ 功能详解](#-功能详解)
- [🧱 技术栈](#-技术栈)
- [🛠️ 构建与运行](#️-构建与运行)
- [📦 模型下载](#-模型下载)
- [🔐 隐私](#-隐私)
- [📁 工程结构](#-工程结构)
- [🐙 上传 GitHub 完整路径清单](#-上传-github-完整路径清单)
- [⚠️ 已知事项](#️-已知事项)
- [📄 License](#-license)

---

## ✨ 功能详解

### 1. 中英互译（Translate）
- 输入框 + 流式输出，仿 Google 翻译交互
- 中文 → 英文 / 英文 → 中文双向，一键交换源/目标语言
- 历史译文本地缓存，方便回看
- 引擎：**Gemma 4（LiteRT-LM）** 端侧推理，完全离线

### 2. AI 对话（Chat）
- 豆包 / ChatGPT 风格气泡 UI + "思考中"动画
- 多轮对话、按 token 流式渲染，KV-cache 加速
- 长按消息复制 / 重新生成
- 内置麦克风输入：按住 → 说话 → 松开，Gemma 端到端转写后直接发送
- 麦克风三态 UI：录音中（波形）/ 转录中（轨道动画）/ 生成中（Stop 按钮）
- 引擎：**Gemma 4（LiteRT-LM）** 多模态，含文本 + 音频

### 3. 语音翻译（Voice）
- **一个模型搞定一切**：识别 + 翻译全部走 Gemma 4 多模态音频编码器，不依赖任何第三方 ASR（Vosk 已移除）
- 索尼 / 豆包风格**响应式波形**：
  - 说话即跳动（包络跟随器，attack 快 / release 慢）
  - 静音时柔和呼吸基线（正弦滚动动画），表现为"一直在听"
  - 60Hz 逐帧重绘 + 渐变填充 + 高振幅 glow 光晕
- 16kHz 单声道 PCM 录音 → WAV 包装 → 交给 Gemma 音频编码器
- 停止录音后立即显示**三色轨道动画** + 阶段文案（识别中→整理中→翻译中→即将完成），防止"以为死机"
- Gemma 流式输出：原文 / 译文双卡片同步呈现，收到第一个 token 即关闭等待动画
- ZH→EN / EN→ZH 双向，方向 Chip 锁定在录制中不可误触
- 无模型时展示渐变占位图 + "去下载模型"一键跳转

### 4. 图像识别（Vision）
- 拍照 / 相册导入图片，支持多张图历史记录
- 四类常用问法：描述 / 文字翻译 / 解题 / 识物
- 流式输出 + 背压重试，不丢 token
- 引擎：**Gemma 4（LiteRT-LM）** 多模态（图像 + 文本同上下文）

### 5. 模型管理（Models）
- 内置 Gemma 4 E2B / E4B 两档，一键下载
- HTTP Range 断点续传 + WorkManager 后台任务，退出 App 后续传
- 多镜像源自动切换：`hf-mirror.com`（国内）/ `huggingface.co`（备用）
- 支持本地 `.litertlm` 文件手动导入
- 实时显示下载进度 / 已用磁盘 / 模型状态

### 6. 国际化 & 设置
- **应用语言实时切换**（中 / 英），切换后 Activity 自动重建，全 UI 立刻更新
- 三种主题：浅色 / 深色 / 跟随系统
- 推理后端：GPU（默认）/ CPU（兼容模式）
- 引擎状态指示：未下载模型时显示友好提示而非红色报错

---

## 🧱 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 / UI | **Kotlin 2.0.21** + Jetpack Compose 1.7 + Material 3 |
| 推理引擎 | **LiteRT-LM SDK 0.11.0**（`com.google.ai.edge.litertlm`）— Gemma 4 文本 / 图像 / 音频三模态 |
| 音频采集 | `AudioRecord` — 16kHz PCM16 mono，含自定义 WAV 包装器 |
| 波形渲染 | Compose Canvas 自绘，包络跟随器 + 60Hz 帧重绘 + 径向渐变 |
| DI | **Hilt 2.52** + KSP |
| 异步 / 流 | Kotlin Coroutines + Flow + `callbackFlow` + `buffer(64)` 背压 |
| 持久化 | DataStore（偏好设置）+ Room（聊天历史） |
| 后台任务 | WorkManager 2.9.1（模型下载） |
| 网络 | OkHttp + Range 断点续传 |
| 国际化 | AppCompat `AppCompatDelegate.setApplicationLocales` — 兼容 API 26–35 |
| Activity 基类 | `AppCompatActivity`（Hilt `@AndroidEntryPoint` 兼容） |
| 最低版本 | Android 8.0（SDK 26），arm64-v8a / armeabi-v7a |
| GMS 依赖 | **完全无**，可装在华为 / 荣耀等无 Google 设备 |

---

## 🛠️ 构建与运行

### 1. 环境准备

- **Android Studio Ladybug 2024.2.1** 及以上（含 Hedgehog / Koala / Meerkat）
- **JDK 17**（推荐用 Android Studio 自带 jbr）：
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
- Android SDK 35 + NDK 25.x（首次 Gradle Sync 自动安装）

### 2. 克隆与同步

```bash
git clone https://github.com/<你的用户名>/yiren.git
cd yiren
# Android Studio → File → Open → 选择 yiren/ 目录 → 自动 Gradle Sync
```

### 3. 国内镜像

`settings.gradle.kts` 已配置**阿里云 + 华为云 Maven 镜像**，国内构建无需代理。

### 4. Debug 构建（开发 / 调试）

```bash
./gradlew :app:installDebug         # 直连 ADB 设备安装（包名带 .debug 后缀）
./gradlew :app:assembleDebug        # 仅打包，输出到 app/build/outputs/apk/debug/
```

### 5. Release 构建（发行包）

**无签名密钥时**（朋友间传包 / 侧载测试）：

```bash
./gradlew :app:assembleRelease
# 自动 fallback 到 debug 签名
# 输出：app/build/outputs/apk/release/app-release.apk（~36 MB，V2 签名）
```

**使用正式签名**（正式发布时）：

在项目根目录新建 `keystore.properties`（已被 `.gitignore` 排除，**不要上传**）：

```properties
storeFile=../release.jks
storePassword=你的密码
keyAlias=yiren
keyPassword=你的密码
```

生成密钥（只需做一次）：

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias yiren
```

打包：

```bash
./gradlew :app:assembleRelease
```

### 6. 直接安装到手机（ADB）

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 📦 模型下载

应用首次启动后进入 **模型** 标签页，选择并下载离线模型：

| 模型 | 文件大小 | 内存峰值 | 支持能力 | 推荐设备 |
| --- | --- | --- | --- | --- |
| Gemma 4 · E2B IT | ~1.5 GB | ~3 GB | 文本 + 图像 + **语音** | 6 GB RAM 起 |
| Gemma 4 · E4B IT | ~3.0 GB | ~5 GB | 文本 + 图像 + **语音** | 8 GB RAM 起 |

> **两个模型都支持语音识别**（Gemma 4 的 LiteRT-LM 包内含音频编码器）。
> 选 E2B 加载更快（约 10 秒），E4B 推理质量更好。

下载链接来自 **hf-mirror.com**（国内直连），备用 `huggingface.co`；应用内自动按镜像优先级切换，全程断点续传。

---

## 🔐 隐私

- 所有 AI 推理**完全在设备本地**执行，不上云
- 网络仅用于**用户主动下载模型**（首次使用，约 1.5–3 GB）
- 不上传任何文本 / 图片 / 音频 / 用户行为 / 崩溃日志
- 完全无 Google Services / Firebase / 统计 SDK
- 无账号注册，无使用追踪

---

## 📁 工程结构

```
app/src/main/
├── AndroidManifest.xml               # 权限 + Activity + FileProvider + AppLocalesService
├── java/com/offlinetranslator/app/
│   ├── MainActivity.kt               # AppCompatActivity + Hilt + Compose setContent
│   ├── OfflineTranslatorApp.kt       # @HiltAndroidApp Application + WorkManager init
│   ├── di/
│   │   └── AppModule.kt              # Hilt provides（DB / Engine / Recorder / Repository）
│   ├── core/
│   │   ├── data/
│   │   │   ├── AppPreferences.kt     # DataStore 包装（主题 / 后端 / 语言偏好）
│   │   │   ├── db/AppDatabase.kt     # Room 数据库（聊天历史）
│   │   │   └── model/
│   │   │       ├── ModelInfo.kt      # 模型描述（id / size / url / supportsAudio...）
│   │   │       ├── ModelRegistry.kt  # 内置模型清单（E2B / E4B，supportsAudio=true）
│   │   │       └── ModelStorage.kt   # 磁盘路径 / 完整性校验
│   │   ├── designsystem/
│   │   │   ├── components/
│   │   │   │   ├── GlassCard.kt         # 玻璃拟态卡片
│   │   │   │   ├── WaveformBars.kt      # 响应式波形（包络跟随 + 60Hz + glow）
│   │   │   │   └── VoiceProcessingIndicator.kt  # 三色轨道动画（转录等待）
│   │   │   └── theme/                   # Color / Type / Theme（浅色 / 深色）
│   │   ├── i18n/LocaleManager.kt     # AppCompatDelegate per-app locale 封装
│   │   └── util/                     # Bitmap 转 PNG bytes、bitmapOf 等工具
│   ├── engine/
│   │   ├── llm/
│   │   │   ├── GemmaEngine.kt        # LiteRT-LM 封装（文本 / 图像 / 音频，GPU→CPU fallback）
│   │   │   ├── EngineState.kt        # IDLE / LOADING / READY / ERROR / MODEL_MISSING
│   │   │   └── PromptTemplates.kt    # Gemma 4 chat template + stop tokens
│   │   ├── audio/PcmAudioRecorder.kt # AudioRecord，16kHz PCM16 mono + amplitude Flow
│   │   ├── storage/ModelStorage.kt   # 模型文件 I/O
│   │   └── network/ModelDownloader.kt # OkHttp Range 续传 + 多镜像切换
│   └── feature/
│       ├── shell/                    # AppShell + BottomNavigation + Route 定义
│       ├── translate/                # 翻译页 Screen + ViewModel
│       ├── chat/                     # 对话页 Screen + ViewModel（含麦克风 Gemma ASR）
│       ├── voice/                    # 语音页 Screen + ViewModel（端到端 Gemma 音频）
│       ├── vision/                   # 图像页 Screen + ViewModel
│       ├── models/                   # 模型管理 Screen + ViewModel + DownloadWorker
│       └── settings/                 # 设置页 Screen + ViewModel（语言 / 主题 / 后端）
└── res/
    ├── drawable/                     # 矢量图（ic_launcher_foreground 等）
    ├── mipmap-anydpi-v26/            # 自适应图标
    ├── values/strings.xml            # 中文字符串（默认）
    ├── values-en/strings.xml         # English 字符串
    ├── values/themes.xml             # AppCompat 主题（浅色）
    ├── values-night/themes.xml       # 深色模式主题
    ├── values/colors.xml             # 品牌色系
    └── xml/
        ├── file_paths.xml            # FileProvider（相机临时文件）
        ├── locales_config.xml        # 支持语言清单（zh-CN / en）
        ├── backup_rules.xml
        └── data_extraction_rules.xml
```

---

## 🐙 上传 GitHub 完整路径清单

> 这是 Android Gradle KTS 项目，仓库根 = 项目根。  
> 将本目录 `git init` 后 `git push` 即可，下面列出**克隆后能直接 Build 的最小文件集**。

### ✅ 必须上传 · 根目录

| 路径 | 说明 |
| --- | --- |
| `README.md` | 项目说明（本文件） |
| `CHANGELOG.md` | 版本更新历史 |
| `LICENSE` | Apache 2.0 文本 |
| `.gitignore` | 已涵盖 `*.apk` / `*.jks` / `local.properties` / `models/` / `Yiren-*.apk` 等 |
| `build.gradle.kts` | 顶层 Gradle 脚本（插件版本管理） |
| `settings.gradle.kts` | 模块声明 + 国内 Maven 镜像（阿里云 / 华为云） |
| `gradle.properties` | JVM 堆大小 / AndroidX 开关 / Kotlin 编译选项 |
| `gradlew` | Linux / macOS wrapper ⚠️ 提交前需 `chmod +x gradlew` |
| `gradlew.bat` | Windows wrapper |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 版本（当前 8.10.2） |
| `gradle/wrapper/gradle-wrapper.jar` | wrapper 引导 jar |

### ✅ 必须上传 · `app/`

| 路径 | 说明 |
| --- | --- |
| `app/build.gradle.kts` | 模块构建脚本（依赖 / ABI filter / minSdk / buildTypes） |
| `app/proguard-rules.pro` | ProGuard keep 规则（LiteRT / TFLite / Compose / Coroutines / Room） |

### ✅ 必须上传 · `app/src/main/`

| 路径 | 说明 |
| --- | --- |
| `AndroidManifest.xml` | 权限声明 + Activity + FileProvider + `AppLocalesMetadataHolderService` |

#### 源码：`app/src/main/java/com/offlinetranslator/app/`

| 子路径 | 说明 |
| --- | --- |
| `MainActivity.kt` | AppCompatActivity + Compose setContent（国际化基础） |
| `OfflineTranslatorApp.kt` | Application 入口 |
| `di/AppModule.kt` | Hilt 模块 |
| `core/data/model/ModelInfo.kt` | 模型数据类 |
| `core/data/model/ModelRegistry.kt` | 内置模型清单（含 `supportsAudio = true`） |
| `core/data/model/ModelStorage.kt` | 文件路径、完整性校验 |
| `core/data/db/` | Room Entity + DAO + Database |
| `core/data/AppPreferences.kt` | DataStore 包装 |
| `core/designsystem/components/GlassCard.kt` | 玻璃卡片 |
| `core/designsystem/components/WaveformBars.kt` | 响应式波形 |
| `core/designsystem/components/VoiceProcessingIndicator.kt` | 转录等待动画 |
| `core/designsystem/theme/` | Color / Type / Theme |
| `core/i18n/LocaleManager.kt` | 语言切换封装 |
| `core/util/` | 工具函数 |
| `engine/llm/GemmaEngine.kt` | LiteRT-LM 核心（文本 / 图像 / 音频） |
| `engine/llm/PromptTemplates.kt` | Gemma 提示词模板 |
| `engine/audio/PcmAudioRecorder.kt` | 麦克风采集 |
| `engine/storage/` | 模型文件管理 |
| `engine/network/` | OkHttp 多镜像下载 |
| `feature/shell/` | AppShell + 底部导航 + 路由 |
| `feature/translate/` | 翻译 Tab |
| `feature/chat/` | 对话 Tab（含麦克风 ASR） |
| `feature/voice/` | 语音 Tab（端到端 Gemma 音频） |
| `feature/vision/` | 图像 Tab |
| `feature/models/` | 模型管理 Tab + WorkManager |
| `feature/settings/` | 设置 Tab（语言 / 主题 / 后端） |

#### 资源：`app/src/main/res/`

| 子目录 | 内容 |
| --- | --- |
| `drawable/` | 矢量图资源 |
| `mipmap-*/` | 各密度启动图标（含 anydpi-v26 自适应） |
| `values/` | 中文字符串 / 颜色 / 主题 / 维度 |
| `values-en/` | 英文字符串 |
| `values-night/` | 深色模式覆盖 |
| `xml/file_paths.xml` | FileProvider 路径配置（相机临时文件） |
| `xml/locales_config.xml` | 支持语言声明（zh-CN + en） |
| `xml/backup_rules.xml` | 备份白名单 |
| `xml/data_extraction_rules.xml` | 数据提取规则 |

### 🚫 严禁上传

| 路径 | 原因 |
| --- | --- |
| `local.properties` | 含本机 SDK/NDK 绝对路径，因机而异 |
| `*.jks` · `keystore.properties` · `release-*.jks` | **签名私钥 — 绝对不能公开** |
| `.gradle/` · `build/` · `app/build/` | 构建缓存，动辄几 GB |
| `.idea/` · `*.iml` · `.DS_Store` | IDE / macOS 个人配置 |
| `*.apk` · `*.aab` | 编译产物，走 GitHub Releases 发布 |
| `Yiren-*.apk` | 本地构建包，走 Releases |
| `*.litertlm` · `*.tflite` · `*.task` · `*.bin` · `models/` | 模型权重 — 超 GitHub 文件限制 |
| `google-services.json` | 不需要（零 GMS 依赖） |

### 🟡 推荐补充

| 路径 | 说明 |
| --- | --- |
| `CHANGELOG.md` | 已创建 ✓ |
| `screenshots/` | 五大功能截图（README 内嵌引用） |
| `.github/workflows/android.yml` | CI 自动构建 debug APK |
| `docs/architecture.md` | 架构决策记录（可选） |

### 一键提交流程

```bash
# ① 首次建仓
chmod +x gradlew                  # ⚠️ macOS/Linux 必须
git init
git add .
git status --ignored              # 检查有无漏网的大文件
git commit -m "init: 译人 v1.0.3"
git branch -M main
git remote add origin git@github.com:<your-name>/yiren.git
git push -u origin main

# ② 后续迭代
git add -A
git commit -m "fix: 语言切换不生效"
git push

# ③ 发布 Release
# GitHub → Releases → Draft a new release
# Tag: v1.0.3
# 附件: Yiren-1.0.3-release.apk（约 36 MB，V2 签名，可直接侧载）
```

### 📦 APK / 模型发布规则

| 文件 | 存放位置 |
| --- | --- |
| `Yiren-x.x.x-release.apk` | GitHub Releases（手动上传，勿 commit） |
| `*.litertlm`（模型权重） | 留在 HuggingFace / hf-mirror；App 内置下载链接 |
| `local.properties` · `*.jks` | **本地保管，永远不提交** |

---

## ⚠️ 已知事项

1. **首次冷启动慢**：Gemma 4 E4B 在中端机需 30–60 秒加载，E2B 约 10 秒；期间显示加载动画。
2. **首次进入任何功能页**：未下载模型时，页面显示友好 CTA "去下载模型" 并跳转模型 Tab，不再弹红字报错。
3. **语音 / 对话首次推理慢**：Gemma audio encoder 首次激活需 5–30 秒；这段时间显示三色轨道动画，表明 App 正在工作。
4. **图像推理首次慢**：vision encoder 首次 native 编译约 10 秒；同会话内后续图片秒级。
5. **大文件下载**：建议 WiFi 下载（E2B ~1.5 GB，E4B ~3 GB）；已实现 Range 断点续传，App 退出后重开可继续。
6. **华为 GPU 兼容**：Gemma 4 在部分 Mali GPU 上自动 fallback 到 CPU，正常运行但更慢；可在「设置」手动切 CPU。

---

## 📄 License

[Apache License 2.0](LICENSE)

第三方组件遵循各自原协议：
- [LiteRT-LM SDK](https://github.com/google-ai-edge/litert-lm) — Apache 2.0
- [Gemma 模型权重](https://ai.google.dev/gemma/terms) — Gemma Terms of Use
- [Jetpack Compose / AppCompat / Room / Hilt / WorkManager](https://developer.android.com) — Apache 2.0
