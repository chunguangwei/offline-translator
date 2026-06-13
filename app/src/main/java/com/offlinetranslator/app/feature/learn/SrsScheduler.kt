package com.offlinetranslator.app.feature.learn

/** SRS 间隔重复纯函数调度器（Leitner 盒子 + 二选一）。无 Android 依赖，纯单测。 */
object SrsScheduler {
    val intervalsDays = longArrayOf(0, 1, 3, 7, 16, 30)
    const val MAX_BOX = 6
    const val DAY_MS = 86_400_000L
    const val FAR_FUTURE = Long.MAX_VALUE

    data class Update(val box: Int, val dueAt: Long, val missCount: Int, val lastReviewedAt: Long)

    /** 给一次复习评分，算出新的调度状态。correct=认识 / !correct=不认识。 */
    fun schedule(box: Int, missCount: Int, correct: Boolean, now: Long): Update {
        if (!correct) {
            return Update(box = 0, dueAt = now, missCount = missCount + 1, lastReviewedAt = now)
        }
        val newBox = (box + 1).coerceAtMost(MAX_BOX)
        val dueAt = if (newBox >= MAX_BOX) FAR_FUTURE
        else now + intervalsDays[newBox] * DAY_MS
        return Update(box = newBox, dueAt = dueAt, missCount = missCount, lastReviewedAt = now)
    }
}

/** 连续打卡纯逻辑。用「本地日序号」(epochDay) 计算，避免时区耦合。 */
object StreakLogic {
    data class State(val lastStudyDay: Long, val currentStreak: Int)

    /** 完成≥1张复习时调用。today/lastDay 为本地日序号。 */
    fun onStudied(lastDay: Long, current: Int, today: Long): State = when {
        lastDay == today -> State(today, current)          // 今天已记过
        lastDay == today - 1 -> State(today, current + 1)  // 昨天→续
        else -> State(today, 1)                            // 断档/首次→重置
    }

    /** 展示用：若 lastDay 非今天也非昨天，连续已断，显示 0。 */
    fun displayStreak(lastDay: Long, current: Int, today: Long): Int =
        if (lastDay == today || lastDay == today - 1) current else 0
}

/** 今日到期池筛选纯逻辑。 */
object DuePool {
    /**
     * 从全部卡里选出今日该练的：
     *  - 到期卡：dueAt<=now 且 (box>=1 或 lastReviewedAt>0)  含答错打回但没练完的
     *  - 新卡：box==0 且 lastReviewedAt==0，按 newLimit 取前 N（按传入顺序）
     */
    fun <T> select(
        cards: List<T>, now: Long, newLimit: Int,
        box: (T) -> Int, dueAt: (T) -> Long, lastReviewedAt: (T) -> Long,
    ): List<T> {
        val due = cards.filter { dueAt(it) <= now && (box(it) >= 1 || lastReviewedAt(it) > 0) }
        val fresh = cards.filter { box(it) == 0 && lastReviewedAt(it) == 0L }.take(newLimit)
        return due + fresh
    }
}
