import Foundation
import UIKit

/// 模型下载源偏好 —— 对应 Android ModelSource（设置页可选）。
enum ModelSourcePref: String, CaseIterable {
    /// 国内优先：ModelScope(阿里云) → hf-mirror → 官方（默认）。
    case cnFirst = "CN_FIRST"
    /// 官方优先：huggingface.co → ModelScope → hf-mirror。
    case official = "OFFICIAL"
    /// 自定义镜像 base URL（仅用它，不静默兜底）。
    case custom = "CUSTOM"
    /// 仅本地：不联网下载。
    case localOnly = "LOCAL_ONLY"

    static var current: ModelSourcePref {
        ModelSourcePref(rawValue: UserDefaults.standard.string(forKey: "modelSourcePref") ?? "CN_FIRST") ?? .cnFirst
    }
}

/// 模型下载器（后台会话版）。
///
/// 源顺序与 Android 一致：ModelScope(阿里云，国内最快) → hf-mirror → 官方。
///
/// 两个 iOS 平台坑的解法都在这里：
/// 1. **锁屏/切后台下载中断**：用 `URLSessionConfiguration.background`，
///    下载由系统 nsurlsessiond 进程执行——锁屏、挂起、甚至 App 被杀都继续；
///    重开 App 重建同 identifier 会话自动接管在途任务（修"锁屏后重新下载"）。
/// 2. **吞吐**：URLSessionDownloadTask 系统直写磁盘（此前 AsyncBytes 逐字节
///    迭代把 2.6GB 卡死在个位数 MB/s）。
///
/// 失败重试：网络抖动产生的 resumeData 同源续传（最多 2 次），仍失败换下一源；
/// 用户取消产生的 resumeData 落盘，下次点下载接着下。
@MainActor
final class ModelDownloader: NSObject, ObservableObject {
    /// 必须全局单例：background session identifier 全 App 唯一。
    static let shared = ModelDownloader()

    enum Phase: Equatable {
        case idle
        case downloading(fraction: Double, downloaded: Int64, total: Int64)
        case failed(String)
    }

    @Published private(set) var phases: [String: Phase] = [:]

    private let storage = ModelStorage.shared
    private var session: URLSession!
    private let proxy = SessionDelegateProxy()
    /// 用户主动取消的 modelId（didCompleteWithError 据此区分取消与失败）。
    private var cancelRequested: Set<String> = []
    /// 同源 resume 重试计数（"modelId|srcIdx" → 次数）。
    private var resumeRetries: [String: Int] = [:]
    private var lastReport = Date.distantPast

    private override init() {
        super.init()
        proxy.owner = self
        let cfg = URLSessionConfiguration.background(
            withIdentifier: "com.offlinetranslator.yiren.model-download"
        )
        cfg.isDiscretionary = false
        cfg.sessionSendsLaunchEvents = true
        session = URLSession(configuration: cfg, delegate: proxy, delegateQueue: nil)
        // App 启动：接回在途任务，恢复进度展示。
        session.getAllTasks { tasks in
            Task { @MainActor [weak self] in
                for t in tasks where t.state == .running || t.state == .suspended {
                    guard let modelId = Self.modelId(of: t) else { continue }
                    // 后台会话重启后 expected 可能为 -1（未知）→ 回落到清单已知大小，
                    // 否则 total=1 会让进度条瞬间爆表。
                    let expected = t.countOfBytesExpectedToReceive
                    let total = expected > 0 ? expected : (ModelRegistry.byId(modelId)?.sizeBytes ?? 1)
                    self?.phases[modelId] = .downloading(
                        fraction: Double(t.countOfBytesReceived) / Double(total),
                        downloaded: t.countOfBytesReceived, total: total
                    )
                }
            }
        }
    }

    // MARK: - 对外接口

    func phase(for model: ModelInfo) -> Phase { phases[model.id] ?? .idle }

    func isDownloading(_ model: ModelInfo) -> Bool {
        if case .downloading = phase(for: model) { return true }
        return false
    }

    func download(_ model: ModelInfo) {
        guard !isDownloading(model) else { return }
        let zh = PromptTemplates.isZhUi
        guard !sources(for: model).isEmpty else {
            phases[model.id] = .failed(
                ModelSourcePref.current == .localOnly
                    ? (zh ? "已设为「仅本地」模式，请到设置改为其它模型源" : "Local-only mode: change the model source in Settings")
                    : (zh ? "未配置自定义下载地址，请到「设置」填写" : "Custom mirror URL not configured")
            )
            return
        }
        cancelRequested.remove(model.id)
        resumeRetries = resumeRetries.filter { !$0.key.hasPrefix("\(model.id)|") }
        phases[model.id] = .downloading(fraction: 0, downloaded: 0, total: model.sizeBytes)
        startTask(model: model, sourceIndex: 0)
    }

    func cancel(_ model: ModelInfo) {
        cancelRequested.insert(model.id)
        phases[model.id] = .idle
        session.getAllTasks { tasks in
            for t in tasks where Self.modelId(of: t) == model.id {
                (t as? URLSessionDownloadTask)?.cancel { [weak self] data in
                    guard let data else { return }
                    Task { @MainActor [weak self] in
                        self?.saveResume(data, for: model, sourceIndex: Self.sourceIndex(of: t) ?? 0)
                    }
                }
            }
        }
    }

    // MARK: - 任务编排

    private func sources(for model: ModelInfo) -> [URL] {
        switch ModelSourcePref.current {
        case .cnFirst: return [model.urlModelScope, model.urlMirror, model.urlHf]
        case .official: return [model.urlHf, model.urlModelScope, model.urlMirror]
        case .custom:
            let base = (UserDefaults.standard.string(forKey: "customMirrorBase") ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !base.isEmpty else { return [] }
            let s = base.hasSuffix("/") ? base + model.fileName : base + "/" + model.fileName
            return URL(string: s).map { [$0] } ?? []
        case .localOnly: return []
        }
    }

    private func startTask(model: ModelInfo, sourceIndex: Int) {
        let srcs = sources(for: model)
        guard sourceIndex < srcs.count else {
            phases[model.id] = .failed(PromptTemplates.isZhUi ? "所有下载源均失败，请稍后重试" : "All sources failed")
            return
        }
        let url = srcs[sourceIndex]
        let task: URLSessionDownloadTask
        if let rd = loadResume(for: model, sourceIndex: sourceIndex) {
            task = session.downloadTask(withResumeData: rd)
        } else {
            task = session.downloadTask(with: URLRequest(url: url))
        }
        task.taskDescription = "\(model.id)|\(sourceIndex)"
        task.resume()
    }

    // MARK: - 代理回调入口（proxy 已切回主线程）

    fileprivate func onProgress(modelId: String, written: Int64, expected: Int64) {
        let now = Date()
        guard now.timeIntervalSince(lastReport) > 0.25 else { return }
        lastReport = now
        guard !cancelRequested.contains(modelId) else { return }
        let model = ModelRegistry.byId(modelId)
        let total = expected > 0 ? written + max(expected - written, 0) : (model?.sizeBytes ?? 1)
        phases[modelId] = .downloading(
            fraction: Double(written) / Double(max(total, 1)), downloaded: written, total: total
        )
    }

    fileprivate func onFinished(modelId: String, tempURL: URL) {
        guard let model = ModelRegistry.byId(modelId) else { return }
        defer { try? FileManager.default.removeItem(at: tempURL) }
        let size = (try? FileManager.default.attributesOfItem(atPath: tempURL.path)[.size] as? Int64) ?? 0
        // 体积防呆：防 CDN 截断/错误页面落盘。
        guard size > model.sizeBytes / 2 else {
            phases[modelId] = .failed(PromptTemplates.isZhUi ? "下载内容异常，请重试" : "Corrupted download")
            return
        }
        let dest = storage.fileURL(for: model)
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: tempURL, to: dest)
            clearResume(for: model)
            storage.activeModelId = model.id // 下载完成自动激活，与 Android 一致
            phases[modelId] = .idle
        } catch {
            phases[modelId] = .failed(error.localizedDescription)
        }
    }

    fileprivate func onFailed(modelId: String, sourceIndex: Int, resumeData: Data?, isCancelled: Bool, message: String) {
        guard let model = ModelRegistry.byId(modelId) else { return }
        if isCancelled {
            // 用户主动取消：resumeData 已在 cancel() 落盘；静默归位。
            if cancelRequested.remove(modelId) != nil { phases[modelId] = .idle }
            return
        }
        if let resumeData {
            saveResume(resumeData, for: model, sourceIndex: sourceIndex)
            // 网络抖动 → 同源续传重试（最多 2 次），不浪费已下载的部分。
            let key = "\(modelId)|\(sourceIndex)"
            let n = resumeRetries[key, default: 0]
            if n < 2 {
                resumeRetries[key] = n + 1
                startTask(model: model, sourceIndex: sourceIndex)
                return
            }
        }
        // 换下一个源（resumeData 只对同 URL 有效，清掉）。
        clearResume(for: model)
        _ = message // 中间源失败信息不打扰用户，最终全失败才提示
        startTask(model: model, sourceIndex: sourceIndex + 1)
    }

    // MARK: - resumeData 持久化（key 带源序号，换源不串）

    private func resumeFile(for model: ModelInfo, sourceIndex: Int) -> URL {
        storage.modelsDir.appendingPathComponent("\(model.fileName).resume\(sourceIndex)")
    }

    private func saveResume(_ data: Data, for model: ModelInfo, sourceIndex: Int) {
        try? data.write(to: resumeFile(for: model, sourceIndex: sourceIndex))
    }

    private func loadResume(for model: ModelInfo, sourceIndex: Int) -> Data? {
        let f = resumeFile(for: model, sourceIndex: sourceIndex)
        defer { try? FileManager.default.removeItem(at: f) } // 一次性使用
        return try? Data(contentsOf: f)
    }

    private func clearResume(for model: ModelInfo) {
        for i in 0..<3 {
            try? FileManager.default.removeItem(at: resumeFile(for: model, sourceIndex: i))
        }
    }

    // MARK: - util

    fileprivate nonisolated static func modelId(of task: URLSessionTask) -> String? {
        task.taskDescription?.split(separator: "|").first.map(String.init)
    }

    fileprivate nonisolated static func sourceIndex(of task: URLSessionTask) -> Int? {
        guard let parts = task.taskDescription?.split(separator: "|"), parts.count == 2 else { return nil }
        return Int(parts[1])
    }
}

/// 后台会话代理：回调在后台队列，统一切主线程交给 ModelDownloader。
private final class SessionDelegateProxy: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    weak var owner: ModelDownloader?

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let modelId = ModelDownloader.modelId(of: downloadTask) else { return }
        Task { @MainActor [weak owner] in
            owner?.onProgress(modelId: modelId, written: totalBytesWritten, expected: totalBytesExpectedToWrite)
        }
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let modelId = ModelDownloader.modelId(of: downloadTask) else { return }
        // location 在回调返回后即失效，必须同步先挪走。
        let stable = FileManager.default.temporaryDirectory
            .appendingPathComponent("yiren-dl-\(UUID().uuidString)")
        do {
            try FileManager.default.moveItem(at: location, to: stable)
        } catch { return }
        Task { @MainActor [weak owner] in
            owner?.onFinished(modelId: modelId, tempURL: stable)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let modelId = ModelDownloader.modelId(of: task) else { return }
        let srcIdx = ModelDownloader.sourceIndex(of: task) ?? 0
        let ns = error as NSError
        let resumeData = ns.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        let cancelled = ns.code == NSURLErrorCancelled
        Task { @MainActor [weak owner] in
            owner?.onFailed(modelId: modelId, sourceIndex: srcIdx,
                            resumeData: resumeData, isCancelled: cancelled,
                            message: error.localizedDescription)
        }
    }

    /// 后台事件投递完毕 → 调用系统给的 completion（App 被后台唤醒收尾用）。
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            AppDelegate.backgroundSessionCompletion?()
            AppDelegate.backgroundSessionCompletion = nil
        }
    }
}
