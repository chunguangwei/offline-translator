import SwiftUI

/// 译人 iOS 入口。V1=翻译先行（spec：docs/superpowers/specs/2026-06-11-yiren-ios-v1-design.md）。
@main
struct YirenApp: App {
    var body: some Scene {
        WindowGroup {
            RootTabView()
                .task {
                    #if DEBUG
                    runTranslateSmokeIfRequested()
                    #endif
                }
        }
    }
}
