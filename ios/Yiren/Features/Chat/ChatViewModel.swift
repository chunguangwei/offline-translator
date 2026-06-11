import Foundation
import SwiftData
import UIKit

/// 问答状态机 —— 对应 Android `ChatViewModel.kt`，全部产品决策对齐：
/// 上下文=摘要+全部未压缩双方消息；压缩 >20 条或 >3000 字（留 6 条原文）；
/// 图片发送即清预览、窗口内追问重喂、压缩后停喂；语音=转写回填输入框；
/// 停止保留已生成部分并置顶会话。
@MainActor
final class ChatViewModel: ObservableObject {

    // 阈值常量收口（与 Android companion 一致，调优只改这里）。
    private static let compressAfterMessages = 20
    private static let contextCharBudget = 3000
    private static let keepRawAfterCompress = 6

    @Published var sessionId: String?
    /// 当前会话消息缓存 —— 流式期间 body 每 token 重渲染，不能每次都查 SwiftData，
    /// 只在数据真正变更（开会话/发送/落库/删除）时 reload。
    @Published private(set) var messages: [ChatMessage] = []
    @Published var streamingContent = ""
    @Published var isGenerating = false
    @Published var isRecording = false
    @Published var isTranscribing = false
    /// 待发送的图片附件（发送即清，与主流 IM 一致）。
    @Published var attachedImage: UIImage?
    @Published var errorMessage: String?

    private let gemma = GemmaService.shared
    private let recorder = AudioRecorder.shared
    private var generateTask: Task<Void, Never>?
    private var context: ModelContext { DataStore.context }

    // MARK: - 会话管理

    func sessions() -> [ChatSession] {
        let d = FetchDescriptor<ChatSession>(sortBy: [SortDescriptor(\.updatedAt, order: .reverse)])
        return (try? context.fetch(d)) ?? []
    }

    func messages(in sid: String) -> [ChatMessage] {
        var d = FetchDescriptor<ChatMessage>(
            predicate: #Predicate { $0.sessionId == sid },
            sortBy: [SortDescriptor(\.createdAt, order: .forward)]
        )
        d.includePendingChanges = true
        return (try? context.fetch(d)) ?? []
    }

    /// 数据变更后刷新消息缓存。
    private func reloadMessages() {
        messages = sessionId.map { messages(in: $0) } ?? []
    }

    func openSession(_ id: String) {
        guard !isGenerating else { return }
        sessionId = id
        streamingContent = ""
        errorMessage = nil
        attachedImage = nil
        reloadMessages()
    }

    func startNewSession() {
        let s = ChatSession(title: PromptTemplates.isZhUi ? "新会话" : "New chat",
                            modelId: gemma.activeModel?.id ?? "")
        context.insert(s)
        try? context.save()
        openSession(s.id)
    }

    func deleteSession(_ id: String) {
        // 先清掉该会话消息引用的本地图片文件，避免孤儿文件累积。
        for m in messages(in: id) {
            if let p = m.imagePath { try? FileManager.default.removeItem(atPath: p) }
            context.delete(m)
        }
        if let s = sessions().first(where: { $0.id == id }) { context.delete(s) }
        try? context.save()
        if sessionId == id {
            sessionId = nil
            streamingContent = ""
            reloadMessages()
        }
    }

    private func session(_ id: String) -> ChatSession? {
        sessions().first { $0.id == id }
    }

    // MARK: - 图片附件

    func attachImage(_ image: UIImage) {
        // 最长边缩到 896（贴近 Gemma 视觉编码原生输入，密集文字多留细节）。
        attachedImage = Self.resize(image, maxSide: 896)
        errorMessage = nil
    }

    func clearImage() { attachedImage = nil }

    private static func resize(_ img: UIImage, maxSide: CGFloat) -> UIImage {
        let m = max(img.size.width, img.size.height)
        guard m > maxSide else { return img }
        let scale = maxSide / m
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        return UIGraphicsImageRenderer(size: size).image { _ in
            img.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    private func saveImage(_ img: UIImage) -> String? {
        guard let data = img.jpegData(compressionQuality: 0.9) else { return nil }
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("chat_images", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
            return url.path
        } catch { return nil }
    }

    // MARK: - 发送

    func send(_ text: String) {
        let content = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let image = attachedImage
        if (content.isEmpty && image == nil) || isGenerating { return }
        // 附件已捕获到局部变量，发送即清预览（图随消息进气泡）。
        if image != nil { attachedImage = nil }
        if sessionId == nil { startNewSession() }
        guard let sid = sessionId else { return }

        generateTask?.cancel()
        generateTask = Task {
            let sess = session(sid)
            let all = messages(in: sid)
            let unsummarized = Array(all.dropFirst(sess?.summarizedCount ?? 0))

            // 入库用户消息（图先落盘）。
            let imagePath = image.flatMap { saveImage($0) }
            let userMsg = ChatMessage(sessionId: sid, role: "user",
                                      content: content, imagePath: imagePath)
            context.insert(userMsg)
            try? context.save()
            reloadMessages()

            switch await gemma.ensureLoaded() {
            case .failure(let f):
                if case .modelMissing = f {
                    errorMessage = PromptTemplates.isZhUi ? "请先到「设置 → 模型管理」下载模型" : "Download a model in Settings first"
                } else if case .initFailed(let m) = f {
                    errorMessage = m
                }
                return
            case .success:
                break
            }
            if image != nil && !gemma.visionEnabled {
                errorMessage = PromptTemplates.isZhUi ? "当前模型未启用图像识别" : "Vision is not enabled for this model"
                return
            }

            // 历史 = 摘要桥接 + 全部未压缩双方消息（超字数预算从最旧裁剪兜底）。
            var history: [Message] = []
            if let summary = sess?.summary, !summary.isEmpty {
                let bridge = PromptTemplates.summaryBridge(summary)
                history.append(Message(bridge.user, role: .user))
                history.append(Message(bridge.assistant, role: .model))
            }
            var turns = unsummarized.map { m -> (role: Role, text: String) in
                let role: Role = m.role == "user" ? .user : .model
                let text = m.imagePath != nil ? PromptTemplates.historyImageNote(m.content) : m.content
                return (role, text)
            }
            var total = turns.reduce(0) { $0 + $1.text.count }
            while turns.count > 1 && total > Self.contextCharBudget {
                total -= turns[0].text.count
                turns.removeFirst()
            }
            history.append(contentsOf: turns.map { Message($0.text, role: $0.role) })

            // 当前用户消息：新图直接带；没新图时回看窗口内最近一张已发图重喂
            //（否则"图里第二个字是什么"这类追问模型根本看不到图）。
            var contents: [Content] = []
            var turnText = content
            if let imagePath {
                contents.append(.imageFile(imagePath))
                turnText = PromptTemplates.imageTurn(content, refed: false)
            } else if gemma.visionEnabled,
                      let refed = unsummarized.last(where: { $0.imagePath != nil })?.imagePath,
                      FileManager.default.fileExists(atPath: refed) {
                contents.append(.imageFile(refed))
                turnText = PromptTemplates.imageTurn(content, refed: true)
            }
            contents.append(.text(turnText))

            isGenerating = true
            streamingContent = ""
            errorMessage = nil

            do {
                let role = UserDefaults.standard.string(forKey: "chatRole") ?? "default"
                let stream = try await gemma.chatStream(
                    history: history,
                    user: Message(contents: contents, role: .user),
                    system: PromptTemplates.chatSystem(role: role)
                )
                var acc = ""
                for try await delta in stream {
                    if Task.isCancelled { break }
                    acc += delta
                    streamingContent = acc
                }
                if Task.isCancelled { return }
                context.insert(ChatMessage(sessionId: sid, role: "assistant", content: acc))
                if let sess {
                    sess.title = String(content.isEmpty ? (PromptTemplates.isZhUi ? "🖼 图片" : "🖼 Image") : content).prefix(20).description
                    sess.updatedAt = .now
                    sess.modelId = gemma.activeModel?.id ?? ""
                }
                try? context.save()
                reloadMessages()
                isGenerating = false
                streamingContent = ""
                maybeCompress(sid)
            } catch {
                if !Task.isCancelled {
                    errorMessage = error.localizedDescription
                }
                isGenerating = false
                streamingContent = ""
            }
        }
    }

    /// 暂停：把已流出的部分作为助手回复落库，并把会话顶到列表最前。
    func stop() {
        gemma.cancelGeneration()
        generateTask?.cancel()
        let partial = streamingContent
        if !partial.isEmpty, let sid = sessionId {
            context.insert(ChatMessage(sessionId: sid, role: "assistant", content: partial))
            session(sid)?.updatedAt = .now
            try? context.save()
            reloadMessages()
        }
        isGenerating = false
        streamingContent = ""
    }

    // MARK: - 上下文压缩（回答完成后后台静默；失败下轮再试）

    private func maybeCompress(_ sid: String) {
        Task {
            guard let sess = session(sid) else { return }
            let all = messages(in: sid)
            let unsummarized = Array(all.dropFirst(sess.summarizedCount))
            let totalChars = unsummarized.reduce(0) { $0 + $1.content.count }
            guard unsummarized.count > Self.compressAfterMessages
                || totalChars > Self.contextCharBudget else { return }
            let toCompress = unsummarized.dropLast(Self.keepRawAfterCompress)
            guard !toCompress.isEmpty else { return }
            let zh = PromptTemplates.isZhUi
            let convText = toCompress
                .map { ($0.role == "user" ? (zh ? "用户：" : "User: ") : (zh ? "助手：" : "Assistant: ")) + $0.content }
                .joined(separator: "\n")
            do {
                let stream = try await gemma.generateStream(
                    prompt: PromptTemplates.summarize(conversation: convText, previousSummary: sess.summary),
                    sampler: Samplers.precise
                )
                var acc = ""
                for try await d in stream { acc += d }
                let summary = acc.trimmingCharacters(in: .whitespacesAndNewlines)
                if !summary.isEmpty {
                    sess.summary = summary
                    sess.summarizedCount += toCompress.count
                    try? context.save()
                }
            } catch { /* 静默，下轮再试 */ }
        }
    }

    // MARK: - 语音输入（转写回填输入框，用户确认后再发）

    func startVoice() async {
        guard !isRecording, !isGenerating else { return }
        guard await recorder.requestPermission() else {
            errorMessage = PromptTemplates.isZhUi ? "需要麦克风权限" : "Microphone permission required"
            return
        }
        do {
            try recorder.start()
            isRecording = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// 停止录音并转写，返回文字（回填输入框）。失败返回空串。
    func stopVoiceAndTranscribe() async -> String {
        guard isRecording else { return "" }
        let pcm = recorder.stop()
        isRecording = false
        guard pcm.count > 16_000 else { // <0.5s
            errorMessage = PromptTemplates.isZhUi ? "录音太短，请多说一会儿" : "Recording too short"
            return ""
        }
        guard AudioRecorder.peak(of: pcm) > 0.01 else {
            errorMessage = PromptTemplates.isZhUi ? "没采到声音，请检查麦克风" : "No audio captured"
            return ""
        }
        isTranscribing = true
        defer { isTranscribing = false }

        if case .failure = await gemma.ensureLoaded() {
            errorMessage = PromptTemplates.isZhUi ? "请先到「设置 → 模型管理」下载模型" : "Download a model first"
            return ""
        }
        guard gemma.audioEnabled else {
            errorMessage = PromptTemplates.isZhUi ? "当前模型未启用语音识别" : "Audio is not enabled"
            return ""
        }
        do {
            let wav = AudioRecorder.wavData(from: pcm)
            let stream = try await gemma.transcribe(wav: wav, zh: PromptTemplates.isZhUi)
            var acc = ""
            for try await d in stream { acc += d }
            return acc.trimmingCharacters(in: .whitespacesAndNewlines)
        } catch {
            errorMessage = error.localizedDescription
            return ""
        }
    }
}
