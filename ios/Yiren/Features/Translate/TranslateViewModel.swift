import Foundation

/// 翻译页状态机 —— 对应 Android `TranslateViewModel.kt`（V1 无语音/历史）。
@MainActor
final class TranslateViewModel: ObservableObject {
    @Published var input = ""
    @Published var output = ""
    @Published var sourceIsZh = true
    @Published var isTranslating = false
    /// 引擎把模型读进内存的阶段（首载很慢，必须有提示防误判卡死）。
    @Published var loadingModel = false
    /// 还没下载模型 —— 顶部内联横幅（不是报错）。
    @Published var isModelMissing = false
    @Published var errorMessage: String?

    private let gemma = GemmaService.shared
    private var task: Task<Void, Never>?

    /// 页面出现/模型页返回时调用：预热引擎并刷新缺失横幅。
    func refreshModel() {
        Task {
            switch await gemma.ensureLoaded() {
            case .success: isModelMissing = false
            case .failure(let f):
                if case .modelMissing = f { isModelMissing = true }
            }
        }
    }

    func swapDirection() {
        sourceIsZh.toggle()
        swap(&input, &output)
    }

    func translate() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isTranslating else { return }
        task?.cancel()
        isTranslating = true
        loadingModel = true
        output = ""
        errorMessage = nil

        let fromZh = sourceIsZh
        task = Task {
            switch await gemma.ensureLoaded() {
            case .failure(let f):
                loadingModel = false
                isTranslating = false
                if case .modelMissing = f {
                    isModelMissing = true
                } else if case .initFailed(let msg) = f {
                    errorMessage = "模型加载失败：\(msg)"
                }
                return
            case .success:
                loadingModel = false
                isModelMissing = false
            }

            do {
                let stream = try await gemma.generateStream(
                    prompt: PromptTemplates.translate(text, fromZh: fromZh)
                )
                for try await delta in stream {
                    if Task.isCancelled { break }
                    output += delta
                }
                isTranslating = false
            } catch {
                if !Task.isCancelled {
                    errorMessage = error.localizedDescription
                }
                isTranslating = false
            }
        }
    }

    func stop() {
        gemma.cancelGeneration()
        task?.cancel()
        isTranslating = false
        loadingModel = false
    }

    func clear() {
        stop()
        input = ""
        output = ""
    }
}
