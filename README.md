# 译人 · Yiren

> 完全离线 / 端侧推理的 AI 翻译与问答应用，**Android + iOS 双端功能对齐**。
> 翻译（文字/语音）· 问答（文字/语音/图片，多轮会话）· 历史 · 设置，
> 零依赖 Google 服务（GMS-free），适配华为 / 荣耀等无 GMS 设备。

**Android 1.0.10**（最低 Android 8.0 / API 26，arm64-v8a + armeabi-v7a）　|　**iOS 1.0.1**（最低 iOS 17，开发签名分发，上架准备中）

---

## 目录

- [✨ 功能详解](#-功能详解)
- [🧱 技术栈](#-技术栈)
- [🛠️ 构建与运行](#️-构建与运行)
- [🍏 iOS 构建](#-ios-构建)
- [📦 模型下载](#-模型下载)
- [🔐 隐私](#-隐私)
- [📁 工程结构](#-工程结构)
- [🐙 上传 GitHub 完整路径清单](#-上传-github-完整路径清单)
- [⚠️ 已知事项](#️-已知事项)
- [📄 License](#-license)

---

## ✨ 功能详解

> 双端同款底部 5-Tab：**翻译 / 问答 / 学习 / 历史 / 设置**，UI 贴合品牌暖色（珊瑚 #FF6F61 / 琥珀 #FFB152 / 品红 #C2479B）。

### 1. 翻译（Translate）
- 中英双向流式翻译，一键交换方向，低温采样保证只输出一种目标语言
- **语音输入**：录音 → Gemma 离线逐字转写 → 回填输入框（用户确认后再翻译）
- **拍照 / 选图翻译**：识别图中文字逐行「原文 => 译文」对照流式输出
- **译文分享卡片**：暖色品牌渐变卡图一键分享
- 模型缺失顶部内联横幅 + 「去下载」；首载「正在加载模型」提示
- 翻译成功自动写入历史（同句去重）

### 2. 问答（Q&A）
- **角色预设**：默认助手 / E人陪聊（中来中回、英来英回）/ 翻译官 / 语法老师 /
  写作润色 / 英语陪练（英语闲聊 + ✏️ 纠错），切换自动开新会话
- 多轮会话：会话抽屉（切换 / 删除），标题自动取首问
- **上下文压缩**：上下文 = 摘要 + 全部未压缩双方消息；超 20 条或会话满时
  后台静默压缩成 ≤200 字摘要，保留最近 6 条原文 —— 离线小模型的长对话方案
- **图片问答**：相册选图（系统 Picker 免权限），图随消息进气泡、可点全屏预览；
  追问时自动"重喂"窗口内最近一张图，压缩后自然停喂
- **语音输入**：转写回填输入框，不直接发送
- 暂停生成保留已输出部分；回复语言跟随应用语言

### 3. 学习（Learn）
- **SRS 间隔重复学习闭环**：跨全部单词本 + 生词本聚合的「🔥 连续打卡 + 今日到期 N 张 +
  开始复习」每日仪式。Leitner 盒子调度——答对隔更久再现（1→3→7→16→30 天，到顶即掌握），
  答错打回最低档很快重练；新词受每日量限额渐进放出，不会一次涌入
- **错题本**：反复答错（≥3 次）的词单独汇总，可只练难词强化
- **每日提醒**：可选的本地通知到点提醒复习（纯本地、不联网，默认关）
- **生词本**：历史记录星标收藏 → 纳入复习池
- **单词本**：上传生词文本（粘贴或 txt），AI 离线提取「英文 => 释义 => 注释」建库
 （纯英文词表自动补释义）；自定名称 / 用途 / 每日学习量；中→英 / 英→中 / 混合翻卡
- 双端同款算法（间隔表 / 盒子 / 打卡规则逐字一致），纯函数可单测

### 4. 历史（History）
- 只存翻译记录（产品决策）：语言方向 / 原文 / 译文 / 时间，星标收藏，复制 / 删除 / 清空

### 5. 设置（Settings）
- 主题（跟随系统 / 浅 / 深）、应用语言（中 / 英）
- 推理后端（自动 = GPU 优先回退 CPU / 手动 GPU / CPU），显示实际协商结果；
  视觉 / 音频编码器始终走 CPU（跨设备稳定性优先）
- 模型管理（下载 / 启用 / 删除）+ 下载源四档（国内优先 / 官方优先 / 自定义镜像 / 仅本地）
- 关于与许可（版本 / 版权 / 专有许可 / GitHub / 第三方致谢 / 隐私声明）
- Android 专属：应用内检查更新（GitHub Release 自动升级）

### 6. 品牌启动页（Splash）
- 暖色渐变 + **眨眼 logo 角色动画** + 轮换功能标语，6 秒可跳过
- **远程换图无需发版**：改仓库 `branding/splash/splash.json` 即可（详见该目录 README）

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

**iOS 端**（`ios/`，与 Android 功能对齐）：

| 类别 | 选型 |
| --- | --- |
| 语言 / UI | Swift 5.10 + SwiftUI（iOS 17+，单 target 无三方依赖） |
| 推理引擎 | LiteRT-LM 官方 Swift 封装 + `CLiteRTLM.xcframework`（真机 arm64 + 模拟器 arm64） |
| 多轮对话 | LiteRT-LM 原生会话 API（systemMessage + initialMessages + 多模态 Message） |
| 持久化 | SwiftData（会话 / 消息 / 翻译历史） |
| 音频 | AVAudioEngine 16kHz PCM16 + WAV 封装 |
| 模型下载 | background URLSession（锁屏 / 杀进程不中断，系统 resumeData 续传） |
| 工程 | xcodegen 声明式（`project.yml` 入库，`.xcodeproj` 随手再生） |

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

### 7. 发版（Android）

```bash
# CHANGELOG.md 写好新版本条目（更新弹窗会展示这段"人话"内容）→ bump 版本号 → 打 tag
git tag v1.0.X && git push origin v1.0.X
# GitHub Actions 自动：签名构建 → 发 Release → 写回应用内更新清单 latest.json
```

---

## 🍏 iOS 构建

```bash
brew install xcodegen           # 一次性
cd ios && xcodegen generate && open Yiren.xcodeproj
```

- Xcode 里选 Personal Team / 付费 Team → 选真机 → ⌘R（详细步骤见 `ios/需要你做的.md`）
- 新增源文件后需重跑 `xcodegen generate`
- **开发期模型随包**：把 `.litertlm` 丢进 `ios/BundledModels/`（gitignored）再构建，
  装上即用免下载；正式分发不带模型，用户应用内下载
- 上架 App Store 需付费开发者账号；LaunchScreen 静态 / 图标无 alpha / 全离线隐私标签等
  合规项已预埋

---

## 📦 模型下载

首次启动按提示进入 **设置 → 模型管理**，选择并下载离线模型：

| 模型 | 文件大小 | 内存峰值 | 支持能力 | 推荐设备 |
| --- | --- | --- | --- | --- |
| Gemma 4 · E2B IT | ~2.4 GB | ~4 GB | 文本 + 图像 + **语音** | 6 GB RAM 起（iPhone 13 实测可用） |
| Gemma 4 · E4B IT | ~3.4 GB | ~5.5 GB | 文本 + 图像 + **语音** | 8 GB RAM 起 |

> 两个模型都内含音频编码器（语音识别）。E2B 加载更快，E4B 质量更好。

下载源顺序（双端一致，设置页可改）：**ModelScope（魔搭/阿里云，国内最快）→ hf-mirror → huggingface.co 官方**，
逐源自动兜底，全程断点续传（iOS 用系统后台会话，锁屏/杀进程不中断）。

> 为什么 ModelScope 优先：hf-mirror 对 HF Xet 类型仓库只会 308 重定向回 huggingface.co，
> 国内无加速效果；ModelScope 直连阿里云 OSS 实测快一个量级。

---

## 🔐 隐私

- 所有 AI 推理**完全在设备本地**执行，不上云
- 联网仅用于三件事：**下载模型**（首次使用，约 1.5–3 GB）、**检查更新**、**启动时获取启动图与运营信息**——均不含任何个人数据
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

```
ios/                                  # iOS 端（SwiftUI，与 Android 功能对齐）
├── project.yml                       # xcodegen 工程声明（.xcodeproj 不入库）
├── 需要你做的.md                      # 真机/验收步骤
├── BundledModels/                    # 开发期"模型随包"开关（gitignored，平时仅 .gitkeep）
├── LiteRTLM/                         # 官方 LiteRT-LM Swift 封装 + CLiteRTLM.xcframework
└── Yiren/
    ├── App/YirenApp.swift            # 入口：启动页→主界面 + SwiftData 容器 + 后台下载挂点
    ├── Data/Store.swift              # SwiftData：会话/消息/翻译历史
    ├── DesignSystem/BrandTheme.swift # 品牌色 + 键盘收起扩展
    ├── Engine/                       # GemmaService / PromptTemplates / AudioRecorder
    ├── Features/                     # Translate / Chat / History / Settings / Models / Splash
    └── Assets.xcassets/AppIcon       # 1024 全出血图标（无 alpha）

branding/
├── yiren-icon-master.svg / .png      # logo master（1024）
├── splash/splash.json                # 启动页远程换图配置（改它免发版换图）
└── update/latest.json                # 应用内更新清单（CI 发版自动写回）
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
| `LICENSE` | 专有许可协议（商用需作者书面授权） |
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

**专有许可（Proprietary）** — 详见 [LICENSE](LICENSE)。

版权所有 © 2026 weichunguang，保留所有权利。可出于个人学习、研究、评估目的查看、运行、修改与（非商业）分享，须保留本协议与版权声明；**任何商业用途须事先获得著作权人 weichunguang（chunguangwei@gmail.com）的书面同意**。

第三方组件遵循各自原协议：
- [LiteRT-LM SDK](https://github.com/google-ai-edge/litert-lm) — Apache 2.0
- [Gemma 模型权重](https://ai.google.dev/gemma/terms) — Gemma Terms of Use
- [Jetpack Compose / AppCompat / Room / Hilt / WorkManager](https://developer.android.com) — Apache 2.0
