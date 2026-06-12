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

/// 生成/引擎生命周期串行门 —— 对应 Android 的 mutex + genMutex：
/// LiteRT-LM 引擎单租户，并发生成会损坏 KV-cache；后台上下文压缩与用户
/// 下一条提问可能同时发生，必须排队。
actor GenerationGate {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func acquire() async {
        if !busy {
            busy = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        if waiters.isEmpty {
            busy = false
        } else {
            waiters.removeFirst().resume()
        }
    }
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
    private let gate = GenerationGate()

    /// 确保目标模型已加载进内存。幂等：已加载同一模型且后端偏好未变直接返回。
    /// 经 [gate] 串行 —— 卸载/重载引擎不能与任何在途生成并发（悬空原生指针）。
    func ensureLoaded() async -> Result<ModelInfo, EngineFailure> {
        await gate.acquire()
        let result = await ensureLoadedLocked()
        await gate.release()
        return result
    }

    private func ensureLoadedLocked() async -> Result<ModelInfo, EngineFailure> {
        let info = ModelRegistry.byId(storage.activeModelId) ?? ModelRegistry.defaultModel

        // 后端偏好变更（设置页切换）也触发重载——但只会在拿到 gate 后发生，
        // 不会拔掉正在生成的引擎。
        if engine != nil, loadedModelId == info.id, loadedBackendPref == BackendPref.current {
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
                    // 主模型 GPU + 编码器 CPU 时如实显示混合状态（与 Android MIXED 对齐）。
                    let mainGpu = main.rawValue == "gpu"
                    let hasCpuEncoder = vision != nil || audio != nil
                    activeBackendLabel = mainGpu ? (hasCpuEncoder ? "GPU + CPU" : "GPU") : "CPU"
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

    /// 单轮流式生成（翻译等无历史任务）；可附一张图（拍照翻译）。
    func generateStream(
        prompt: String,
        sampler: SamplerConfig? = Samplers.chat,
        imageData: Data? = nil
    ) async throws -> AsyncThrowingStream<String, Error> {
        if imageData != nil, !visionEnabled {
            throw EngineFailure.initFailed("vision encoder not enabled")
        }
        return try await gatedStream { engine in
            try await engine.createConversation(
                with: ConversationConfig(samplerConfig: sampler)
            )
        } send: { conversation in
            var contents: [Content] = []
            if let imageData { contents.append(.imageData(imageData)) }
            contents.append(.text(prompt))
            return conversation.sendMessageStream(Message(contents: contents))
        }
    }

    /// 多轮问答流式生成 —— 用 LiteRT-LM 原生会话：system + 历史 initialMessages +
    /// 当前用户消息（可带图片/音频 Content），模板渲染交给引擎（比 Android 手拼干净）。
    func chatStream(
        history: [Message],
        user: Message,
        system: String = PromptTemplates.chatSystem(),
        sampler: SamplerConfig? = Samplers.chat
    ) async throws -> AsyncThrowingStream<String, Error> {
        try await gatedStream { engine in
            try await engine.createConversation(
                with: ConversationConfig(
                    systemMessage: Message(system, role: .system),
                    initialMessages: history,
                    samplerConfig: sampler
                )
            )
        } send: { conversation in
            conversation.sendMessageStream(user)
        }
    }

    /// 语音逐字转写（单轮，低温采样保证忠实）。
    func transcribe(wav: Data, zh: Bool) async throws -> AsyncThrowingStream<String, Error> {
        guard audioEnabled else { throw EngineFailure.initFailed("audio encoder not enabled") }
        return try await gatedStream { engine in
            try await engine.createConversation(
                with: ConversationConfig(samplerConfig: Samplers.precise)
            )
        } send: { conversation in
            conversation.sendMessageStream(Message(contents: [
                .audioData(wav),
                .text(PromptTemplates.transcribeVerbatim(zh: zh)),
            ]))
        }
    }

    /// 取门 → 建会话 → 发消息，流终止（完成/取消/出错）时放门。
    /// 所有生成必经此处，确保与引擎重载互斥（Android genMutex 同款语义）。
    private func gatedStream(
        makeConversation: (Engine) async throws -> Conversation,
        send: (Conversation) -> AsyncThrowingStream<Message, Error>
    ) async throws -> AsyncThrowingStream<String, Error> {
        await gate.acquire()
        do {
            guard let engine else { throw EngineFailure.notLoaded }
            let conversation = try await makeConversation(engine)
            currentConversation = conversation
            return textStream(from: send(conversation))
        } catch {
            await gate.release()
            throw error
        }
    }

    /// 把引擎的 Message 流转成干净的文本增量流：
    /// 兼容增量/累计两种流式约定（Android 端踩过的坑）+ 停止符防御裁剪。
    private func textStream(
        from inner: AsyncThrowingStream<Message, Error>
    ) -> AsyncThrowingStream<String, Error> {
        let gate = self.gate
        return AsyncThrowingStream<String, Error> { continuation in
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
                // 流终止（完成/取消/出错）→ 放生成门。onTermination 恰好触发一次。
                Task { await gate.release() }
            }
        }
    }

    /// 取消当前生成（翻译页「停止」按钮）。
    func cancelGeneration() {
        try? currentConversation?.cancel()
    }
}
