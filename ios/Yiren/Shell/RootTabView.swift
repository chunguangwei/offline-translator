import SwiftUI

/// 底部 4-Tab 壳 —— 与 Android AppShell 一致：翻译 / 问答 / 历史 / 设置。
/// V1 只有「翻译」是真实功能，其余为占位（后续阶段对齐 Android）。
struct RootTabView: View {
    var body: some View {
        TabView {
            TranslateView()
                .tabItem { Label("翻译", systemImage: "character.bubble") }
            PlaceholderView(
                title: "问答",
                note: "离线 AI 问答（文字/语音/图片）即将到来"
            )
            .tabItem { Label("问答", systemImage: "bubble.left.and.bubble.right") }
            PlaceholderView(
                title: "历史",
                note: "翻译历史记录即将到来"
            )
            .tabItem { Label("历史", systemImage: "clock") }
            PlaceholderView(
                title: "设置",
                note: "主题 / 语言 / 模型管理等即将到来",
                showModelsEntry: true
            )
            .tabItem { Label("设置", systemImage: "gearshape") }
        }
        .tint(.brandPrimary)
    }
}

/// 占位页 —— 结构成立、文案诚实，避免空白 Tab。
struct PlaceholderView: View {
    let title: String
    let note: String
    var showModelsEntry = false

    @State private var showModels = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Image(systemName: "hammer")
                    .font(.system(size: 36))
                    .foregroundStyle(Color.brandSecondary)
                Text(note)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                if showModelsEntry {
                    Button("模型管理") { showModels = true }
                        .buttonStyle(.borderedProminent)
                        .tint(.brandPrimary)
                        .padding(.top, 8)
                }
            }
            .padding(32)
            .navigationTitle(title)
            .sheet(isPresented: $showModels) { ModelsView() }
        }
    }
}
