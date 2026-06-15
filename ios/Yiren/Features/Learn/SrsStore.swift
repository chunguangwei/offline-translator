import Foundation
import SwiftData

/// SRS 卡片同步 + 回填辅助（iOS 无 ViewModel 层，视图在数据变更点调用）。
@MainActor
enum SrsStore {
    static let starredDailyNewLimit = 10
    static let hardMissThreshold = 3

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// 已解析正反面的复习项。
    struct ReviewItem: Identifiable {
        let id: String
        let card: ReviewCard
        let front: String
        let back: String
        let note: String
    }

    static func todayEpochDay() -> Int64 {
        Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 / 86_400)
    }

    /// 方向 mixed：按 sourceId 的稳定派生定向（不可用 String.hashValue —— 跨进程不稳定）。
    private static func enFront(_ sourceId: String) -> Bool {
        (sourceId.utf8.reduce(0) { $0 &+ Int($1) }) % 2 == 0
    }

    private static func toItem(_ context: ModelContext, _ card: ReviewCard) -> ReviewItem? {
        let sid = card.sourceId
        if card.sourceType == "WORD_ENTRY" {
            var d = FetchDescriptor<WordEntry>(predicate: #Predicate { $0.uid == sid }); d.fetchLimit = 1
            guard let e = try? context.fetch(d).first else { return nil }
            return enFront(sid)
                ? ReviewItem(id: sid, card: card, front: e.english, back: e.chinese, note: e.note)
                : ReviewItem(id: sid, card: card, front: e.chinese, back: e.english, note: e.note)
        } else {
            var d = FetchDescriptor<TranslationRecord>(predicate: #Predicate { $0.uid == sid }); d.fetchLimit = 1
            guard let r = try? context.fetch(d).first else { return nil }
            return enFront(sid)
                ? ReviewItem(id: sid, card: card, front: r.sourceText, back: r.translatedText, note: "")
                : ReviewItem(id: sid, card: card, front: r.translatedText, back: r.sourceText, note: "")
        }
    }

    /// (todayDue, hardCount, displayStreak) 聚合统计。
    static func summary(_ context: ModelContext) -> (todayDue: Int, hardCount: Int, streak: Int) {
        let now = nowMs()
        let books = (try? context.fetch(FetchDescriptor<WordBook>())) ?? []
        let wordCards = (try? context.fetch(FetchDescriptor<ReviewCard>(
            predicate: #Predicate { $0.sourceType == "WORD_ENTRY" }))) ?? []
        var todayDue = 0
        for book in books {
            let ids = Set(book.entries.map { $0.uid })
            let cards = wordCards.filter { ids.contains($0.sourceId) }
            todayDue += DuePool.select(cards: cards, now: now, newLimit: book.dailyGoal,
                box: { $0.box }, dueAt: { $0.dueAt }, lastReviewedAt: { $0.lastReviewedAt }).count
        }
        let starredCards = (try? context.fetch(FetchDescriptor<ReviewCard>(
            predicate: #Predicate { $0.sourceType == "STARRED" }))) ?? []
        todayDue += DuePool.select(cards: starredCards, now: now, newLimit: starredDailyNewLimit,
            box: { $0.box }, dueAt: { $0.dueAt }, lastReviewedAt: { $0.lastReviewedAt }).count
        let hardCount = ((try? context.fetch(FetchDescriptor<ReviewCard>())) ?? [])
            .filter { $0.missCount >= hardMissThreshold }.count
        let d = UserDefaults.standard
        let streak = StreakLogic.displayStreak(
            lastDay: Int64(d.integer(forKey: "streakLastDay")),
            current: d.integer(forKey: "streakCurrent"),
            today: todayEpochDay())
        return (todayDue, hardCount, streak)
    }

    static func buildTodaySession(_ context: ModelContext) -> [ReviewItem] {
        let now = nowMs()
        let books = (try? context.fetch(FetchDescriptor<WordBook>())) ?? []
        let wordCards = (try? context.fetch(FetchDescriptor<ReviewCard>(
            predicate: #Predicate { $0.sourceType == "WORD_ENTRY" }))) ?? []
        var selected: [ReviewCard] = []
        for book in books {
            let ids = Set(book.entries.map { $0.uid })
            let cards = wordCards.filter { ids.contains($0.sourceId) }
            selected += DuePool.select(cards: cards, now: now, newLimit: book.dailyGoal,
                box: { $0.box }, dueAt: { $0.dueAt }, lastReviewedAt: { $0.lastReviewedAt })
        }
        let starredCards = (try? context.fetch(FetchDescriptor<ReviewCard>(
            predicate: #Predicate { $0.sourceType == "STARRED" }))) ?? []
        selected += DuePool.select(cards: starredCards, now: now, newLimit: starredDailyNewLimit,
            box: { $0.box }, dueAt: { $0.dueAt }, lastReviewedAt: { $0.lastReviewedAt })
        return selected.compactMap { toItem(context, $0) }.shuffled()
    }

    static func buildHardSession(_ context: ModelContext) -> [ReviewItem] {
        let cards = ((try? context.fetch(FetchDescriptor<ReviewCard>())) ?? [])
            .filter { $0.missCount >= hardMissThreshold }
            .sorted { $0.missCount > $1.missCount }
        return cards.compactMap { toItem(context, $0) }
    }

    /// 取本册的全部 WORD_ENTRY 调度卡。
    static func bookCards(_ context: ModelContext, _ book: WordBook) -> [ReviewCard] {
        let ids = Set(book.entries.map { $0.uid })
        let wordCards = (try? context.fetch(FetchDescriptor<ReviewCard>(
            predicate: #Predicate { $0.sourceType == "WORD_ENTRY" }))) ?? []
        return wordCards.filter { ids.contains($0.sourceId) }
    }

    static func buildBookSession(_ context: ModelContext, _ book: WordBook) -> [ReviewItem] {
        let now = nowMs()
        let cards = bookCards(context, book)
        return DuePool.select(cards: cards, now: now, newLimit: book.dailyGoal,
            box: { $0.box }, dueAt: { $0.dueAt }, lastReviewedAt: { $0.lastReviewedAt })
            .compactMap { toItem(context, $0) }.shuffled()
    }

    /// 某单词本统计：(今日到期数, 已掌握数=box≥MAX_BOX, 该词是否已掌握的 uid 集)。
    static func bookStats(_ context: ModelContext, _ book: WordBook) -> (due: Int, mastered: Int, masteredUids: Set<String>) {
        let now = nowMs()
        let cards = bookCards(context, book)
        let due = DuePool.select(cards: cards, now: now, newLimit: book.dailyGoal,
            box: { $0.box }, dueAt: { $0.dueAt }, lastReviewedAt: { $0.lastReviewedAt }).count
        let masteredUids = Set(cards.filter { $0.box >= SrsScheduler.maxBox }.map { $0.sourceId })
        return (due, masteredUids.count, masteredUids)
    }

    /// 评分落库。
    static func grade(_ context: ModelContext, _ card: ReviewCard, correct: Bool) {
        let u = SrsScheduler.schedule(box: card.box, missCount: card.missCount, correct: correct, now: nowMs())
        card.box = u.box; card.dueAt = u.dueAt; card.missCount = u.missCount; card.lastReviewedAt = u.lastReviewedAt
        try? context.save()
    }

    /// 完成≥1张后更新连续天数。
    static func markStudiedToday() {
        let d = UserDefaults.standard
        let s = StreakLogic.onStudied(
            lastDay: Int64(d.integer(forKey: "streakLastDay")),
            current: d.integer(forKey: "streakCurrent"),
            today: todayEpochDay())
        d.set(Int(s.lastStudyDay), forKey: "streakLastDay")
        d.set(s.currentStreak, forKey: "streakCurrent")
        d.set(max(d.integer(forKey: "streakLongest"), s.currentStreak), forKey: "streakLongest")
    }

    /// 建卡（幂等：已存在同 (sourceType, sourceId) 则跳过）。
    static func addCard(_ context: ModelContext, sourceType: String, sourceId: String) {
        let type = sourceType, sid = sourceId
        var d = FetchDescriptor<ReviewCard>(predicate: #Predicate { $0.sourceType == type && $0.sourceId == sid })
        d.fetchLimit = 1
        if let existing = try? context.fetch(d), !existing.isEmpty { return }
        context.insert(ReviewCard(sourceType: sourceType, sourceId: sourceId,
                                  box: 0, dueAt: nowMs(), missCount: 0, lastReviewedAt: 0))
    }

    /// 删卡（按来源）。
    static func removeCard(_ context: ModelContext, sourceType: String, sourceId: String) {
        let type = sourceType, sid = sourceId
        let d = FetchDescriptor<ReviewCard>(predicate: #Predicate { $0.sourceType == type && $0.sourceId == sid })
        for c in (try? context.fetch(d)) ?? [] { context.delete(c) }
    }

    /// 删除某类型全部卡（清空历史时用）。
    static func removeAll(_ context: ModelContext, sourceType: String) {
        let type = sourceType
        let d = FetchDescriptor<ReviewCard>(predicate: #Predicate { $0.sourceType == type })
        for c in (try? context.fetch(d)) ?? [] { context.delete(c) }
    }

    /// 首次回填：现有词条 + 已星标记录一律建 box0 卡。UserDefaults 标志守一次性。
    static func runBackfillIfNeeded(_ context: ModelContext) {
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: "srsBackfilled") { return }
        let entries = (try? context.fetch(FetchDescriptor<WordEntry>())) ?? []
        for e in entries { addCard(context, sourceType: "WORD_ENTRY", sourceId: e.uid) }
        let starred = (try? context.fetch(FetchDescriptor<TranslationRecord>(
            predicate: #Predicate { $0.starred == true }))) ?? []
        for r in starred { addCard(context, sourceType: "STARRED", sourceId: r.uid) }
        try? context.save()
        defaults.set(true, forKey: "srsBackfilled")
    }
}
