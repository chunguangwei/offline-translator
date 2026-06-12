import SwiftUI

/// 底部 4-Tab 壳 —— 与 Android AppShell 一致：翻译 / 问答 / 历史 / 设置，全部真实。
struct RootTabView: View {
    private var zh: Bool { PromptTemplates.isZhUi }

    var body: some View {
        TabView {
            TranslateView()
                .tabItem { Label(zh ? "翻译" : "Translate", systemImage: "character.bubble") }
            ChatView()
                .tabItem { Label(zh ? "问答" : "Q&A", systemImage: "bubble.left.and.bubble.right") }
            LearnView()
                .tabItem { Label(zh ? "学习" : "Learn", systemImage: "graduationcap") }
            HistoryView()
                .tabItem { Label(zh ? "历史" : "History", systemImage: "clock") }
            SettingsView()
                .tabItem { Label(zh ? "设置" : "Settings", systemImage: "gearshape") }
        }
        .tint(.brandPrimary)
    }
}
