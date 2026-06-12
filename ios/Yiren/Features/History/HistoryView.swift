import SwiftData
import SwiftUI

/// 历史 Tab —— 只存翻译记录（产品决策），对应 Android HistoryScreen。
struct HistoryView: View {
    @Query(sort: \TranslationRecord.createdAt, order: .reverse)
    private var records: [TranslationRecord]
    @Environment(\.modelContext) private var context
    /// 记录 / 生词本 / 单词本 三段；单词本是独立体系（上传+测试）。
    @State private var tab = 0
    @State private var showPractice = false

    private var zh: Bool { PromptTemplates.isZhUi }
    private var starredOnly: Bool { tab == 1 }
    private var items: [TranslationRecord] { starredOnly ? records.filter(\.starred) : records }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $tab) {
                    Text(zh ? "记录" : "All").tag(0)
                    Text(zh ? "生词本" : "Starred").tag(1)
                    Text(zh ? "单词本" : "Word books").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.bottom, 4)

                if tab == 2 {
                    WordBooksSection()
                } else if items.isEmpty {
                    ContentUnavailableView(
                        starredOnly ? (zh ? "还没有收藏的生词" : "No starred words yet")
                                    : (zh ? "还没有翻译记录" : "No translations yet"),
                        systemImage: starredOnly ? "star" : "clock",
                        description: Text(
                            starredOnly ? (zh ? "点历史记录的星标加入生词本" : "Star a history item to add it")
                                        : (zh ? "翻译成功后会自动记录在这里" : "Successful translations appear here")
                        )
                    )
                } else {
                    List {
                        ForEach(items, id: \.persistentModelID) { r in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text("\(r.sourceLang == "ZH" ? "中" : "EN") → \(r.targetLang == "ZH" ? "中" : "EN")")
                                        .font(.caption2.weight(.semibold))
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 2)
                                        .background(Capsule().fill(Color.brandPrimary.opacity(0.14)))
                                        .foregroundStyle(Color.brandPrimary)
                                    Spacer()
                                    Text(r.createdAt.formatted(date: .numeric, time: .shortened))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                    // 收藏进生词本（练习素材）。
                                    Button {
                                        r.starred.toggle()
                                        try? context.save()
                                    } label: {
                                        Image(systemName: r.starred ? "star.fill" : "star")
                                            .foregroundStyle(r.starred ? Color.brandPrimary : Color.secondary)
                                    }
                                    .buttonStyle(.borderless)
                                }
                                Text(r.sourceText)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(3)
                                Text(r.translatedText)
                                    .font(.body)
                                    .textSelection(.enabled)
                            }
                            .padding(.vertical, 4)
                            .swipeActions {
                                Button(role: .destructive) {
                                    context.delete(r)
                                    try? context.save()
                                } label: { Label(zh ? "删除" : "Delete", systemImage: "trash") }
                                Button {
                                    UIPasteboard.general.string = r.translatedText
                                } label: { Label(zh ? "复制" : "Copy", systemImage: "doc.on.doc") }
                            }
                        }
                    }
                }
            }
            .navigationTitle(zh ? "历史" : "History")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if starredOnly && !items.isEmpty {
                        Button(zh ? "开始练习" : "Practice") { showPractice = true }
                    }
                    if !records.isEmpty && tab == 0 {
                        Button(zh ? "清空" : "Clear", role: .destructive) {
                            for r in records { context.delete(r) }
                            try? context.save()
                        }
                    }
                }
            }
            .sheet(isPresented: $showPractice) {
                PracticeView(items: records.filter(\.starred))
            }
        }
    }
}

/// 抽卡练习：正面原文 → 点击翻面看译文 → 「认识」移出本轮 /「再练」放回队尾。
private struct PracticeView: View {
    let items: [TranslationRecord]
    @State private var queue: [TranslationRecord] = []
    @State private var revealed = false
    @Environment(\.dismiss) private var dismiss

    private var zh: Bool { PromptTemplates.isZhUi }

    var body: some View {
        VStack(spacing: 20) {
            if queue.isEmpty {
                Spacer()
                Text("🎉").font(.system(size: 56))
                Text(zh ? "本轮全部记住了！" : "All done for this round!")
                    .font(.title3.weight(.semibold))
                Button(zh ? "完成" : "Done") { dismiss() }
                    .buttonStyle(.borderedProminent)
                    .tint(.brandPrimary)
                Spacer()
            } else {
                let card = queue[0]
                Text(zh ? "第 \(items.count - queue.count + 1) / \(items.count) 张"
                        : "Card \(items.count - queue.count + 1) / \(items.count)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 24)
                Spacer()
                VStack(spacing: 14) {
                    Text(card.sourceText)
                        .font(.title2.weight(.semibold))
                        .multilineTextAlignment(.center)
                    if revealed {
                        Text(card.translatedText)
                            .font(.title3)
                            .foregroundStyle(Color.brandPrimary)
                            .multilineTextAlignment(.center)
                    } else {
                        Text(zh ? "点击卡片看译文" : "Tap card to reveal")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(28)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
                .padding(.horizontal, 24)
                .onTapGesture { revealed.toggle() }
                Spacer()
                HStack(spacing: 16) {
                    Button(zh ? "再练" : "Again") {
                        let c = queue.removeFirst()
                        queue.append(c)
                        revealed = false
                    }
                    .buttonStyle(.bordered)
                    Button(zh ? "认识" : "Got it") {
                        queue.removeFirst()
                        revealed = false
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.brandPrimary)
                }
                .padding(.bottom, 32)
            }
        }
        .onAppear { queue = items.shuffled() }
        .presentationDetents([.large])
    }
}
