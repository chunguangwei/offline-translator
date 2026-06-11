# 译人 iOS 二期：全功能对齐 Android 实施计划

> 2026-06-11 用户拍板选 A（主线推进）。V1（翻译先行）已真机验证通过。
> 本期把问答/历史/设置/启动页全部对齐 Android v1.0.6 行为。

**Goal:** iOS 四 Tab 全部真实化 + 品牌启动页，与 Android 功能/UI/产品决策一致。

**Architecture:** SwiftUI + SwiftData（iOS17+ 原生持久化，对应 Android Room）；
问答用 LiteRT-LM Swift 的**原生多轮 API**（ConversationConfig.initialMessages +
systemMessage + 多模态 Message），比 Android 手拼 `<|turn>` 模板更干净；
图片用 `Content.imageFile(绝对路径)` 零拷贝；语音 AVAudioEngine 16k PCM → WAV
→ `Content.audioData`。

**对齐的产品决策（不重新讨论）：**
- 上下文 = 摘要 + 全部未压缩双方消息；压缩触发 >20 条或 >3000 字，留 6 条原文
- 图片：发送即清预览、进气泡、可全屏预览、窗口内追问重喂、压缩后停喂
- 语音 = 输入法（转写回填输入框，用户确认再发）
- 历史只存翻译记录、同句去重；模型不随包；提示词文案随系统语言
- 视觉/音频编码器 CPU（稳定优先），主模型 GPU→CPU 回退
- 启动页 6s 可跳过、眨眼 logo、轮换标语、splash.json 远程换图（同一 URL 契约）

## Tasks

### 1. 数据层 `Yiren/Data/Store.swift`
SwiftData @Model：ChatSession(id unique,title,updatedAt,modelId,summary,summarizedCount)、
ChatMessage(sessionId,role,content,imagePath,createdAt)、
TranslationRecord(sourceText,translatedText,sourceLang,targetLang,createdAt)。
共享 ModelContainer 单例；YirenApp 挂 .modelContainer。

### 2. 引擎扩展 `Engine/GemmaService.swift` + `Engine/PromptTemplates.swift`
- ensureLoaded：vision/audio 编码器按模型能力开启（CPU）；主后端按设置
  AUTO=[gpu,cpu]/GPU=[gpu,cpu]/CPU=[cpu]；编码器失败逐级降配 (cpu,cpu)→(cpu,nil)→(nil,nil)
- chatStream(history:[Message], user:Message, sampler)：原生多轮
- transcribe(wav:Data)：单轮音频转写
- PromptTemplates：chatSystem/transcribe/summarize/图片标记 全部随 Locale 中英

### 3. 录音 `Engine/AudioRecorder.swift`
AVAudioEngine tap → 16k mono PCM16 累积 + 幅度流；pcmToWav；
Info.plist NSMicrophoneUsageDescription。

### 4. 问答 `Features/Chat/`
ChatViewModel：会话 CRUD/打开/删除（删图片文件）；send（图存
chat_images/uuid.jpg，发送即清预览，历史=摘要桥接+全部未压缩+预算裁剪，
新图/重喂图 imageFile，流式，stop 落部分+touch 会话）；压缩(20/3000/6)；
语音 start/stop→transcribe 回填。
ChatView：顶栏(History 抽屉/新会话)、气泡(用户图+文字/助手 Markdown)、
全屏图预览、录音波形+转写中指示、输入条(附图/麦克风/发送/停止)、贴底滚动。

### 5. 历史 `Features/History/HistoryView.swift` + TranslateViewModel 挂钩
@Query 倒序列表、复制/删除/清空、空态；翻译成功 dedup 后写入。

### 6. 设置 `Features/Settings/SettingsView.swift`
主题(系统/浅/深 @AppStorage→preferredColorScheme)、推理后端(AUTO/GPU/CPU
@AppStorage→GemmaService，显示实际协商结果)、模型管理入口、
关于与许可(版本/©/专有许可/GitHub 链接/三方致谢/隐私)。无"应用更新"
（iOS 由 App Store 承担——平台差异）。

### 7. 启动页 `Features/Splash/SplashView.swift` + `SplashRemoteStore.swift`
Canvas 重绘角色（圆头+尾巴三角+肩身 cubic，眼睛 destinationOut 镂空+
随机眨眼 scaleY）；6s 倒计时跳过胶囊；轮换标语；版本号；
splash.json（jsDelivr→raw）缓存图下次启动整屏显示。

### 8. 壳与收尾
RootTabView 挂真实四页；YirenApp：Splash→Crossfade 主界面 + modelContainer
+ 主题。每阶段 xcodegen + xcodebuild 模拟器编译绿；最后截图冒烟 + 推送。
