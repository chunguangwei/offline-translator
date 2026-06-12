package com.offlinetranslator.app.engine.llm

/**
 * 问答上下文窗口的纯算术 —— 从 ChatViewModel 抽出以便单测（行为不变）。
 *
 * 产品决策（docs/待修复清单.md「产品已知取舍」）：
 * 上下文 = 摘要 + 全部未压缩双方消息；未压缩 >[COMPRESS_AFTER_MESSAGES] 条
 * 或总字数 >[CHAR_BUDGET]（会话"满了"）触发压缩，压缩后保留最近
 * [KEEP_RAW_AFTER_COMPRESS] 条原文；压缩没跟上时按预算从最旧裁剪兜底。
 */
object ContextWindow {
    /** 未压缩消息超过这个条数 → 触发上下文压缩。 */
    const val COMPRESS_AFTER_MESSAGES = 20

    /**
     * 未压缩消息总字数预算。超过即视为会话"满了"→ 触发压缩；同时是单轮
     * prompt 携带原文的硬上限。模型窗口 4096 token，预留生成空间后中文按
     * ≈1 字/token 取 3000。
     */
    const val CHAR_BUDGET = 3000

    /** 压缩后保留的最近原文条数（其余并入摘要）。 */
    const val KEEP_RAW_AFTER_COMPRESS = 6

    /** 是否需要触发压缩。 */
    fun shouldCompress(unsummarizedCount: Int, totalChars: Int): Boolean =
        unsummarizedCount > COMPRESS_AFTER_MESSAGES || totalChars > CHAR_BUDGET

    /**
     * 上下文裁剪兜底：完整带上双方消息，仅当总字数超预算（压缩还没跟上）时
     * 从最旧的开始裁（最近的消息永远保留，至少留 1 条）。
     */
    fun fitBudget(
        turns: List<Pair<String, String>>,
        budget: Int = CHAR_BUDGET,
    ): List<Pair<String, String>> {
        var total = turns.sumOf { it.second.length }
        var result = turns
        while (result.size > 1 && total > budget) {
            total -= result.first().second.length
            result = result.drop(1)
        }
        return result
    }
}
