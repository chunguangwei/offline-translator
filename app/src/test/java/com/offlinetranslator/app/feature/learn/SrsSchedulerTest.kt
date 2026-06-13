package com.offlinetranslator.app.feature.learn

import org.junit.Assert.assertEquals
import org.junit.Test

class SrsSchedulerTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test fun `答对从0升到1，1天后到期`() {
        val r = SrsScheduler.schedule(box = 0, missCount = 0, correct = true, now = now)
        assertEquals(1, r.box)
        assertEquals(now + 1 * day, r.dueAt)
        assertEquals(0, r.missCount)
        assertEquals(now, r.lastReviewedAt)
    }

    @Test fun `答对逐档间隔 1 3 7 16 30`() {
        assertEquals(now + 1 * day, SrsScheduler.schedule(0, 0, true, now).dueAt)
        assertEquals(now + 3 * day, SrsScheduler.schedule(1, 0, true, now).dueAt)
        assertEquals(now + 7 * day, SrsScheduler.schedule(2, 0, true, now).dueAt)
        assertEquals(now + 16 * day, SrsScheduler.schedule(3, 0, true, now).dueAt)
        assertEquals(now + 30 * day, SrsScheduler.schedule(4, 0, true, now).dueAt)
    }

    @Test fun `答对到顶档6视为掌握，永不到期`() {
        val r = SrsScheduler.schedule(box = 5, missCount = 0, correct = true, now = now)
        assertEquals(6, r.box)
        assertEquals(Long.MAX_VALUE, r.dueAt)
    }

    @Test fun `答错归0、missCount加1、立即到期`() {
        val r = SrsScheduler.schedule(box = 4, missCount = 2, correct = false, now = now)
        assertEquals(0, r.box)
        assertEquals(now, r.dueAt)
        assertEquals(3, r.missCount)
        assertEquals(now, r.lastReviewedAt)
    }

    // streak：用「本地日序号」（epochDay）做参数，避免时区/时钟问题
    @Test fun `同一天再次完成不变`() {
        val r = StreakLogic.onStudied(lastDay = 100, current = 5, today = 100)
        assertEquals(100, r.lastStudyDay); assertEquals(5, r.currentStreak)
    }
    @Test fun `隔天完成加1`() {
        val r = StreakLogic.onStudied(lastDay = 100, current = 5, today = 101)
        assertEquals(101, r.lastStudyDay); assertEquals(6, r.currentStreak)
    }
    @Test fun `断档后重置为1`() {
        val r = StreakLogic.onStudied(lastDay = 100, current = 5, today = 103)
        assertEquals(103, r.lastStudyDay); assertEquals(1, r.currentStreak)
    }
    @Test fun `从未学习过首次为1`() {
        val r = StreakLogic.onStudied(lastDay = 0, current = 0, today = 100)
        assertEquals(100, r.lastStudyDay); assertEquals(1, r.currentStreak)
    }
    @Test fun `展示时断档显示0`() {
        assertEquals(5, StreakLogic.displayStreak(lastDay = 100, current = 5, today = 100))
        assertEquals(5, StreakLogic.displayStreak(lastDay = 100, current = 5, today = 101))
        assertEquals(0, StreakLogic.displayStreak(lastDay = 100, current = 5, today = 102))
    }

    // DuePool.select：把卡分成「到期(含打回)」「新卡(受限额)」「未到期」
    private data class C(val box: Int, val dueAt: Long, val last: Long) // 测试替身
    @Test fun `到期与新卡与限额`() {
        val now = 1_000L
        val cards = listOf(
            C(box = 2, dueAt = 500, last = 999),   // 到期旧卡 → 入选
            C(box = 0, dueAt = 500, last = 999),    // 答错打回(last>0) → 入选、不受限额
            C(box = 0, dueAt = 1000, last = 0),     // 新卡 #1
            C(box = 0, dueAt = 1000, last = 0),     // 新卡 #2
            C(box = 0, dueAt = 1000, last = 0),     // 新卡 #3（限额=2 时落选）
            C(box = 3, dueAt = 5000, last = 999),   // 未到期 → 落选
        )
        val sel = DuePool.select(
            cards = cards, now = now, newLimit = 2,
            box = { it.box }, dueAt = { it.dueAt }, lastReviewedAt = { it.last },
        )
        // 2 张到期(含打回) + 2 张新卡（限额2）= 4
        assertEquals(4, sel.size)
    }

    @Test fun `回填一律 box0、dueAt now、last 0`() {
        val card = SrsScheduler.backfillCard(sourceType = "WORD_ENTRY", sourceId = 42L, now = 999L)
        assertEquals("WORD_ENTRY", card.sourceType); assertEquals(42L, card.sourceId)
        assertEquals(0, card.box); assertEquals(999L, card.dueAt)
        assertEquals(0, card.missCount); assertEquals(0L, card.lastReviewedAt)
    }
}
