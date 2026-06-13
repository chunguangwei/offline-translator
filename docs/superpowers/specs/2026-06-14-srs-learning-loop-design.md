# 译人 · SRS 学习闭环 设计文档

> 状态：设计已确认，待转 implementation plan。
> 日期：2026-06-14。范围：Android + iOS 双端对齐。

## 目标

把现有"单词本/生词本"从**粗糙的熟练度计数器**（认识+1/不认识归0，≥3 掌握）升级成**真正的间隔重复（SRS）学习闭环**：答对的词隔更久再出现，答错的很快回来；并围绕它建立"今日到期 / 连续打卡 / 每日提醒 / 错题本"的习惯回路，让人愿意每天打开。

**北极星**：一个跨全部词库聚合的"今日到期 N 张 + 开始复习"单一每日仪式。

## 范围

**本轮做**：
- SRS 间隔重复调度（核心）
- 跨"单词本词条 + 生词本星标"统一复习池
- 今日到期数 + 连续打卡天数
- 每日提醒推送（本地、需手动开）
- 错题本/难词视图

**明确不做（后续单独轮次）**：
- 听写/跟读语音模式（需 TTS + ASR 发音评分，另一套大东西）
- 问答增强（长文档/PDF 问答等，受离线小模型上下文窗口硬约束，单独设计）

## 架构决策

### 采用方案 A：统一 `review_card` 调度表

单词本词条与生词本星标是两套数据，要合并成一个复习池，SRS 状态集中存一张表，而非分散到两张现有表加列。

- ✅ SRS 逻辑单一真相源、纯函数好单测；今日到期/错题本/打卡都查一张表
- ⚠️ 代价：需同步钩子（导入建卡、星标建卡、删除删卡）——几个点，可控

被否方案 B（两张现有表各加 SRS 列 + UNION 聚合）：schema 重复、逻辑散两处、难单测，放弃。

### SRS 算法：Leitner 盒子 + 二选一评分

贴合现有「认识/不认识」二选一交互，最简单。

| 盒子档 box | 答对后下次到期间隔 |
|---|---|
| 0（新/答错打回） | 本次会话内稍后再现 |
| 1 | 1 天 |
| 2 | 3 天 |
| 3 | 7 天 |
| 4 | 16 天 |
| 5 | 30 天 |
| 6 | 已掌握（永不到期，仅"全量抽查"可练） |

间隔天数表 `[0, 1, 3, 7, 16, 30]`（下标=box，box 6=掌握封顶）。

## 数据模型

### `review_card` 表（Android Room 实体 / iOS `ReviewCard` @Model，双端同构）

| 字段 | 类型 | 含义 |
|---|---|---|
| `id` | 主键自增 | |
| `sourceType` | 枚举 `WORD_ENTRY` / `STARRED` | 卡片来源 |
| `sourceId` | Long | 指向 `word_entry.id` 或 `translation_history.id` |
| `box` | Int 0..6 | 盒子档（0=新/打回，6=掌握） |
| `dueAt` | Long(epoch ms) | 下次到期时间 |
| `missCount` | Int | 累计答错次数（错题本用） |
| `lastReviewedAt` | Long | 上次复习时间 |
| `createdAt` | Long | 建卡时间 |
| 唯一约束 | `(sourceType, sourceId)` | 防重复建卡 |

**卡片正反面内容不存本表**——只存调度状态 + 指针。复习时按 `sourceId` 回查源行拿内容：
- `WORD_ENTRY` → `word_entry` 的 english/chinese/note
- `STARRED` → `translation_history` 的 原文/译文/方向

### 连续天数（存偏好，不建表）

Android DataStore / iOS @AppStorage：
- `lastStudyDate`：上次完成复习的日期（本地日历日）
- `currentStreak`：当前连续天数
- `longestStreak`：历史最长（展示用）

规则：完成 ≥1 张复习即算今天打卡。`lastStudyDate==今天`→不变；`==昨天`→streak+1；否则 streak=1。展示当前连续天数时，若 `lastStudyDate` 既非今天也非昨天，视为已断，显示 0。

## SRS 调度器（纯函数，双端各一份、可单测）

输入：当前卡 `box/missCount`、是否答对、当前时间 `now`。输出：新的 `box/dueAt/missCount/lastReviewedAt`。

```
const intervalsDays = [0, 1, 3, 7, 16, 30]
const MAX_BOX = 6
const DAY_MS = 86_400_000

schedule(card, correct, now):
  if correct:
    newBox = min(card.box + 1, MAX_BOX)
    dueAt  = (newBox >= MAX_BOX) ? FAR_FUTURE
                                 : now + intervalsDays[newBox] * DAY_MS
    return { box: newBox, dueAt, missCount: card.missCount, lastReviewedAt: now }
  else:
    return { box: 0, dueAt: now, missCount: card.missCount + 1, lastReviewedAt: now }
```

`FAR_FUTURE` 用一个远大值（如 `Long.MAX_VALUE`）表示掌握后不再到期。

## 复习流程与 UI

### Learn 页结构（顶部聚合卡 + 分区）

```
🔥 连续 7 天   今日到期 23 张   [ 开始复习 ]
─────────────────────────────
单词本 ┊ 生词本 ┊ 错题本
 · 考研核心词        到期 12 ›
 · 日常口语          到期 11 ›
 · 生词本(星标)      到期  5 ›
 · 错题本            18 难词 ›
```

### 今日到期池的计算

```
今日到期 =
   到期卡：dueAt ≤ now 且 (box≥1 或 lastReviewedAt>0)   // 含答错打回但没练完的卡
 + 新  卡：box=0 且 lastReviewedAt=0，按每日限额取前 N 张
```
> 用 `lastReviewedAt` 区分"全新词"（=0，受限额）与"答错打回的卡"（>0，不受限额、应尽快重练）。会话内答错的卡靠内存队列本次再现解决；中途退出残留的打回卡，下次仍算到期。

**新词每日限额**：刚导入的词 `dueAt=now` 即到期，但每天放进池子的**新卡**（box=0 且 lastReviewedAt=0）受所属单词本 `dailyGoal`（5/10/20/30）限额，避免开局一次性涌入几百张。生词本星标的新卡限额固定为 **10/天**（本轮不做可配）。

### 统一复习会话（点「开始复习」）

1. 跨全部来源把今日到期卡拉进**一个混合内存队列**，顺序打散
2. 翻卡 UI 沿用现有：正面出题 → 「翻面」看答案 → 「认识 / 不认识」
3. 方向 `mixed`：每张随机中→英或英→中（沿用现有 per-card 定向）
4. 「不认识」→ 该卡回**队尾本次再现** + 落库 `schedule(card, false)`；「认识」→ 出队 + 落库 `schedule(card, true)`
5. 队列空 = 今日复习完 → 更新连续天数 → 弹小结（今日复习 N 张 · 新掌握 M 张 · 连续 X 天）

> 会话内"答错回队尾再现"沿用现有单词本 quiz 的行为，无需新造。

### 进具体单词本（保留现有入口，语义升级）

- 顶部「本册今日到期 K 张」→ 只练这一本的到期卡（走 SRS 落库）
- 「全量抽查」→ 整本打散过一遍，**不改 SRS 档位**（练手用，不打乱节奏）

### 错题本/难词视图

- 入口在分区里，列出 `missCount ≥ 3` 的卡（跨单词本+生词本），按错次降序
- 「只练错题本」→ 把这些卡单独拉成一个会话强化（走 SRS 落库）
- 简单策略：只按 `missCount` 阈值显示，不做"连对移出"逻辑（错次不清零，掌握后自然不再到期、不再置顶）

## 每日提醒推送

设置页：「每日复习提醒」开关 + 时间选择器，**默认关、需手动开**。

- **Android**：WorkManager 每日定时任务（项目已用 WorkManager）。触发时查"今日到期数"，>0 才发通知「📚 你有 N 张单词待复习」。需 `POST_NOTIFICATIONS` 权限（API 33+），开开关时申请；改时间即重排任务。
- **iOS**：`UNUserNotificationCenter` 按日历重复的本地通知。开开关时申请授权；正文用通用语「📚 该复习单词啦」（iOS 无法在触发时查库，用通用文案，或带上次进后台时缓存的到期数）。

纯本地调度、不联网，与隐私承诺一致（隐私文案已在 v1.0.11 据实化，提醒属本地通知无需额外披露联网）。

## 数据迁移与回填

- **Android** Room v5→v6：迁移 SQL 仅 `CREATE TABLE review_card`（含唯一索引）。**首次启动后一次性回填**，prefs 加 `srsBackfilled` 标志守护：
  - 给现有 `word_entry` 和已星标 `translation_history` **统一建 `box=0`、`dueAt=now` 的卡**（视作新卡）
  - **关键**：全部按 box0 回填 → 受每日新词限额约束、按 `dailyGoal/天` 渐进放出，**不会回填即爆几百张**
  - **取舍**：旧 `proficiency`(0..3) 进度不迁移、一律从头算。理由：旧熟练度是非时间维的粗糙计数器，无法转成有意义的 SRS 间隔；且旧系统本就不是真 SRS，重置代价小、换来回填逻辑极简且无洪峰。该列保留在 `word_entry` 不动（兼容/回滚用），仅不再驱动复习
  - 回填逻辑放 Kotlin，迁移 SQL 保持干净
- **iOS** SwiftData：新增 `ReviewCard` @Model（轻量自动迁移）。同样以 @AppStorage `srsBackfilled` 标志守一次性回填。

## 同步钩子（保证 `review_card` 与源数据一致）

挂到现有流程：

| 触发点 | 动作 | 现有挂载位置 |
|---|---|---|
| 单词本导入完成 | 批量建卡（box0/due now） | `WordBookViewModel.saveBook` |
| 删单词本 / 删词条 | 删对应卡 | 单词本删除路径 |
| 星标一条历史 | 建 `STARRED` 卡 | 历史星标 toggle |
| 取消星标 / 删历史 | 删该卡 | 历史取消星标 / 删除 |

## 测试

延续项目纯函数单测习惯，双端各一份：

- **SRS 调度器**：答对升档 + 到期推算；答错归零 + missCount+1；档6 掌握封顶（dueAt 远未来）
- **连续天数逻辑**：同日不变 / 隔日+1 / 断档重置为 1 / 断档后显示 0
- **每日新词限额**：超额新卡不进当日池、过期旧卡不受限额约束

## 受影响文件（预估，详列留给 plan）

**Android**：`AppDatabase.kt`（review_card 实体 + DAO + MIGRATION_5_6）、新 `SrsScheduler.kt`（纯函数）、新 streak 偏好字段（AppPreferences）、`LearnScreen.kt` + 新 ViewModel（聚合今日到期/打卡/统一会话）、`WordBookViewModel.kt`（建卡/删卡钩子 + 全量抽查不改档）、历史星标钩子、设置页提醒开关、新提醒 Worker、回填逻辑、单测。

**iOS**：`Store.swift`（ReviewCard @Model）、新 `SrsScheduler.swift`、streak @AppStorage、`LearnView.swift` + ViewModel、单词本/历史钩子、设置提醒、`UNUserNotificationCenter` 调度、回填、单测。

## 不变量 / 约束

- 双端 SRS 算法、间隔表、box 上限、streak 规则**逐字一致**（同 PromptTemplates 对齐原则）
- 纯离线：提醒为本地通知，无任何联网
- 回填只跑一次（prefs 标志守护），重装/迁移不重复建卡（唯一约束兜底）
