import Foundation
import SwiftData

/// SRS 卡片同步 + 回填辅助（iOS 无 ViewModel 层，视图在数据变更点调用）。
@MainActor
enum SrsStore {
    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

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
