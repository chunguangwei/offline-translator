import SwiftData
import SwiftUI

/// 译人 iOS 入口：品牌启动页（6s 可跳过）→ 主界面；SwiftData 容器 + 主题。
@main
struct YirenApp: App {
    @AppStorage("themeMode") private var themeMode = "system"
    @State private var splashDone = false

    var body: some Scene {
        WindowGroup {
            ZStack {
                if splashDone {
                    RootTabView()
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
