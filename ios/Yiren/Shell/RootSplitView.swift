import SwiftUI

/// iPad Regular 尺寸下的两栏 Shell：侧栏 5 项 + Detail。
/// - iPhone / iPad Slide Over(Compact) 由 `RootView` 走 `RootTabView`。
/// - Detail 复用现有 5 个页面，各自 `NavigationStack` 作为二级导航栈。
struct RootSplitView: View {
    private enum Section: Hashable, CaseIterable {
        case translate, chat, learn, history, settings
    }

    @State private var selection: Section? = Self.initialSelection()

    private static func initialSelection() -> Section {
        #if DEBUG
        switch UserDefaults.standard.string(forKey: "startTab") {
        case "chat": return .chat
        case "learn": return .learn
        case "history": return .history
        case "settings": return .settings
        default: return .translate
        }
        #else
        return .translate
        #endif
    }
    private var zh: Bool { PromptTemplates.isZhUi }

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                item(.translate, title: zh ? "翻译" : "Translate", icon: "character.bubble")
                item(.chat, title: zh ? "问答" : "Q&A", icon: "bubble.left.and.bubble.right")
                item(.learn, title: zh ? "学习" : "Learn", icon: "graduationcap")
                item(.history, title: zh ? "历史" : "History", icon: "clock")
                item(.settings, title: zh ? "设置" : "Settings", icon: "gearshape")
            }
            .navigationTitle("Yiren")
            .listStyle(.sidebar)
        } detail: {
            switch selection ?? .translate {
            case .translate: TranslateView()
            case .chat:      ChatView()
            case .learn:     LearnView()
            case .history:   HistoryView()
            case .settings:  SettingsView()
            }
        }
        .navigationSplitViewStyle(.balanced)
        .tint(.brandPrimary)
    }

    private func item(_ section: Section, title: String, icon: String) -> some View {
        NavigationLink(value: section) { Label(title, systemImage: icon) }
    }
}
