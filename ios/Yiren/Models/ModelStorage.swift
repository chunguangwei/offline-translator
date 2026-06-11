import Foundation

/// 模型落盘与激活状态 —— 对应 Android `ModelStorage` + activeModelId 偏好。
/// 模型存 Application Support/models/（不入 iCloud 备份，2.6GB 别坑用户流量）。
final class ModelStorage {
    static let shared = ModelStorage()

    private let activeKey = "activeModelId"

    var modelsDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("models", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            // 大文件目录整体排除 iCloud 备份。
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var mutableDir = dir
            try? mutableDir.setResourceValues(values)
        }
        return dir
    }

    func fileURL(for model: ModelInfo) -> URL {
        modelsDir.appendingPathComponent(model.fileName)
    }

    func isDownloaded(_ model: ModelInfo) -> Bool {
        FileManager.default.fileExists(atPath: fileURL(for: model).path)
    }

    /// App 包内预置的模型（开发期"模型随包"：ios/BundledModels/ 放 .litertlm 即随包安装）。
    /// 包是只读的，引擎直接以只读方式从包内路径加载，缓存写到 [modelsDir]。
    func bundledURL(for model: ModelInfo) -> URL? {
        Bundle.main.url(forResource: model.fileName, withExtension: nil, subdirectory: "BundledModels")
    }

    func isBundled(_ model: ModelInfo) -> Bool { bundledURL(for: model) != nil }

    /// 模型实际可用路径：优先用户下载的副本（可删可换），否则回落包内预置。
    func availableURL(for model: ModelInfo) -> URL? {
        let downloaded = fileURL(for: model)
        if FileManager.default.fileExists(atPath: downloaded.path) { return downloaded }
        return bundledURL(for: model)
    }

    func isAvailable(_ model: ModelInfo) -> Bool { availableURL(for: model) != nil }

    func delete(_ model: ModelInfo) {
        try? FileManager.default.removeItem(at: fileURL(for: model))
        if activeModelId == model.id { activeModelId = nil }
    }

    var activeModelId: String? {
        get { UserDefaults.standard.string(forKey: activeKey) }
        set { UserDefaults.standard.set(newValue, forKey: activeKey) }
    }
}
