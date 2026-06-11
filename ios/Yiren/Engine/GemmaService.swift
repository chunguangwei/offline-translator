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

/// 对 LiteRT-LM 的应用层封装 —— 对应 Android `GemmaEngine.kt`。
/// 单实例；加载策略与 Android 一致：GPU 优先，初始化失败回退 CPU。
/// V1 纯文本，vision/audio 编码器不启用（省内存、加载更快），后续阶段打开。
@MainActor
final class GemmaService: ObservableObject {
    static let shared = GemmaService()

    @Published private(set) var state: EngineState = .idle
    @Published private(set) var activeModel: ModelInfo?

    private var engine: Engine?
    private var loadedModelId: String?
    private var currentConversation: Conversation?
    private let storage = ModelStorage.shared

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

        // GPU 优先，失败回退 CPU —— 与 Android backendChain(AUTO) 一致。
        var lastError = "engine init failed"
        for backend in [Backend.gpu, Backend.cpu()] {
            do {
                let config = try EngineConfig(
                    modelPath: file.path,
                    backend: backend,
                    visionBackend: nil,
                    audioBackend: nil,
                    maxNumTokens: info.maxTokens,
                    // 缓存必须落可写目录：模型可能在只读的 App 包内（随包预置），
                    // 默认"模型同目录"会写失败。
                    cacheDir: storage.modelsDir.path
                )
                let candidate = Engine(engineConfig: config)
                try await candidate.initialize()
                engine = candidate
                loadedModelId = info.id
                state = .ready
                return .success(info)
            } catch {
                lastError = String(describing: error)
                continue
            }
        }
        state = .error(lastError)
        return .failure(.initFailed(lastError))
    }

    /// 流式生成：每次新建 Conversation（多轮上下文由调用方拼进 prompt，与 Android 同策略），
    /// 文本增量经 [PromptTemplates.trimAtStop] 防御性裁剪后产出。
    func generateStream(prompt: String) async throws -> AsyncThrowingStream<String, Error> {
        guard let engine else { throw EngineFailure.notLoaded }
        let sampler = try? SamplerConfig(topK: 40, topP: 0.95, temperature: 0.7)
        let conversation = try await engine.createConversation(
            with: ConversationConfig(samplerConfig: sampler)
        )
        currentConversation = conversation
        let inner = conversation.sendMessageStream(Message(prompt))

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
            }
        }
    }

    /// 取消当前生成（翻译页「停止」按钮）。
    func cancelGeneration() {
        try? currentConversation?.cancel()
    }
}
