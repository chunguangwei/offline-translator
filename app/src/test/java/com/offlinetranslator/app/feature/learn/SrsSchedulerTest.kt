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
}
