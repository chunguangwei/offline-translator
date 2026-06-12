# 译人 · 单词本系统设计

> 2026-06-12 用户需求：上传生词本文本 → AI 提取英文+翻译 → 命名存储 →
> 每日学习量 → 中译英/英译中翻卡测试 → 全量随机抽查。规则细节授权 Claude 设计。

## 数据模型（双端同构）

- **WordBook**：name（用户命名）、purpose（用途，可选）、dailyGoal（每日学习量 5/10/20/30，默认 10）、createdAt
- **WordEntry**：bookId、english、chinese（释义）、note（注释：词性/例句/用法，可空）、
  proficiency（熟练度 0..3）、lastSeenAt、createdAt

## 导入流程

1. 入口：历史 Tab 三段式「记录 / 生词本 / 单词本」→ 单词本列表 → ➕ 新建
2. 来源二选：**粘贴文本** 或 **选择 .txt 文件**
3. Gemma 离线提取（分块 ~1200 字符，逐块流式）：
   严格行格式 `english => 中文释义 => 注释`（注释可空）；
   纯英文词表（无中文）→ 模型自动生成中文释义；同英文去重
4. 提取结果**预览列表**（可滑删错误项）→ 填名称/用途/每日量 → 保存

## 学习与测试规则

- **熟练度**：测试中点「认识」+1、「不认识」归 0；proficiency ≥ 3 视为**已掌握**
- **今日学习**：从未掌握词中取 dailyGoal 个（优先没学过/最久没见的）→ 进入翻卡测试
- **全量抽查**：整本随机洗牌（含已掌握，检验真实记忆）
- **方向**：中→英（出中文，查看显示英文+注释）/ 英→中（出英文，查看显示中文+注释）/ 混合随机
- **翻卡交互**：正面 prompt → 点「查看」翻面 → 自评 认识/不认识；
  不认识的本轮放回队尾直到全部认识；结束显示统计（共几张/一次过几张）

## 平台落点

- Android：Room v4→v5（word_book / word_entry 两表正式迁移）、
  `feature/wordbook/`（VM + Screens）、历史页三段化、OpenDocument 选 txt
- iOS：SwiftData 两个 @Model、`Features/WordBook/`、HistoryView 三段化、fileImporter
- 提取 prompt 双端同文案：PromptTemplates.extractVocab（低温采样）
