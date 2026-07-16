import SwiftData
import SwiftUI

/// 后台下载会话的系统回调挂点：App 被杀后下载完成，系统拉起 App 在后台收尾。
final class AppDelegate: NSObject, UIApplicationDelegate {
    static var backgroundSessionCompletion: (() -> Void)?

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        Self.backgroundSessionCompletion = completionHandler
        _ = ModelDownloader.shared // 重建同 identifier 会话，接管事件投递
    }
}

/// 译人 iOS 入口：品牌启动页（6s 可跳过）→ 主界面；SwiftData 容器 + 主题。
@main
struct YirenApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @AppStorage("themeMode") private var themeMode = "system"
    @State private var splashDone = false

    var body: some Scene {
        WindowGroup {
            ZStack {
                if splashDone {
                    RootView()
                        .transition(.opacity)
                } else {
                    SplashView(onFinished: {
                        withAnimation(.easeInOut(duration: 0.45)) { splashDone = true }
                    })
                    .transition(.opacity)
                }
            }
            .preferredColorScheme(
                themeMode == "light" ? .light : themeMode == "dark" ? .dark : nil
            )
            .task {
                #if DEBUG
                runTranslateSmokeIfRequested()
                #endif
            }
        }
        .modelContainer(DataStore.container)
    }
}
