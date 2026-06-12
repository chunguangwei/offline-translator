import SwiftData
import SwiftUI
import UIKit

/// 学习 Tab：生词本（翻译记录收藏的词，抽卡练习）+ 单词本（上传建库+测试）。
/// 收藏动作在「历史」页（来源处），学习入口统一在这里。
struct LearnView: View {
    @Query(sort: \TranslationRecord.createdAt, order: .reverse)
    private var records: [TranslationRecord]
    @Environment(\.modelContext) private var context
    @State private var tab = 0
    @State private var showPractice = false

    private var zh: Bool { PromptTemplates.isZhUi }
    private var starred: [TranslationRecord] { records.filter(\.starred) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $tab) {
                    Text(zh ? "生词本" : "Starred").tag(0)
                    Text(zh ? "单词本" : "Word books").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.bottom, 4)

                if tab == 1 {
                    WordBooksSection()
                } else if starred.isEmpty {
                    ContentUnavailableView(
                        zh ? "还没有收藏的生词" : "No starred words yet",
                        systemImage: "star",
                        description: Text(zh ? "在「历史」页点星标加入生词本" : "Star a history item to add it")
                    )
                } else {
                    List {
                        ForEach(starred, id: \.persistentModelID) { r in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(r.sourceText).font(.subheadline)
                                Text(r.translatedText)
                                    .font(.body)
                                    .foregroundStyle(Color.brandPrimary)
                            }
                            .swipeActions {
                                Button {
                                    r.starred = false // 移出生词本
                                    try? context.save()
                                } label: { Label(zh ? "移出" : "Unstar", systemImage: "star.slash") }
                            }
                        }
                    }
                }
            }
            .navigationTitle(zh ? "学习" : "Learn")
            .toolbar {
                if tab == 0 && !starred.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(zh ? "开始练习" : "Practice") { showPractice = true }
                    }
                }
            }
            .sheet(isPresented: $showPractice) {
                StarredPracticeView(items: starred)
            }
        }
    }
}

/// 生词本抽卡练习（自 HistoryView 迁来）：正面原文 → 翻面译文 → 认识移出/再练放回队尾。
struct StarredPracticeView: View {
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
    }
}
