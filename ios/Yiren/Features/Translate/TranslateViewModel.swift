import Foundation
import SwiftData

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
    @Published var isRecording = false
    @Published var isTranscribing = false
    @Published var errorMessage: String?

    private let gemma = GemmaService.shared
    private let recorder = AudioRecorder.shared
    private var task: Task<Void, Never>?
    private var zh: Bool { PromptTemplates.isZhUi }

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
                    prompt: PromptTemplates.translate(text, fromZh: fromZh),
                    sampler: Samplers.precise
                )
                for try await delta in stream {
                    if Task.isCancelled { break }
                    output += delta
                }
                isTranslating = false
                // 翻译成功 → 写入历史（同原文+方向去重，只留最新一条）。
                let final = output.trimmingCharacters(in: .whitespacesAndNewlines)
                if !final.isEmpty, !Task.isCancelled {
                    let ctx = DataStore.context
                    let src = text
                    let sl = fromZh ? "ZH" : "EN"
                    let tl = fromZh ? "EN" : "ZH"
                    let dup = FetchDescriptor<TranslationRecord>(
                        predicate: #Predicate { $0.sourceText == src && $0.sourceLang == sl && $0.targetLang == tl }
                    )
                    for old in (try? ctx.fetch(dup)) ?? [] { ctx.delete(old) }
                    ctx.insert(TranslationRecord(
                        sourceText: src, translatedText: final, sourceLang: sl, targetLang: tl
                    ))
                    try? ctx.save()
                }
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

    // MARK: - 语音输入（与 Android 翻译页一致：转写回填输入框，用户确认后再翻译）

    func startVoice() async {
        guard !isRecording, !isTranslating, !isTranscribing else { return }
        guard await recorder.requestPermission() else {
            errorMessage = zh ? "需要麦克风权限" : "Microphone permission required"
            return
        }
        do {
            try recorder.start()
            isRecording = true
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func stopVoiceAndFill() async {
        guard isRecording else { return }
        let pcm = recorder.stop()
        isRecording = false
        guard pcm.count > 16_000 else { // < 0.5s
            errorMessage = zh ? "录音太短，请多说一会儿" : "Recording too short"
            return
        }
        guard AudioRecorder.peak(of: pcm) > 0.01 else {
            errorMessage = zh ? "没采到声音，请检查麦克风" : "No audio captured"
            return
        }
        isTranscribing = true
        loadingModel = true
        defer { isTranscribing = false }

        if case .failure(let f) = await gemma.ensureLoaded() {
            loadingModel = false
            if case .modelMissing = f { isModelMissing = true }
            return
        }
        loadingModel = false
        guard gemma.audioEnabled else {
            errorMessage = zh ? "当前模型未启用语音识别" : "Audio is not enabled"
            return
        }
        do {
            let wav = AudioRecorder.wavData(from: pcm)
            let stream = try await gemma.transcribe(wav: wav, zh: sourceIsZh)
            var acc = ""
            for try await d in stream {
                acc += d
                input = acc.trimmingCharacters(in: .whitespacesAndNewlines)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func clear() {
        stop()
        input = ""
        output = ""
    }
}
