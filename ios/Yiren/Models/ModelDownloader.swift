import Foundation

/// 模型下载器 —— 与 Android `ModelDownloader` 同策略：
/// 国内镜像（hf-mirror）优先、官方（huggingface.co）兜底，逐源重试。
/// V1 不做断点续传（Android 端有，iOS 后续补），失败可重新下载。
@MainActor
final class ModelDownloader: NSObject, ObservableObject {

    enum Phase: Equatable {
        case idle
        case downloading(fraction: Double, downloaded: Int64, total: Int64)
        case failed(String)
    }

    /// modelId → 下载状态。
    @Published private(set) var phases: [String: Phase] = [:]

    private var tasks: [String: Task<Void, Never>] = [:]
    private let storage = ModelStorage.shared

    func phase(for model: ModelInfo) -> Phase { phases[model.id] ?? .idle }

    func isDownloading(_ model: ModelInfo) -> Bool {
        if case .downloading = phase(for: model) { return true }
        return false
    }

    func download(_ model: ModelInfo) {
        guard tasks[model.id] == nil else { return }
        phases[model.id] = .downloading(fraction: 0, downloaded: 0, total: model.sizeBytes)
        tasks[model.id] = Task { [weak self] in
            await self?.run(model)
            self?.tasks[model.id] = nil
        }
    }

    func cancel(_ model: ModelInfo) {
        tasks[model.id]?.cancel()
        tasks[model.id] = nil
        phases[model.id] = .idle
    }

    private func run(_ model: ModelInfo) async {
        // 国内镜像优先，官方兜底 —— 与 Android 默认源一致。
        let sources = [model.urlMirror, model.urlHf]
        var lastError = "下载失败"
        for url in sources {
            do {
                try await fetch(model, from: url)
                phases[model.id] = .idle
                return
            } catch is CancellationError {
                phases[model.id] = .idle
                return
            } catch {
                lastError = error.localizedDescription
                // 换下一个源重试。
            }
        }
        phases[model.id] = .failed(lastError)
    }

    private func fetch(_ model: ModelInfo, from url: URL) async throws {
        let dest = storage.fileURL(for: model)
        let tmp = dest.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: tmp)

        let (bytes, response) = try await URLSession.shared.bytes(from: url)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let total = http.expectedContentLength > 0 ? http.expectedContentLength : model.sizeBytes

        FileManager.default.createFile(atPath: tmp.path, contents: nil)
        let handle = try FileHandle(forWritingTo: tmp)
        defer { try? handle.close() }

        var buffer = Data()
        buffer.reserveCapacity(1 << 20)
        var downloaded: Int64 = 0
        var lastReport = Date.distantPast

        for try await byte in bytes {
            buffer.append(byte)
            if buffer.count >= 1 << 20 { // 1MB 批量落盘，避免频繁 IO
                try handle.write(contentsOf: buffer)
                downloaded += Int64(buffer.count)
                buffer.removeAll(keepingCapacity: true)
                try Task.checkCancellation()
                // 进度上报节流到 ~4 次/秒，别刷爆主线程。
                if Date().timeIntervalSince(lastReport) > 0.25 {
                    lastReport = Date()
                    let d = downloaded
                    phases[model.id] = .downloading(
                        fraction: Double(d) / Double(total), downloaded: d, total: total
                    )
                }
            }
        }
        if !buffer.isEmpty {
            try handle.write(contentsOf: buffer)
            downloaded += Int64(buffer.count)
        }
        try handle.close()

        // 体积防呆：差太多视为坏档（防 CDN 截断 / 错误页面落盘）。
        guard downloaded > model.sizeBytes / 2 else { throw URLError(.cannotParseResponse) }
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)

        // 下载完成自动激活 —— 与 Android 行为一致。
        storage.activeModelId = model.id
    }
}
