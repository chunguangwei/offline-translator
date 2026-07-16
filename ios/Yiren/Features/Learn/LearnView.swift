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
    @State private var todayDue = 0
    @State private var hardCount = 0
    @State private var streak = 0
    @State private var showReview = false
    @State private var showHardReview = false
    @State private var sessionItems: [SrsStore.ReviewItem] = []
    @State private var hardItems: [SrsStore.ReviewItem] = []

    private var zh: Bool { PromptTemplates.isZhUi }
    private var starred: [TranslationRecord] { records.filter(\.starred) }

    private func refresh() {
        let s = SrsStore.summary(context)
        todayDue = s.todayDue
        hardCount = s.hardCount
        streak = s.streak
        if tab == 2 { hardItems = SrsStore.buildHardSession(context) }
    }

    private var headerCard: some View {
        VStack(spacing: 12) {
            HStack {
                Text(zh ? "🔥 连续 \(streak) 天" : "🔥 \(streak)-day streak")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(zh ? "今日到期 \(todayDue) 张" : "\(todayDue) due today")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if todayDue > 0 {
                Button {
                    sessionItems = SrsStore.buildTodaySession(context)
                    showReview = true
                } label: {
                    Text(zh ? "开始复习" : "Start review")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.brandPrimary)
            } else {
                Text(zh ? "今日已清空 🎉" : "All cleared today 🎉")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                headerCard

                Picker("", selection: $tab) {
                    Text(zh ? "生词本" : "Starred").tag(0)
                    Text(zh ? "单词本" : "Word books").tag(1)
                    Text(zh ? "错题本" : "Hard").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.bottom, 4)

                if tab == 1 {
                    WordBooksSection()
                } else if tab == 2 {
                    if hardCount == 0 {
                        ContentUnavailableView(
                            zh ? "还没有难词" : "No hard words yet",
                            systemImage: "checkmark.seal",
                            description: Text(zh ? "继续保持 👍" : "Keep it up 👍")
                        )
                    } else {
                        VStack(spacing: 0) {
                            Button {
                                sessionItems = SrsStore.buildHardSession(context)
                                showHardReview = true
                            } label: {
                                Text(zh ? "只练错题本" : "Review hard words")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(.brandPrimary)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)

                            List {
                                ForEach(hardItems) { item in
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(item.front).font(.subheadline)
                                        Text(item.back)
                                            .font(.body)
                                            .foregroundStyle(Color.brandPrimary)
                                    }
                                }
                            }
                        }
                    }
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
                                    SrsStore.removeCard(context, sourceType: "STARRED", sourceId: r.uid)
                                    refresh()
                                } label: { Label(zh ? "移出" : "Unstar", systemImage: "star.slash") }
                            }
                        }
                    }
                }
            }
            .readingWidth()
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
            .sheet(isPresented: $showReview) {
                SrsReviewView(
                    items: sessionItems,
                    onGrade: { c, ok in SrsStore.grade(context, c, correct: ok) },
                    onFinished: { n in if n > 0 { SrsStore.markStudiedToday() }; refresh() }
                )
            }
            .sheet(isPresented: $showHardReview) {
                SrsReviewView(
                    items: sessionItems,
                    onGrade: { c, ok in SrsStore.grade(context, c, correct: ok) },
                    onFinished: { n in
                        if n > 0 { SrsStore.markStudiedToday() }
                        refresh()
                        hardItems = SrsStore.buildHardSession(context)
                    }
                )
            }
            .onChange(of: tab) { _, t in
                if t == 2 { hardItems = SrsStore.buildHardSession(context) }
            }
            .task { SrsStore.runBackfillIfNeeded(context); refresh() }
        }
    }
}

/// 统一 SRS 复习卡片视图：正面 → 翻面（含 note）→ 认识/不认识落库评分。
struct SrsReviewView: View {
    let items: [SrsStore.ReviewItem]
    let onGrade: (ReviewCard, Bool) -> Void
    let onFinished: (Int) -> Void
    @State private var queue: [SrsStore.ReviewItem] = []
    @State private var revealed = false
    @State private var reviewed = 0
    @State private var finished = false
    @Environment(\.dismiss) private var dismiss
    private var zh: Bool { PromptTemplates.isZhUi }

    var body: some View {
        VStack(spacing: 20) {
            if queue.isEmpty {
                Spacer()
                Text("🎉").font(.system(size: 56))
                Text(zh ? "今日复习 \(reviewed) 张" : "Reviewed \(reviewed) today")
                    .font(.title3.weight(.semibold))
                Button(zh ? "完成" : "Done") { dismiss() }
                    .buttonStyle(.borderedProminent).tint(.brandPrimary)
                Spacer()
            } else {
                let item = queue[0]
                Text(zh ? "剩 \(queue.count) 张" : "\(queue.count) left")
                    .font(.caption).foregroundStyle(.secondary).padding(.top, 24)
                Spacer()
                VStack(spacing: 14) {
                    Text(item.front).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                    if revealed {
                        Text(item.back).font(.title3).foregroundStyle(Color.brandPrimary).multilineTextAlignment(.center)
                        if !item.note.isEmpty {
                            Text(item.note).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        }
                    } else {
                        Text(zh ? "点击卡片看答案" : "Tap to reveal").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity).padding(28)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
                .padding(.horizontal, 24)
                .onTapGesture { revealed.toggle() }
                Spacer()
                HStack(spacing: 16) {
                    Button(zh ? "不认识" : "Don't know") {
                        onGrade(item.card, false); reviewed += 1
                        let c = queue.removeFirst(); queue.append(c); revealed = false
                    }.buttonStyle(.bordered)
                    Button(zh ? "认识" : "Got it") {
                        onGrade(item.card, true); reviewed += 1
                        queue.removeFirst(); revealed = false
                    }.buttonStyle(.borderedProminent).tint(.brandPrimary)
                }.padding(.bottom, 32)
            }
        }
        .onAppear { queue = items }
        .onChange(of: queue.isEmpty) { _, empty in
            if empty && !finished { finished = true; onFinished(reviewed) }
        }
    }
}

/// 生词本抽卡练习（自 HistoryView 迁来）：正面原文 → 翻面译文 → 认识移出/不认识放回队尾。
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
                    Button(zh ? "不认识" : "Don't know") {
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
