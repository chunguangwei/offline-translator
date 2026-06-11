import Foundation
import UIKit

/// 远程启动图 —— 与 Android SplashViewModel 同一份 `branding/splash/splash.json` 契约：
/// `{ "enabled": true, "imageUrl": "https://…" }`，改仓库文件即可换图、无需发版。
/// 本次启动用上次缓存（绝不等网络），后台静默刷新供下次启动；离线/关闭回内置动画。
@MainActor
final class SplashRemoteStore: ObservableObject {
    static let shared = SplashRemoteStore()

    struct Config: Decodable {
        var enabled: Bool = false
        var imageUrl: String = ""
    }

    @Published private(set) var cachedImage: UIImage?

    private var dir: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("splash", isDirectory: true)
    }
    private var imageFile: URL { dir.appendingPathComponent("splash_image") }
    private var urlFile: URL { dir.appendingPathComponent("splash_image.url") }

    // jsDelivr CDN 国内可达性好，优先；raw.githubusercontent 兜底。
    private let configUrls = [
        "https://cdn.jsdelivr.net/gh/chunguangwei/offline-translator@main/branding/splash/splash.json",
        "https://raw.githubusercontent.com/chunguangwei/offline-translator/main/branding/splash/splash.json",
    ]

    private init() {
        if let data = try? Data(contentsOf: imageFile), let img = UIImage(data: data) {
            cachedImage = img
        }
        Task.detached(priority: .background) { [weak self] in
            await self?.refresh()
        }
    }

    private func refresh() async {
        var cfg: Config?
        for u in configUrls {
            guard let url = URL(string: u) else { continue }
            if let (data, resp) = try? await URLSession.shared.data(from: url),
               (resp as? HTTPURLResponse)?.statusCode == 200,
               let parsed = try? JSONDecoder().decode(Config.self, from: data) {
                cfg = parsed
                break
            }
        }
        guard let cfg else { return }
        if !cfg.enabled || cfg.imageUrl.isEmpty {
            try? FileManager.default.removeItem(at: imageFile)
            try? FileManager.default.removeItem(at: urlFile)
            return
        }
        let cachedUrl = try? String(contentsOf: urlFile, encoding: .utf8)
        if cachedUrl == cfg.imageUrl, FileManager.default.fileExists(atPath: imageFile.path) { return }
        guard let imgUrl = URL(string: cfg.imageUrl),
              let (data, resp) = try? await URLSession.shared.data(from: imgUrl),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              UIImage(data: data) != nil else { return }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? data.write(to: imageFile)
        try? cfg.imageUrl.write(to: urlFile, atomically: true, encoding: .utf8)
        // 下次启动生效（不打断本次展示）。
    }
}
