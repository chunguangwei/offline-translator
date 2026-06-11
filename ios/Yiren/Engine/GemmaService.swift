import Foundation

/// 引擎可观测状态 —— 对应 Android `EngineState`。
/// `modelMissing` 区别于 `error`：前者是首跑常态（还没下载模型），UI 给内联横幅而非报错。
enum EngineState: Equatable {
    case idle
    case loading
    case ready
    case modelMissing
    case error(String)
}

enum EngineFailure: Error {
    case modelMissing
    case initFailed(String)
    case notLoaded
}

/// 采样配置。放在类型外（非 MainActor 隔离），默认参数才能引用。
/// - precise：翻译等确定性任务。0.7 的对话温度会让小模型译完目标语言后
///   "刹不住车"继续滚出一串其他语言（用户真机实测），低温收敛。
/// - chat：自由问答，保留创造性。
enum Samplers {
    static let precise = try? SamplerConfig(topK: 40, topP: 0.9, temperature: 0.2)
    static let chat = try? SamplerConfig(topK: 40, topP: 0.95, temperature: 0.7)
}

/// 推理后端偏好（设置页可选，对应 Android InferenceBackend）。
enum BackendPref: String, CaseIterable {
    case auto = "AUTO"
    case gpu = "GPU"
    case cpu = "CPU"

    static var current: BackendPref {
        BackendPref(rawValue: UserDefaults.standard.string(forKey: "backendPref") ?? "AUTO") ?? .auto
    }
}

@MainActor
final class GemmaService: ObservableObject {
    static let shared = GemmaService()

    @Published private(set) var state: EngineState = .idle
    @Published private(set) var activeModel: ModelInfo?
    /// 实际协商出的后端描述（设置页展示，如 "GPU"/"CPU"）。
    @Published private(set) var activeBackendLabel: String = "—"
    /// 当前加载是否启用了视觉/音频编码器。
    @Published private(set) var visionEnabled = false
    @Published private(set) var audioEnabled = false

    private var engine: Engine?
    private var loadedModelId: String?
    private var loadedBackendPref: BackendPref?
    private var currentConversation: Conversation?
    private let storage = ModelStorage.shared

    /// 设置页切了后端 → 下次 ensureLoaded 重载。
    func invalidateForBackendChange() {
        if loadedBackendPref != BackendPref.current {
            engine = nil
            loadedModelId = nil
            state = .idle
        }
    }

    /// 确保目标模型已加载进内存。幂等：已加载同一模型直接返回。
    func ensureLoaded() async -> Result<ModelInfo, EngineFailure> {
        let info = ModelRegistry.byId(storage.activeModelId) ?? ModelRegistry.defaultModel

        if engine != nil, loadedModelId == info.id {
            state = .ready
            activeModel = info
            return .success(info)
        }

        // 可用模型 = 用户下载的副本 || App 包内预置（开发期模型随包）。
        guard let file = storage.availableURL(for: info) else {
            state = .modelMissing
            activeModel = info
            return .failure(.modelMissing)
        }

        // 切换模型：先卸掉旧引擎。
        engine = nil
        loadedModelId = nil
        state = .loading
        activeModel = info

        // 主模型按偏好（AUTO/GPU = GPU 优先回退 CPU；CPU = 仅 CPU）。
        // 视觉/音频编码器一律 CPU（与 Android 决策一致：部分设备 GPU 编码器
        // 初始化成功但输出异常向量）；编码器失败逐级降配 (有图有音)→(仅图)→(纯文本)。
        let pref = BackendPref.current
        let mains: [Backend] = pref == .cpu ? [.cpu()] : [.gpu, .cpu()]
        var encoderCombos: [(Backend?, Backend?)] = [(nil, nil)]
        if info.supportsImage && info.supportsAudio {
            encoderCombos = [(.cpu(), .cpu()), (.cpu(), nil), (nil, nil)]
        } else if info.supportsImage {
            encoderCombos = [(.cpu(), nil), (nil, nil)]
        }

        var lastError = "engine init failed"
        for main in mains {
            for (vision, audio) in encoderCombos {
                do {
                    let config = try EngineConfig(
                        modelPath: file.path,
                        backend: main,
                        visionBackend: vision,
                        audioBackend: audio,
                        maxNumTokens: info.maxTokens,
                        // 缓存必须落可写目录：模型可能在只读的 App 包内。
                        cacheDir: storage.modelsDir.path
                    )
                    let candidate = Engine(engineConfig: config)
                    try await candidate.initialize()
                    engine = candidate
                    loadedModelId = info.id
                    loadedBackendPref = pref
                    visionEnabled = vision != nil
                    audioEnabled = audio != nil
                    activeBackendLabel = (main.rawValue == "gpu") ? "GPU" : "CPU"
                    state = .ready
                    return .success(info)
                } catch {
                    lastError = String(describing: error)
                    continue
                }
            }
        }
        state = .error(lastError)
        return .failure(.initFailed(lastError))
    }

    /// 单轮流式生成（翻译等无历史任务）。
    func generateStream(
        prompt: String,
        sampler: SamplerConfig? = Samplers.chat
    ) async throws -> AsyncThrowingStream<String, Error> {
        guard let engine else { throw EngineFailure.notLoaded }
        let conversation = try await engine.createConversation(
            with: ConversationConfig(samplerConfig: sampler)
        )
        currentConversation = conversation
        return textStream(from: conversation.sendMessageStream(Message(prompt)))
    }

    /// 多轮问答流式生成 —— 用 LiteRT-LM 原生会话：system + 历史 initialMessages +
    /// 当前用户消息（可带图片/音频 Content），模板渲染交给引擎（比 Android 手拼干净）。
    func chatStream(
        history: [Message],
        user: Message,
        sampler: SamplerConfig? = Samplers.chat
    ) async throws -> AsyncThrowingStream<String, Error> {
        guard let engine else { throw EngineFailure.notLoaded }
        let conversation = try await engine.createConversation(
            with: ConversationConfig(
                systemMessage: Message(PromptTemplates.chatSystem(), role: .system),
                initialMessages: history,
                samplerConfig: sampler
            )
        )
        currentConversation = conversation
        return textStream(from: conversation.sendMessageStream(user))
    }

    /// 语音逐字转写（单轮，低温采样保证忠实）。
    func transcribe(wav: Data, zh: Bool) async throws -> AsyncThrowingStream<String, Error> {
        guard let engine else { throw EngineFailure.notLoaded }
        guard audioEnabled else { throw EngineFailure.initFailed("audio encoder not enabled") }
        let conversation = try await engine.createConversation(
            with: ConversationConfig(samplerConfig: Samplers.precise)
        )
        currentConversation = conversation
        let message = Message(contents: [
            .audioData(wav),
            .text(PromptTemplates.transcribeVerbatim(zh: zh)),
        ])
        return textStream(from: conversation.sendMessageStream(message))
    }

    /// 把引擎的 Message 流转成干净的文本增量流：
    /// 兼容增量/累计两种流式约定（Android 端踩过的坑）+ 停止符防御裁剪。
    private func textStream(
        from inner: AsyncThrowingStream<Message, Error>
    ) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream<String, Error> { continuation in
            let task = Task {
                // 兼容增量/累计两种流式约定（Android 端踩过的坑）：
                // 新块若以已发文本为前缀则视为累计，只发 delta。
                var emitted = ""
                do {
                    for try await message in inner {
                        let raw = message.toString
                        guard !raw.isEmpty else { continue }
                        var combined: String
                        if raw.count >= emitted.count, raw.hasPrefix(emitted) {
                            combined = raw // 累计式
                        } else {
                            combined = emitted + raw // 增量式
                        }
                        let clean = PromptTemplates.trimAtStop(combined)
                        if clean.count > emitted.count {
                            let delta = String(clean.dropFirst(emitted.count))
                            emitted = clean
                            continuation.yield(delta)
                        }
                        if clean.count < combined.count { break } // 命中停止符
                    }
                    continuation.finish()
                } catch {
                    if error is CancellationError {
                        continuation.finish()
                    } else {
                        continuation.finish(throwing: error)
                    }
                }
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }

    /// 取消当前生成（翻译页「停止」按钮）。
    func cancelGeneration() {
        try? currentConversation?.cancel()
    }
}
