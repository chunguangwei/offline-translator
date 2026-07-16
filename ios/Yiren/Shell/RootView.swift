import SwiftUI

/// 根据 horizontalSizeClass 分流：
/// - Regular（iPad 全屏 / Split 1/2 及以上）→ 两栏 SplitView
/// - Compact（iPhone / iPad Slide Over 1/3）→ 底部 TabView
struct RootView: View {
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        if hSize == .regular {
            RootSplitView()
        } else {
            RootTabView()
        }
    }
}
