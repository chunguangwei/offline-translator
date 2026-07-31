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
        if let raw = UserDefaults.standard.string(forKey: "modelSourcePref"),
           let pref = ModelSourcePref(rawValue: raw) {
            return pref
        }
        return defaultForRegion
    }

    /// 未手动设置时按地区给默认：中国大陆 → 国内优先（ModelScope/阿里云）；
    /// 其它地区 → 官方优先（HF CDN 海外更快）。修复海外用户默认走国内源下载极慢的问题。
    static var defaultForRegion: ModelSourcePref {
        Locale.current.region?.identifier == "CN" ? .cnFirst : .official
    }
}

/// 模型下载器（后台会话 + 分块并行版）。
///
/// 源顺序与 Android 一致：国内 ModelScope(阿里云) 优先 / 海外官方优先 → 逐个兜底。
///
/// 三个平台坑的解法都在这里：
/// 1. **锁屏/切后台下载中断**：用 `URLSessionConfiguration.background`，
///    下载由系统 nsurlsessiond 进程执行——锁屏、挂起、甚至 App 被杀都继续；
///    重开 App 重建同 identifier 会话自动接管在途任务。
/// 2. **吞吐**：URLSessionDownloadTask 系统直写磁盘（AsyncBytes 逐字节迭代会卡死
///    在个位数 MB/s）。
/// 3. **单连接限速**：整文件拆成 [chunkCount] 段 Range 并发下载（App Store 审核网络 /
///    海外单连接常跑不到 9MB/s，2.6GB 超 5 分钟被拒过）。每段独立断点续传，
///    part 文件是源无关的字节区间，换源不丢进度；全部段落盘后按序拼接校验。
///
/// 失败重试：网络抖动产生的 resumeData 同段同源续传（最多 2 次），仍失败换下一源
/// （从 part 已落盘的位置继续）；用户取消时各段 resumeData 落盘，下次点下载接着下。
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

    /// 分块并发数。6 段足够把单连接限速摊薄到 5 分钟线以内，又不至于触发服务端限流。
    private let chunkCount = 6

    private let storage = ModelStorage.shared
    private var session: URLSession!
    private let proxy = SessionDelegateProxy()
    /// 用户主动取消的 modelId（didCompleteWithError 据此区分取消与失败）。
    private var cancelRequested: Set<String> = []
    /// 每段已完成字节（磁盘 part + 在途）：modelId → (chunkIdx → bytes)。
    private var chunkBytes: [String: [Int: Int64]] = [:]
    /// 任务启动时该段的磁盘基准："modelId|chunkIdx" → part 当时大小。
    /// part 只在整段下完时追加写入，所以在途任务的基准就是当前 part 大小。
    private var taskBase: [String: Int64] = [:]
    /// 同段同源 resume 重试计数（"modelId|srcIdx|chunkIdx" → 次数）。
    private var retries: [String: Int] = [:]
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
                guard let self else { return }
                for t in tasks where t.state == .running || t.state == .suspended {
                    guard let modelId = Self.modelId(of: t),
                          let chunk = Self.chunkIndex(of: t),
                          let model = ModelRegistry.byId(modelId) else { continue }
                    let base = self.partSize(model, chunk)
                    self.taskBase["\(modelId)|\(chunk)"] = base
                    var per = self.chunkBytes[modelId] ?? [:]
                    per[chunk] = base + t.countOfBytesReceived
                    self.chunkBytes[modelId] = per
                    self.reportProgress(model)
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
        retries = retries.filter { !$0.key.hasPrefix("\(model.id)|") }
        // 从磁盘 part 恢复各段进度（上次取消/失败留下的不丢）。
        var per: [Int: Int64] = [:]
        for i in 0..<chunkCount { per[i] = partSize(model, i) }
        chunkBytes[model.id] = per
        reportProgress(model)
        for i in 0..<chunkCount { startChunk(model: model, sourceIndex: 0, chunk: i) }
    }

    func cancel(_ model: ModelInfo) {
        cancelRequested.insert(model.id)
        phases[model.id] = .idle
        session.getAllTasks { tasks in
            for t in tasks where Self.modelId(of: t) == model.id {
                let chunk = Self.chunkIndex(of: t) ?? 0
                let srcIdx = Self.sourceIndex(of: t) ?? 0
                (t as? URLSessionDownloadTask)?.cancel { [weak self] data in
                    guard let data else { return }
                    Task { @MainActor [weak self] in
                        self?.saveResume(data, for: model, chunk: chunk, sourceIndex: srcIdx)
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

    private func startChunk(model: ModelInfo, sourceIndex: Int, chunk i: Int) {
        let srcs = sources(for: model)
        guard sourceIndex < srcs.count else {
            phases[model.id] = .failed(PromptTemplates.isZhUi ? "所有下载源均失败，请稍后重试" : "All sources failed")
            return
        }
        let expected = expectedChunkSize(model, i)
        let have = partSize(model, i)
        guard have < expected else {
            // 该段已完成（上次留下的 part）：直接汇总，可能触发拼接。
            chunkBytes[model.id, default: [:]][i] = expected
            reportProgress(model)
            finalizeIfComplete(model)
            return
        }
        let task: URLSessionDownloadTask
        if let rd = loadResume(for: model, chunk: i, sourceIndex: sourceIndex) {
            // resumeData 内含原 Range 请求与已下字节，完成后 temp 是整段内容。
            task = session.downloadTask(withResumeData: rd)
        } else {
            var req = URLRequest(url: srcs[sourceIndex])
            let (s, e) = chunkRange(model, i)
            req.setValue("bytes=\(s + have)-\(e)", forHTTPHeaderField: "Range")
            task = session.downloadTask(with: req)
        }
        taskBase["\(model.id)|\(i)"] = have
        task.taskDescription = "\(model.id)|\(sourceIndex)|\(i)"
        task.resume()
    }

    // MARK: - 代理回调入口（proxy 已切回主线程）

    fileprivate func onProgress(modelId: String, chunk: Int, written: Int64) {
        let now = Date()
        guard now.timeIntervalSince(lastReport) > 0.25 else { return }
        lastReport = now
        guard !cancelRequested.contains(modelId),
              let model = ModelRegistry.byId(modelId) else { return }
        let base = taskBase["\(modelId)|\(chunk)"] ?? 0
        chunkBytes[modelId, default: [:]][chunk] = base + written
        reportProgress(model)
    }

    fileprivate func onFinished(modelId: String, sourceIndex: Int, chunk: Int, tempURL: URL, httpStatus: Int) {
        guard let model = ModelRegistry.byId(modelId) else { return }
        defer { try? FileManager.default.removeItem(at: tempURL) }
        let expected = expectedChunkSize(model, chunk)
        let base = taskBase["\(modelId)|\(chunk)"] ?? 0
        // 服务器忽略 Range（要求续传却回 200 整文件）→ 内容不是这段，按失败处理。
        if base > 0 && httpStatus == 200 {
            failChunk(model: model, sourceIndex: sourceIndex, chunk: chunk, resumeData: nil)
            return
        }
        // 追加到 part（base == 0 时直接落为新 part）。
        let part = partFile(model, chunk)
        do {
            if base == 0 {
                try FileManager.default.moveItem(at: tempURL, to: part)
            } else {
                let out = try FileHandle(forWritingTo: part)
                try out.seekToEnd()
                let inData = try Data(contentsOf: tempURL, options: .mappedIfSafe)
                try out.write(contentsOf: inData)
                try out.close()
            }
        } catch {
            failChunk(model: model, sourceIndex: sourceIndex, chunk: chunk, resumeData: nil)
            return
        }
        let now = partSize(model, chunk)
        if now == expected {
            chunkBytes[modelId, default: [:]][chunk] = expected
            clearResume(for: model, chunk: chunk)
            reportProgress(model)
            finalizeIfComplete(model)
        } else if now < expected {
            // 提前结束（截断）：进度不丢，同源从 part 继续（计入重试次数防死循环）。
            chunkBytes[modelId, default: [:]][chunk] = now
            reportProgress(model)
            retrySameSource(model: model, sourceIndex: sourceIndex, chunk: chunk, resumeData: nil)
        } else {
            // 超出预期：数据异常，删掉重下该段。
            try? FileManager.default.removeItem(at: part)
            chunkBytes[modelId, default: [:]][chunk] = 0
            retrySameSource(model: model, sourceIndex: sourceIndex, chunk: chunk, resumeData: nil)
        }
    }

    fileprivate func onFailed(modelId: String, sourceIndex: Int, chunk: Int, resumeData: Data?, isCancelled: Bool, message: String) {
        guard let model = ModelRegistry.byId(modelId) else { return }
        if isCancelled {
            // 用户主动取消：resumeData 已在 cancel() 落盘；静默归位。
            if cancelRequested.remove(modelId) != nil { phases[modelId] = .idle }
            return
        }
        failChunk(model: model, sourceIndex: sourceIndex, chunk: chunk, resumeData: resumeData, message: message)
    }

    /// 段失败：优先同源续传（最多 2 次），仍失败换下一源（part 是源无关字节区间，不丢进度）。
    private func failChunk(model: ModelInfo, sourceIndex: Int, chunk: Int, resumeData: Data?, message: String = "") {
        if let resumeData {
            saveResume(resumeData, for: model, chunk: chunk, sourceIndex: sourceIndex)
        }
        if resumeData != nil && retrySameSource(model: model, sourceIndex: sourceIndex, chunk: chunk, resumeData: resumeData) {
            return
        }
        // 换下一个源（resumeData 只对同 URL 有效，清掉；part 保留）。
        clearResume(for: model, chunk: chunk)
        _ = message // 中间源失败信息不打扰用户，最终全失败才提示
        startChunk(model: model, sourceIndex: sourceIndex + 1, chunk: chunk)
    }

    /// 同源续传重试（最多 2 次）。返回是否已发起重试。
    @discardableResult
    private func retrySameSource(model: ModelInfo, sourceIndex: Int, chunk: Int, resumeData: Data?) -> Bool {
        let key = "\(model.id)|\(sourceIndex)|\(chunk)"
        let n = retries[key, default: 0]
        guard n < 2 else { return false }
        retries[key] = n + 1
        startChunk(model: model, sourceIndex: sourceIndex, chunk: chunk)
        return true
    }

    // MARK: - 拼接

    /// 全部段落盘 → 按序拼接为最终模型文件，校验体积后激活。
    private func finalizeIfComplete(_ model: ModelInfo) {
        for i in 0..<chunkCount {
            guard partSize(model, i) == expectedChunkSize(model, i) else { return }
        }
        let dest = storage.fileURL(for: model)
        try? FileManager.default.removeItem(at: dest)
        do {
            FileManager.default.createFile(atPath: dest.path, contents: nil)
            let out = try FileHandle(forWritingTo: dest)
            for i in 0..<chunkCount {
                let data = try Data(contentsOf: partFile(model, i), options: .mappedIfSafe)
                try out.write(contentsOf: data)
            }
            try out.close()
        } catch {
            try? FileManager.default.removeItem(at: dest)
            phases[model.id] = .failed(error.localizedDescription)
            return
        }
        // 体积防呆：防 CDN 截断/错误页面落盘。
        let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
        guard size > model.sizeBytes / 2 else {
            try? FileManager.default.removeItem(at: dest)
            phases[model.id] = .failed(PromptTemplates.isZhUi ? "下载内容异常，请重试" : "Corrupted download")
            return
        }
        for i in 0..<chunkCount {
            try? FileManager.default.removeItem(at: partFile(model, i))
        }
        clearAllResume(for: model)
        chunkBytes[model.id] = nil
        storage.activeModelId = model.id // 下载完成自动激活，与 Android 一致
        phases[model.id] = .idle
    }

    // MARK: - 进度汇总

    private func reportProgress(_ model: ModelInfo) {
        let done = (chunkBytes[model.id] ?? [:]).values.reduce(0, +)
        let total = model.sizeBytes
        phases[model.id] = .downloading(
            fraction: Double(done) / Double(max(total, 1)), downloaded: done, total: total
        )
    }

    // MARK: - 分块与 part/resume 文件

    /// 第 i 段的字节区间（含端点）。
    private func chunkRange(_ model: ModelInfo, _ i: Int) -> (Int64, Int64) {
        let size = model.sizeBytes
        let chunkLen = (size + Int64(chunkCount) - 1) / Int64(chunkCount)
        let start = Int64(i) * chunkLen
        let end = min(start + chunkLen, size) - 1
        return (start, end)
    }

    private func expectedChunkSize(_ model: ModelInfo, _ i: Int) -> Int64 {
        let (s, e) = chunkRange(model, i)
        return e - s + 1
    }

    private func partFile(_ model: ModelInfo, _ i: Int) -> URL {
        storage.modelsDir.appendingPathComponent("\(model.fileName).chunk\(i)")
    }

    private func partSize(_ model: ModelInfo, _ i: Int) -> Int64 {
        let p = partFile(model, i)
        guard FileManager.default.fileExists(atPath: p.path) else { return 0 }
        return (try? FileManager.default.attributesOfItem(atPath: p.path)[.size] as? Int64) ?? 0
    }

    private func resumeFile(for model: ModelInfo, chunk i: Int, sourceIndex: Int) -> URL {
        storage.modelsDir.appendingPathComponent("\(model.fileName).chunk\(i).resume\(sourceIndex)")
    }

    private func saveResume(_ data: Data, for model: ModelInfo, chunk i: Int, sourceIndex: Int) {
        try? data.write(to: resumeFile(for: model, chunk: i, sourceIndex: sourceIndex))
    }

    private func loadResume(for model: ModelInfo, chunk i: Int, sourceIndex: Int) -> Data? {
        let f = resumeFile(for: model, chunk: i, sourceIndex: sourceIndex)
        defer { try? FileManager.default.removeItem(at: f) } // 一次性使用
        return try? Data(contentsOf: f)
    }

    private func clearResume(for model: ModelInfo, chunk i: Int) {
        for s in 0..<3 {
            try? FileManager.default.removeItem(at: resumeFile(for: model, chunk: i, sourceIndex: s))
        }
    }

    private func clearAllResume(for model: ModelInfo) {
        for i in 0..<chunkCount { clearResume(for: model, chunk: i) }
    }

    // MARK: - util

    fileprivate nonisolated static func modelId(of task: URLSessionTask) -> String? {
        task.taskDescription?.split(separator: "|").first.map(String.init)
    }

    fileprivate nonisolated static func sourceIndex(of task: URLSessionTask) -> Int? {
        guard let parts = task.taskDescription?.split(separator: "|"), parts.count == 3 else { return nil }
        return Int(parts[1])
    }

    fileprivate nonisolated static func chunkIndex(of task: URLSessionTask) -> Int? {
        guard let parts = task.taskDescription?.split(separator: "|"), parts.count == 3 else { return nil }
        return Int(parts[2])
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
        guard let modelId = ModelDownloader.modelId(of: downloadTask),
              let chunk = ModelDownloader.chunkIndex(of: downloadTask) else { return }
        Task { @MainActor [weak owner] in
            owner?.onProgress(modelId: modelId, chunk: chunk, written: totalBytesWritten)
        }
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let modelId = ModelDownloader.modelId(of: downloadTask) else { return }
        let srcIdx = ModelDownloader.sourceIndex(of: downloadTask) ?? 0
        let chunk = ModelDownloader.chunkIndex(of: downloadTask) ?? 0
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        // location 在回调返回后即失效，必须同步先挪走。
        let stable = FileManager.default.temporaryDirectory
            .appendingPathComponent("yiren-dl-\(UUID().uuidString)")
        do {
            try FileManager.default.moveItem(at: location, to: stable)
        } catch { return }
        Task { @MainActor [weak owner] in
            owner?.onFinished(modelId: modelId, sourceIndex: srcIdx, chunk: chunk,
                              tempURL: stable, httpStatus: status)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let modelId = ModelDownloader.modelId(of: task) else { return }
        let srcIdx = ModelDownloader.sourceIndex(of: task) ?? 0
        let chunk = ModelDownloader.chunkIndex(of: task) ?? 0
        let ns = error as NSError
        let resumeData = ns.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        let cancelled = ns.code == NSURLErrorCancelled
        Task { @MainActor [weak owner] in
            owner?.onFailed(modelId: modelId, sourceIndex: srcIdx, chunk: chunk,
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
