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
