import Foundation

/// 模型下载器 —— 源顺序与 Android 一致：
/// ModelScope(阿里云 OSS，国内最快) → hf-mirror → 官方。
/// hf-mirror 对 HF Xet 仓库只 308 跳回被墙的 huggingface.co（无加速效果）。
///
/// 实现用系统 `URLSessionDownloadTask`（直写磁盘、系统级缓冲），
/// 而非 `URLSession.AsyncBytes`——后者逐字节迭代，2.6GB 会被 CPU 卡死在
/// 个位数 MB/s（用户真机实测"iOS 不快"的根因）。
/// 断点续传走系统 resumeData（取消/失败时落盘，下次接着下）。
@MainActor
final class ModelDownloader: ObservableObject {

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

    private func resumeFile(_ model: ModelInfo) -> URL {
        storage.modelsDir.appendingPathComponent("\(model.fileName).resume")
    }

    private func run(_ model: ModelInfo) async {
        let sources = [model.urlModelScope, model.urlMirror, model.urlHf]
        var lastError = "下载失败"
        for url in sources {
            do {
                try await fetch(model, from: url)
                try? FileManager.default.removeItem(at: resumeFile(model))
                phases[model.id] = .idle
                return
            } catch is CancellationError {
                phases[model.id] = .idle
                return
            } catch let urlError as URLError where urlError.code == .cancelled {
                phases[model.id] = .idle
                return
            } catch {
                lastError = error.localizedDescription
                // 换下一个源重试（resumeData 只对同 URL 有效，换源前清掉）。
                try? FileManager.default.removeItem(at: resumeFile(model))
            }
        }
        phases[model.id] = .failed(lastError)
    }

    private func fetch(_ model: ModelInfo, from url: URL) async throws {
        let dest = storage.fileURL(for: model)
        let resumePath = resumeFile(model)
        let resumeData = try? Data(contentsOf: resumePath)

        let downloader = FileDownloader { [weak self] written, total in
            Task { @MainActor [weak self] in
                guard let self, self.tasks[model.id] != nil else { return }
                let t = total > 0 ? total : model.sizeBytes
                self.phases[model.id] = .downloading(
                    fraction: Double(written) / Double(t), downloaded: written, total: t
                )
            }
        }

        do {
            let tempURL = try await downloader.download(URLRequest(url: url), resumeData: resumeData)
            // 体积防呆：差太多视为坏档（防 CDN 截断 / 错误页面落盘）。
            let size = (try? FileManager.default.attributesOfItem(atPath: tempURL.path)[.size] as? Int64) ?? 0
            guard size > model.sizeBytes / 2 else {
                try? FileManager.default.removeItem(at: tempURL)
                throw URLError(.cannotParseResponse)
            }
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: tempURL, to: dest)
            // 下载完成自动激活 —— 与 Android 行为一致。
            storage.activeModelId = model.id
        } catch {
            // 取消/失败时把系统 resumeData 落盘，下次接着下。
            if let rd = downloader.takeResumeData() {
                try? rd.write(to: resumePath)
            }
            throw error
        }
    }
}

/// 单文件下载封装：URLSessionDownloadTask + delegate 进度，async/await 出口，
/// 支持 resumeData 续传与取消时产出 resumeData。
private final class FileDownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {

    private let onProgress: (Int64, Int64) -> Void
    private var continuation: CheckedContinuation<URL, Error>?
    private var task: URLSessionDownloadTask?
    private var pendingResumeData: Data?
    private var lastReport = Date.distantPast
    private lazy var session = URLSession(
        configuration: .default, delegate: self, delegateQueue: nil
    )

    init(onProgress: @escaping (Int64, Int64) -> Void) {
        self.onProgress = onProgress
    }

    func takeResumeData() -> Data? {
        defer { pendingResumeData = nil }
        return pendingResumeData
    }

    func download(_ request: URLRequest, resumeData: Data?) async throws -> URL {
        defer { session.finishTasksAndInvalidate() }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { cont in
                continuation = cont
                let t = resumeData.map { session.downloadTask(withResumeData: $0) }
                    ?? session.downloadTask(with: request)
                task = t
                t.resume()
            }
        } onCancel: {
            task?.cancel { [weak self] data in
                self?.pendingResumeData = data
            }
        }
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        // 进度上报节流 ~4 次/秒。
        let now = Date()
        guard now.timeIntervalSince(lastReport) > 0.25 else { return }
        lastReport = now
        onProgress(totalBytesWritten, totalBytesExpectedToWrite)
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // location 在本回调返回后即失效，必须先挪到稳定路径。
        let stable = FileManager.default.temporaryDirectory
            .appendingPathComponent("yiren-dl-\(UUID().uuidString)")
        do {
            try FileManager.default.moveItem(at: location, to: stable)
            continuation?.resume(returning: stable)
        } catch {
            continuation?.resume(throwing: error)
        }
        continuation = nil
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return } // 成功路径已在 didFinishDownloadingTo 处理
        let ns = error as NSError
        if let rd = ns.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            pendingResumeData = rd
        }
        continuation?.resume(throwing: error)
        continuation = nil
    }
}
