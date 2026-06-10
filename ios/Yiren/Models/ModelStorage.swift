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

    func delete(_ model: ModelInfo) {
        try? FileManager.default.removeItem(at: fileURL(for: model))
        if activeModelId == model.id { activeModelId = nil }
    }

    var activeModelId: String? {
        get { UserDefaults.standard.string(forKey: activeKey) }
        set { UserDefaults.standard.set(newValue, forKey: activeKey) }
    }
}
