package com.offlinetranslator.app.feature.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun `数值比较而非字典序`() {
        assertTrue(isNewerVersion("1.0.10", "1.0.9")) // 字典序会判错的经典用例
        assertTrue(isNewerVersion("1.1.0", "1.0.99"))
        assertTrue(isNewerVersion("2.0", "1.9.9"))
    }

    @Test
    fun `相同与更旧不触发更新`() {
        assertFalse(isNewerVersion("1.0.9", "1.0.9"))
        assertFalse(isNewerVersion("1.0.8", "1.0.9"))
    }

    @Test
    fun `位数不齐按 0 补齐`() {
        assertTrue(isNewerVersion("1.0.0.1", "1.0"))
        assertFalse(isNewerVersion("1.0", "1.0.0"))
    }

    @Test
    fun `debug 后缀等非数字段忽略`() {
        // 本地 "1.0.9-debug" vs 远端 "1.0.9" —— 同版本，不弹更新
        assertFalse(isNewerVersion("1.0.9", "1.0.9-debug"))
        assertTrue(isNewerVersion("1.0.10", "1.0.9-debug"))
    }
}
