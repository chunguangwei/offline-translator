import Foundation

/// SRS 间隔重复纯函数调度器（与 Android SrsScheduler.kt 逐字一致）。
enum SrsScheduler {
    static let intervalsDays: [Int64] = [0, 1, 3, 7, 16, 30]
    static let maxBox = 6
    static let dayMs: Int64 = 86_400_000

    struct Update { let box: Int; let dueAt: Int64; let missCount: Int; let lastReviewedAt: Int64 }

    static func schedule(box: Int, missCount: Int, correct: Bool, now: Int64) -> Update {
        if !correct { return Update(box: 0, dueAt: now, missCount: missCount + 1, lastReviewedAt: now) }
        let newBox = min(box + 1, maxBox)
        let dueAt = newBox >= maxBox ? Int64.max : now + intervalsDays[newBox] * dayMs
        return Update(box: newBox, dueAt: dueAt, missCount: missCount, lastReviewedAt: now)
    }
}

enum StreakLogic {
    struct State { let lastStudyDay: Int64; let currentStreak: Int }
    static func onStudied(lastDay: Int64, current: Int, today: Int64) -> State {
        if lastDay == today { return State(lastStudyDay: today, currentStreak: current) }
        if lastDay == today - 1 { return State(lastStudyDay: today, currentStreak: current + 1) }
        return State(lastStudyDay: today, currentStreak: 1)
    }
    static func displayStreak(lastDay: Int64, current: Int, today: Int64) -> Int {
        (lastDay == today || lastDay == today - 1) ? current : 0
    }
}

enum DuePool {
    static func select<T>(cards: [T], now: Int64, newLimit: Int,
                          box: (T) -> Int, dueAt: (T) -> Int64, lastReviewedAt: (T) -> Int64) -> [T] {
        let due = cards.filter { dueAt($0) <= now && (box($0) >= 1 || lastReviewedAt($0) > 0) }
        let fresh = cards.filter { box($0) == 0 && lastReviewedAt($0) == 0 }.prefix(newLimit)
        return due + Array(fresh)
    }
}
