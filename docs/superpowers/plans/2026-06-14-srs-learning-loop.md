# SRS 学习闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把单词本/生词本从粗糙熟练度计数器升级成真正的间隔重复（SRS）学习闭环：跨全部词库聚合的「今日到期 + 连续打卡 + 每日提醒 + 错题本」。

**Architecture:** 统一 `review_card` 调度表（来源=单词本词条 或 生词本星标），纯函数 SRS 调度器（Leitner 盒子 + 二选一），跨本聚合今日到期池。Android(Room+Compose+WorkManager) 先行作参考，iOS(SwiftData+SwiftUI+UNUserNotificationCenter) 镜像，算法/常量双端逐字对齐。

**Tech Stack:** Kotlin/Room/Hilt/Compose/WorkManager/DataStore；Swift/SwiftData/SwiftUI/UNUserNotificationCenter/@AppStorage。

**Spec:** `docs/superpowers/specs/2026-06-14-srs-learning-loop-design.md`

**构建/测试命令（贯穿全程）：**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest -Dkotlin.compiler.execution.strategy=in-process   # Android 单测
./gradlew :app:assembleDebug -Dkotlin.compiler.execution.strategy=in-process       # Android 编译
# iOS 单测（模拟器）：
cd ios && xcodebuild -project Yiren.xcodeproj -scheme Yiren \
  -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO test
# iOS 真机编译：
cd ios && xcodegen generate && xcodebuild -project Yiren.xcodeproj -scheme Yiren \
  -destination 'platform=iOS,id=E21DE821-E25A-50C4-9F85-5FB688014A3D' \
  -derivedDataPath build/dev DEVELOPMENT_TEAM=L35RLT89XN build
```
> iOS 新增源文件后必须先 `xcodegen generate` 再编译。

---

## 文件结构（先锁定职责）

**Android 新建：**
- `app/src/main/java/com/offlinetranslator/app/feature/learn/SrsScheduler.kt` — 纯函数调度器 + streak 纯逻辑（可单测，无 Android 依赖）
- `app/src/main/java/com/offlinetranslator/app/feature/learn/ReviewReminderWorker.kt` — WorkManager 每日提醒
- `app/src/test/java/com/offlinetranslator/app/feature/learn/SrsSchedulerTest.kt` — 调度器/streak/限额单测

**Android 修改：**
- `core/data/db/AppDatabase.kt` — `ReviewCardEntity` + `ReviewCardDao` + `MIGRATION_5_6` + version 6
- `core/data/AppPreferences.kt` — streak 字段 + 提醒开关/时间 + srsBackfilled 标志
- `feature/learn/LearnScreen.kt` + 其 ViewModel — 聚合今日到期/打卡/统一会话/错题本
- `feature/wordbook/WordBookViewModel.kt` — 导入建卡、删本/删词删卡、全量抽查不改档
- `feature/history/`（星标 toggle 处）— 星标建卡/取消删卡
- `feature/settings/`（设置页）— 提醒开关 + 时间
- `di/AppModule.kt` — 提供 ReviewCardDao
- `OfflineTranslatorApp.kt` 或启动处 — 触发一次性回填

**iOS 新建：**
- `ios/Yiren/Features/Learn/SrsScheduler.swift` — 纯函数调度器 + streak
- `ios/YirenTests/SrsSchedulerTests.swift` — 单测

**iOS 修改：**
- `Data/Store.swift` — `ReviewCard` @Model + 容器注册
- `Features/Learn/LearnView.swift` + ViewModel — 聚合 UI/会话/错题本
- `Features/WordBook/WordBookViews.swift`（VocabExtractor/保存处）— 建卡/删卡钩子
- 历史星标处 — 建卡/删卡
- `Features/Settings/SettingsView.swift` — 提醒开关 + 时间
- App 启动处 — 一次性回填

---

## 共享常量（双端逐字一致）

```
间隔天数表 intervalsDays = [0, 1, 3, 7, 16, 30]   // 下标=box
MAX_BOX = 6                                       // box6 = 已掌握
DAY_MS = 86_400_000
FAR_FUTURE = Long.MAX_VALUE                        // 掌握后不再到期
STARRED_DAILY_NEW_LIMIT = 10                       // 生词本新卡每日限额
错题本阈值 HARD_MISS_THRESHOLD = 3                 // missCount ≥ 3 进错题本
```

---

## Phase 1 — 纯 SRS 调度器 + streak（Android，TDD）

### Task 1: SRS 调度纯函数

**Files:**
- Create: `app/src/main/java/com/offlinetranslator/app/feature/learn/SrsScheduler.kt`
- Test: `app/src/test/java/com/offlinetranslator/app/feature/learn/SrsSchedulerTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/offlinetranslator/app/feature/learn/SrsSchedulerTest.kt`：
```kotlin
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
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*SrsSchedulerTest*" -Dkotlin.compiler.execution.strategy=in-process`
Expected: 编译失败 `Unresolved reference: SrsScheduler`

- [ ] **Step 3: 写最小实现**

`app/src/main/java/com/offlinetranslator/app/feature/learn/SrsScheduler.kt`：
```kotlin
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
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*SrsSchedulerTest*" -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（4 用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/offlinetranslator/app/feature/learn/SrsScheduler.kt \
        app/src/test/java/com/offlinetranslator/app/feature/learn/SrsSchedulerTest.kt
git commit -m "feat(srs): SRS 间隔重复调度纯函数 + 单测"
```

### Task 2: 连续打卡（streak）纯逻辑

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/learn/SrsScheduler.kt`（追加 `StreakLogic`）
- Test: `app/src/test/java/com/offlinetranslator/app/feature/learn/SrsSchedulerTest.kt`（追加）

- [ ] **Step 1: 追加失败测试**

在 `SrsSchedulerTest.kt` 追加：
```kotlin
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
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*SrsSchedulerTest*" -Dkotlin.compiler.execution.strategy=in-process`
Expected: 编译失败 `Unresolved reference: StreakLogic`

- [ ] **Step 3: 实现**

在 `SrsScheduler.kt` 文件追加：
```kotlin
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
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*SrsSchedulerTest*" -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（共 9 用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/offlinetranslator/app/feature/learn/SrsScheduler.kt \
        app/src/test/java/com/offlinetranslator/app/feature/learn/SrsSchedulerTest.kt
git commit -m "feat(srs): 连续打卡 streak 纯逻辑 + 单测"
```

### Task 3: 今日到期池筛选 + 新卡限额（纯逻辑）

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/learn/SrsScheduler.kt`（追加 `DuePool`）
- Test: `app/src/test/java/com/offlinetranslator/app/feature/learn/SrsSchedulerTest.kt`

- [ ] **Step 1: 追加失败测试**

```kotlin
    // DuePool.partition：把卡分成「到期(含打回)」「新卡(受限额)」「未到期」
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
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*SrsSchedulerTest*" -Dkotlin.compiler.execution.strategy=in-process`
Expected: 编译失败 `Unresolved reference: DuePool`

- [ ] **Step 3: 实现**

在 `SrsScheduler.kt` 追加（泛型 + 取值函数，保持纯净可测、不依赖具体实体）：
```kotlin
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
```
> 注：调用方对「新卡」的限额是 per-单词本（用各自 dailyGoal）。聚合时按本分组分别 `select`，再合并打散——见 Task 9 ViewModel。生词本星标用 `STARRED_DAILY_NEW_LIMIT=10`。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*SrsSchedulerTest*" -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（共 10 用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/offlinetranslator/app/feature/learn/SrsScheduler.kt \
        app/src/test/java/com/offlinetranslator/app/feature/learn/SrsSchedulerTest.kt
git commit -m "feat(srs): 今日到期池筛选 + 新卡限额纯逻辑 + 单测"
```

---

## Phase 2 — Android 数据层（review_card 表）

### Task 4: ReviewCardEntity + DAO + 迁移 v5→v6

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/core/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/offlinetranslator/app/di/AppModule.kt`

- [ ] **Step 1: 加实体 + DAO**

在 `AppDatabase.kt`（WordEntryEntity 之后、WordBookDao 之前或文件末尾合适处）新增：
```kotlin
/** SRS 调度卡：来源=单词本词条 或 生词本星标。内容不存这里，按 sourceId 回查源行。 */
@Entity(
    tableName = "review_card",
    indices = [androidx.room.Index(value = ["sourceType", "sourceId"], unique = true)],
)
data class ReviewCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String,   // "WORD_ENTRY" | "STARRED"
    val sourceId: Long,
    val box: Int = 0,
    val dueAt: Long = 0,
    val missCount: Int = 0,
    val lastReviewedAt: Long = 0,
    val createdAt: Long,
)

@Dao
interface ReviewCardDao {
    @Query("SELECT * FROM review_card")
    suspend fun all(): List<ReviewCardEntity>

    @Query("SELECT * FROM review_card WHERE sourceType = :type")
    suspend fun byType(type: String): List<ReviewCardEntity>

    @Query("SELECT COUNT(*) FROM review_card WHERE dueAt <= :now AND (box >= 1 OR lastReviewedAt > 0)")
    suspend fun countOverdue(now: Long): Int

    @Query("SELECT * FROM review_card WHERE missCount >= :threshold ORDER BY missCount DESC")
    suspend fun hardCards(threshold: Int): List<ReviewCardEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(cards: List<ReviewCardEntity>)

    @Query("UPDATE review_card SET box=:box, dueAt=:dueAt, missCount=:miss, lastReviewedAt=:last WHERE id=:id")
    suspend fun updateState(id: Long, box: Int, dueAt: Long, miss: Int, last: Long)

    @Query("DELETE FROM review_card WHERE sourceType=:type AND sourceId=:sourceId")
    suspend fun deleteBySource(type: String, sourceId: Long)

    @Query("DELETE FROM review_card WHERE sourceType=:type AND sourceId IN (:ids)")
    suspend fun deleteBySourceIds(type: String, ids: List<Long>)
}
```

- [ ] **Step 2: 加迁移 + 升版本 + 注册 DAO**

`AppDatabase.kt`：紧接 `MIGRATION_4_5` 后加：
```kotlin
/** v5→v6：新增 SRS 调度表 review_card。 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS review_card (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "sourceType TEXT NOT NULL, sourceId INTEGER NOT NULL, " +
                "box INTEGER NOT NULL, dueAt INTEGER NOT NULL, " +
                "missCount INTEGER NOT NULL, lastReviewedAt INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_review_card_sourceType_sourceId " +
                "ON review_card (sourceType, sourceId)"
        )
    }
}
```
`@Database` 注解：`entities` 加 `ReviewCardEntity::class`，`version = 5` 改 `version = 6`，抽象类加 `abstract fun reviewCardDao(): ReviewCardDao`。

`di/AppModule.kt`：仿 `provideWordBookDao` 加：
```kotlin
@Provides @Singleton
fun provideReviewCardDao(db: AppDatabase): com.offlinetranslator.app.core.data.db.ReviewCardDao =
    db.reviewCardDao()
```
并在 `Room.databaseBuilder(...)` 的 `.addMigrations(...)` 追加 `MIGRATION_5_6`。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:assembleDebug -Dkotlin.compiler.execution.strategy=in-process`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "feat(srs): review_card 表 + DAO + 迁移 v5→v6"
```

---

## Phase 3 — Android 同步钩子（建卡/删卡）

### Task 5: 单词本导入建卡、删本/删词删卡、全量抽查不改档

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/wordbook/WordBookViewModel.kt`

- [ ] **Step 1: 注入 ReviewCardDao**

在 `WordBookViewModel` 构造函数加入 `private val reviewCardDao: ReviewCardDao`（Hilt 自动注入），import 之。

- [ ] **Step 2: 保存单词本后建卡**

定位 `saveBook`（插入 entries 之后）。`insertEntries` 不回传 id，改为查回该本词条再批量建卡：
```kotlin
// saveBook 内，insertEntries(...) 之后：
val entries = dao.entriesOnce(bookId)               // 该本全部词条（含新插入的 id）
val now = System.currentTimeMillis()
reviewCardDao.insertAll(entries.map {
    ReviewCardEntity(
        sourceType = "WORD_ENTRY", sourceId = it.id,
        box = 0, dueAt = now, missCount = 0, lastReviewedAt = 0, createdAt = now,
    )
})
```
> `insertAll` 用 `OnConflictStrategy.IGNORE` + 唯一索引 → 重复保存同一本不会重复建卡。

- [ ] **Step 3: 删本/删词时删卡**

定位删本路径（调用 `dao.deleteBook`/`deleteEntries` 处）：删本前先取该本词条 id 删卡：
```kotlin
val ids = dao.entriesOnce(bookId).map { it.id }
reviewCardDao.deleteBySourceIds("WORD_ENTRY", ids)
dao.deleteEntries(bookId)
dao.deleteBook(bookId)
```
删单条词条处（调用 `dao.deleteEntry(id)`）前加：
```kotlin
reviewCardDao.deleteBySource("WORD_ENTRY", id)
dao.deleteEntry(id)
```

- [ ] **Step 4: 全量抽查不改 SRS 档位**

定位"全量抽查"评分回写处。确认其只用于练手、**不调用** `reviewCardDao.updateState` 也不调 `updateProficiency`（若现有全量抽查会写 proficiency，保留写 proficiency 但不动 review_card）。在该函数加注释：`// 全量抽查仅练手，不改 SRS 档位（dueAt/box 不动），避免打乱节奏`。

- [ ] **Step 5: 编译**

Run: `./gradlew :app:assembleDebug -Dkotlin.compiler.execution.strategy=in-process`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat(srs): 单词本导入建卡、删本/删词删卡钩子"
```

### Task 6: 生词本星标建卡/取消删卡

**Files:**
- Modify: 历史星标 toggle 所在 ViewModel（`app/src/main/java/com/offlinetranslator/app/feature/history/` 下，grep `starred` 定位）

- [ ] **Step 1: 定位星标 toggle**

Run: `grep -rn "starred\|setStarred\|toggleStar" app/src/main/java/com/offlinetranslator/app/feature/history/`
找到切换星标的方法（设 starred=true/false 的 DAO 调用处）。

- [ ] **Step 2: 注入 + 建卡/删卡**

该 ViewModel 注入 `ReviewCardDao`。在 toggle 方法里，依据新状态建/删卡：
```kotlin
val now = System.currentTimeMillis()
if (newStarred) {
    reviewCardDao.insertAll(listOf(ReviewCardEntity(
        sourceType = "STARRED", sourceId = recordId,
        box = 0, dueAt = now, missCount = 0, lastReviewedAt = 0, createdAt = now,
    )))
} else {
    reviewCardDao.deleteBySource("STARRED", recordId)
}
```

- [ ] **Step 3: 删历史记录时删卡**

定位删除单条历史记录处，前置：
```kotlin
reviewCardDao.deleteBySource("STARRED", recordId)
```

- [ ] **Step 4: 编译**

Run: `./gradlew :app:assembleDebug -Dkotlin.compiler.execution.strategy=in-process`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(srs): 生词本星标建卡/取消删卡钩子"
```

---

## Phase 4 — Android 偏好（streak / 提醒 / 回填标志）

### Task 7: AppPreferences 增字段

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/core/data/AppPreferences.kt`

- [ ] **Step 1: 加 Keys + data class 字段 + setter**

在 `AppPreferences.kt`：
- `data class AppPreferences(...)` 增字段：
```kotlin
    val streakLastDay: Long = 0,
    val streakCurrent: Int = 0,
    val streakLongest: Int = 0,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,    // 默认 20:00
    val reminderMinute: Int = 0,
    val srsBackfilled: Boolean = false,
```
- `Keys` 增（用对应类型的 PreferencesKey）：
```kotlin
    val STREAK_LAST_DAY = longPreferencesKey("streak_last_day")
    val STREAK_CURRENT = intPreferencesKey("streak_current")
    val STREAK_LONGEST = intPreferencesKey("streak_longest")
    val REMINDER_ON = booleanPreferencesKey("reminder_enabled")
    val REMINDER_HOUR = intPreferencesKey("reminder_hour")
    val REMINDER_MIN = intPreferencesKey("reminder_minute")
    val SRS_BACKFILLED = booleanPreferencesKey("srs_backfilled")
```
（文件顶部 import `longPreferencesKey`、`intPreferencesKey`。）
- `flow` 的 map 块补读这些键（缺省同 data class 默认值）。
- 加 setter：
```kotlin
    suspend fun setStreak(lastDay: Long, current: Int, longest: Int) = context.appDataStore.edit {
        it[Keys.STREAK_LAST_DAY] = lastDay; it[Keys.STREAK_CURRENT] = current; it[Keys.STREAK_LONGEST] = longest
    }
    suspend fun setReminder(enabled: Boolean, hour: Int, minute: Int) = context.appDataStore.edit {
        it[Keys.REMINDER_ON] = enabled; it[Keys.REMINDER_HOUR] = hour; it[Keys.REMINDER_MIN] = minute
    }
    suspend fun setSrsBackfilled(v: Boolean) = context.appDataStore.edit { it[Keys.SRS_BACKFILLED] = v }
```

- [ ] **Step 2: 编译**

Run: `./gradlew :app:assembleDebug -Dkotlin.compiler.execution.strategy=in-process`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "feat(srs): AppPreferences 增 streak/提醒/回填字段"
```

---

## Phase 5 — Android 回填

### Task 8: 首次启动一次性回填

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/learn/SrsScheduler.kt`（加回填纯函数）
- Test: `app/src/test/java/com/offlinetranslator/app/feature/learn/SrsSchedulerTest.kt`
- Modify: 启动处（`OfflineTranslatorApp.kt` 或首个 Activity 的 VM）触发

- [ ] **Step 1: 回填映射纯函数 + 测试**

测试追加：
```kotlin
    @Test fun `回填一律 box0、dueAt now、last 0`() {
        val card = SrsScheduler.backfillCard(sourceType = "WORD_ENTRY", sourceId = 42L, now = 999L)
        assertEquals("WORD_ENTRY", card.sourceType); assertEquals(42L, card.sourceId)
        assertEquals(0, card.box); assertEquals(999L, card.dueAt)
        assertEquals(0, card.missCount); assertEquals(0L, card.lastReviewedAt)
    }
```
实现（`SrsScheduler.kt` 加，返回实体）：
```kotlin
    /** 回填：现有词一律作新卡（box0/dueAt=now/last=0），受每日限额渐进放出。 */
    fun backfillCard(sourceType: String, sourceId: Long, now: Long) =
        com.offlinetranslator.app.core.data.db.ReviewCardEntity(
            sourceType = sourceType, sourceId = sourceId,
            box = 0, dueAt = now, missCount = 0, lastReviewedAt = 0, createdAt = now,
        )
```

- [ ] **Step 2: 运行测试通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*SrsSchedulerTest*" -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（共 11 用例）

- [ ] **Step 3: 启动触发回填**

在应用启动可注入 prefs/DAO 的位置（如 `LearnViewModel.init` 首次进入，或一个 `@HiltViewModel` 的应用级初始化）加幂等回填：
```kotlin
suspend fun backfillIfNeeded() {
    if (prefs.flow.first().srsBackfilled) return
    val now = System.currentTimeMillis()
    // 单词本所有词条
    val cards = mutableListOf<ReviewCardEntity>()
    for (book in wordBookDao.observeBooks().first()) {
        wordBookDao.entriesOnce(book.id).forEach {
            cards += SrsScheduler.backfillCard("WORD_ENTRY", it.id, now)
        }
    }
    // 已星标历史
    translationDao.starredOnce().forEach {   // 若无此方法，加 @Query("SELECT * FROM translation_history WHERE starred = 1")
        cards += SrsScheduler.backfillCard("STARRED", it.id, now)
    }
    reviewCardDao.insertAll(cards)           // IGNORE 冲突 → 幂等
    prefs.setSrsBackfilled(true)
}
```
若 `TranslationDao` 无 `starredOnce()`，在其加：
```kotlin
@Query("SELECT * FROM translation_history WHERE starred = 1")
suspend fun starredOnce(): List<TranslationEntity>
```
在合适生命周期调用 `backfillIfNeeded()`（如 LearnViewModel init 的 viewModelScope.launch）。

- [ ] **Step 4: 编译 + 单测**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest -Dkotlin.compiler.execution.strategy=in-process`
Expected: BUILD SUCCESSFUL + 测试全绿

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(srs): 首次启动一次性回填现有词为 SRS 卡"
```

---

## Phase 6 — Android Learn 页 UI + 统一会话

### Task 9: LearnViewModel 聚合状态 + 复习会话

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/learn/LearnScreen.kt`（及其 ViewModel；若 VM 内联在 Screen 文件则就地，否则定位其 VM 文件）

- [ ] **Step 1: 暴露聚合 UI 状态**

ViewModel 增（注入 `reviewCardDao`、`wordBookDao`、`translationDao`、`prefs`）：
- `todayDueCount`：跨本计算今日到期数 = Σ(各本 `DuePool.select(本卡, now, 本.dailyGoal)`) + 生词本 `DuePool.select(星标卡, now, 10)`，去重后 size。
- `displayStreak`：`StreakLogic.displayStreak(prefs.streakLastDay, prefs.streakCurrent, todayEpochDay())`，其中 `todayEpochDay()` = `LocalDate.now().toEpochDay()`。
- `hardCount`：`reviewCardDao.hardCards(3).size`。
- 各单词本/生词本的「到期 N」：分别算每本 `select` 后的 size。

会话队列构建 `buildTodaySession(): List<ReviewCardEntity>`：按上述规则选出到期卡，合并打散（`.shuffled()`）。

- [ ] **Step 2: 评分落库 + streak**

```kotlin
suspend fun grade(card: ReviewCardEntity, correct: Boolean) {
    val now = System.currentTimeMillis()
    val u = SrsScheduler.schedule(card.box, card.missCount, correct, now)
    reviewCardDao.updateState(card.id, u.box, u.dueAt, u.missCount, u.lastReviewedAt)
}
// 会话完成（队列清空、至少练过1张）时：
suspend fun markStudiedToday() {
    val p = prefs.flow.first()
    val today = java.time.LocalDate.now().toEpochDay()
    val s = StreakLogic.onStudied(p.streakLastDay, p.streakCurrent, today)
    prefs.setStreak(s.lastStudyDay, s.currentStreak, maxOf(p.streakLongest, s.currentStreak))
}
```
卡片正反面内容：`WORD_ENTRY` 用 `wordBookDao` 查 entry（加 `@Query("SELECT * FROM word_entry WHERE id=:id") suspend fun entryById(id: Long): WordEntryEntity?`）；`STARRED` 用 `translationDao` 查记录（加 `entryById` 同理）。方向 mixed：每张 `(card.id % 2 == 0L)` 或随机定向。

- [ ] **Step 3: Learn 页 UI**

`LearnScreen.kt` 顶部加聚合卡（沿用现有 Compose 暖色卡片风格，参考现有 `LearnScreen` 既有组件）：
```
🔥 连续 {displayStreak} 天    今日到期 {todayDueCount} 张
[ 开始复习 ]  // todayDueCount==0 时禁用并显示「今日已清空 🎉」
```
下方 FilterChip/分区列出 单词本 / 生词本 / 错题本，各项右侧显示「到期 N」/「N 难词」。「开始复习」打开统一翻卡 Dialog（复用现有单词本 QuizDialog 的翻卡 UI；评分调用 `grade`，答错回队尾，队空调 `markStudiedToday` 并弹小结）。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:assembleDebug -Dkotlin.compiler.execution.strategy=in-process`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(srs): Learn 页聚合今日到期/打卡 + 统一复习会话"
```

### Task 10: 错题本视图

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/learn/LearnScreen.kt`

- [ ] **Step 1: 错题本入口 + 会话**

「错题本」分区点击 → 列出 `reviewCardDao.hardCards(3)`（回查内容展示），「只练错题本」按钮把这些卡拉成会话（同 Task 9 的翻卡 UI + grade）。

- [ ] **Step 2: 编译**

Run: `./gradlew :app:assembleDebug -Dkotlin.compiler.execution.strategy=in-process`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "feat(srs): 错题本/难词视图 + 强化会话"
```

---

## Phase 7 — Android 每日提醒

### Task 11: 提醒 Worker + 设置开关

**Files:**
- Create: `app/src/main/java/com/offlinetranslator/app/feature/learn/ReviewReminderWorker.kt`
- Modify: 设置页（grep 定位 settings Screen/VM）、`AndroidManifest.xml`（POST_NOTIFICATIONS 权限）

- [ ] **Step 1: 权限**

`AndroidManifest.xml` 加 `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`。

- [ ] **Step 2: Worker**

`ReviewReminderWorker.kt`（Hilt Worker，仿现有下载 Worker 风格；查到期数 >0 才发通知）：
```kotlin
@HiltWorker
class ReviewReminderWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val reviewCardDao: ReviewCardDao,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val due = reviewCardDao.countOverdue(System.currentTimeMillis())
        if (due > 0) postNotification(applicationContext, due)  // 渠道+通知，文案「📚 你有 $due 张单词待复习」
        return Result.success()
    }
}
```
排程纯函数（算首次延迟到目标时刻，每 24h 重复）放同文件：
```kotlin
fun scheduleDaily(ctx: Context, hour: Int, minute: Int) {
    val now = java.util.Calendar.getInstance()
    val next = (now.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        if (before(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
    }
    val delay = next.timeInMillis - now.timeInMillis
    val req = PeriodicWorkRequestBuilder<ReviewReminderWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        "review_reminder", ExistingPeriodicWorkPolicy.UPDATE, req)
}
fun cancelDaily(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork("review_reminder")
```

- [ ] **Step 3: 设置页开关 + 时间**

设置页加「每日复习提醒」开关（开时请求 POST_NOTIFICATIONS 权限 + `scheduleDaily`，关时 `cancelDaily`）+ 时间选择器（改时重排）。状态存 `prefs.setReminder(...)`。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:assembleDebug -Dkotlin.compiler.execution.strategy=in-process`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(srs): 每日复习提醒 Worker + 设置开关"
```

---

## Phase 8 — iOS 镜像

### Task 12: iOS SRS 调度器 + streak + 池筛选（TDD）

**Files:**
- Create: `ios/Yiren/Features/Learn/SrsScheduler.swift`
- Create: `ios/YirenTests/SrsSchedulerTests.swift`
- Modify: `ios/project.yml`（新文件需 xcodegen 重生成）

- [ ] **Step 1: 写失败测试**

`ios/YirenTests/SrsSchedulerTests.swift`（镜像 Android 用例，逐字对齐间隔/规则）：
```swift
import XCTest
@testable import Yiren

final class SrsSchedulerTests: XCTestCase {
    let now: Int64 = 1_000_000_000_000
    let day: Int64 = 86_400_000

    func testCorrectPromotesAndSchedules() {
        let r = SrsScheduler.schedule(box: 0, missCount: 0, correct: true, now: now)
        XCTAssertEqual(r.box, 1); XCTAssertEqual(r.dueAt, now + day)
        XCTAssertEqual(r.missCount, 0); XCTAssertEqual(r.lastReviewedAt, now)
    }
    func testIntervals() {
        XCTAssertEqual(SrsScheduler.schedule(box: 1, missCount: 0, correct: true, now: now).dueAt, now + 3*day)
        XCTAssertEqual(SrsScheduler.schedule(box: 4, missCount: 0, correct: true, now: now).dueAt, now + 30*day)
    }
    func testMasteredFarFuture() {
        let r = SrsScheduler.schedule(box: 5, missCount: 0, correct: true, now: now)
        XCTAssertEqual(r.box, 6); XCTAssertEqual(r.dueAt, Int64.max)
    }
    func testWrongResets() {
        let r = SrsScheduler.schedule(box: 4, missCount: 2, correct: false, now: now)
        XCTAssertEqual(r.box, 0); XCTAssertEqual(r.dueAt, now)
        XCTAssertEqual(r.missCount, 3); XCTAssertEqual(r.lastReviewedAt, now)
    }
    func testStreak() {
        XCTAssertEqual(StreakLogic.onStudied(lastDay: 100, current: 5, today: 100).currentStreak, 5)
        XCTAssertEqual(StreakLogic.onStudied(lastDay: 100, current: 5, today: 101).currentStreak, 6)
        XCTAssertEqual(StreakLogic.onStudied(lastDay: 100, current: 5, today: 103).currentStreak, 1)
        XCTAssertEqual(StreakLogic.displayStreak(lastDay: 100, current: 5, today: 102), 0)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd ios && xcodegen generate && xcodebuild -project Yiren.xcodeproj -scheme Yiren -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO test`
Expected: 编译失败 `Cannot find 'SrsScheduler'`

- [ ] **Step 3: 实现**

`ios/Yiren/Features/Learn/SrsScheduler.swift`（与 Kotlin 逐字对齐）：
```swift
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
```

- [ ] **Step 4: 运行确认通过**

Run: `cd ios && xcodegen generate && xcodebuild -project Yiren.xcodeproj -scheme Yiren -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO test`
Expected: TEST SUCCEEDED

- [ ] **Step 5: 提交**

```bash
git add ios/Yiren/Features/Learn/SrsScheduler.swift ios/YirenTests/SrsSchedulerTests.swift ios/project.yml
git commit -m "feat(srs/ios): SRS 调度器 + streak + 池筛选纯函数 + 单测（与安卓对齐）"
```

### Task 13: iOS ReviewCard @Model + 容器注册

**Files:**
- Modify: `ios/Yiren/Data/Store.swift`

- [ ] **Step 1: 加 @Model**

`Store.swift` 加：
```swift
/// SRS 调度卡（对应 Android ReviewCardEntity）。sourceType: "WORD_ENTRY" | "STARRED"。
@Model
final class ReviewCard {
    var sourceType: String
    var sourceId: String      // iOS 用 persistentModelID 字符串或源记录稳定标识
    var box: Int = 0
    var dueAt: Int64 = 0
    var missCount: Int = 0
    var lastReviewedAt: Int64 = 0
    var createdAt: Date

    init(sourceType: String, sourceId: String, box: Int = 0, dueAt: Int64 = 0,
         missCount: Int = 0, lastReviewedAt: Int64 = 0, createdAt: Date = .now) {
        self.sourceType = sourceType; self.sourceId = sourceId
        self.box = box; self.dueAt = dueAt; self.missCount = missCount
        self.lastReviewedAt = lastReviewedAt; self.createdAt = createdAt
    }
}
```
`DataStore.container` 的 `ModelContainer(for:...)` 列表加 `ReviewCard.self`。

**同步给 `WordEntry` 和 `TranslationRecord` 各加稳定标识**（`persistentModelID` 不适合跨表存字符串引用，用自带 uid 更稳）：
```swift
// WordEntry 加：
var uid: String = UUID().uuidString
// TranslationRecord 加：
var uid: String = UUID().uuidString
```
SwiftData 轻量迁移自动补该字段。后续 `ReviewCard.sourceId` 一律存源记录的 `uid`。
> 回填时（Task 14）现有记录的 uid 由默认值 `UUID()` 在迁移后首次访问时生成并持久化——回填遍历时读 `entry.uid` 即得稳定值。

- [ ] **Step 2: 编译**

Run: `cd ios && xcodebuild -project Yiren.xcodeproj -scheme Yiren -destination 'platform=iOS,id=E21DE821-E25A-50C4-9F85-5FB688014A3D' -derivedDataPath build/dev DEVELOPMENT_TEAM=L35RLT89XN build`
Expected: BUILD SUCCEEDED

- [ ] **Step 3: 提交**

```bash
git add ios/Yiren/Data/Store.swift && git commit -m "feat(srs/ios): ReviewCard @Model + 容器注册"
```

### Task 14: iOS 钩子 + 回填 + Learn UI + 错题本 + 提醒

**Files:**
- Modify: `ios/Yiren/Features/WordBook/WordBookViews.swift`（保存/删除处建卡/删卡）
- Modify: 历史星标处（建卡/删卡）
- Modify: `ios/Yiren/Features/Learn/LearnView.swift` + ViewModel（聚合 UI/会话/错题本/回填）
- Modify: `ios/Yiren/Features/Settings/SettingsView.swift`（提醒开关 + 时间）

- [ ] **Step 1: 同步钩子**

单词本保存后：对每个新 `WordEntry` `context.insert(ReviewCard(sourceType: "WORD_ENTRY", sourceId: entry.uid, dueAt: nowMs))`。删本/删词、星标/取消星标、删历史 → 对应 fetch `ReviewCard where sourceType&&sourceId` 删除。`nowMs = Int64(Date().timeIntervalSince1970 * 1000)`。

- [ ] **Step 2: 回填**

App 启动（`@main` App init 或 LearnView `.task`）：`@AppStorage("srsBackfilled")` 守护，遍历所有 `WordEntry` 与 starred `TranslationRecord` 各 `insert(ReviewCard(... box:0, dueAt: nowMs, lastReviewedAt:0))`，置标志。幂等：插入前按 `(sourceType,sourceId)` 查重。

- [ ] **Step 3: Learn UI + 会话 + 错题本**

`LearnView` 顶部聚合卡（🔥连续 N 天 / 今日到期 N / 开始复习），下方 单词本·生词本·错题本 分区（各显到期数/难词数）。今日到期 = 各本 `DuePool.select(本卡, nowMs, 本.dailyGoal)` + 星标 `select(..., 10)` 去重。复习会话复用现有 `WordQuizView` 翻卡 UI；评分 `SrsScheduler.schedule` → 更新 ReviewCard；答错回队尾；队空 `StreakLogic.onStudied` 写 `@AppStorage` streak + 弹小结。错题本 = `ReviewCard where missCount>=3` 排序展示 + 「只练错题本」。streak 用 `@AppStorage("streakLastDay"/"streakCurrent"/"streakLongest")`，今天 epochDay = `Int64(Calendar.current.startOfDay(for: .now).timeIntervalSince1970 / 86400)`。

- [ ] **Step 4: 提醒**

`SettingsView` 加「每日复习提醒」开关 + `DatePicker`(.hourAndMinute)。开时 `UNUserNotificationCenter.current().requestAuthorization` + 排程：
```swift
let content = UNMutableNotificationContent()
content.body = PromptTemplates.isZhUi ? "📚 该复习单词啦" : "📚 Time to review your words"
var dc = DateComponents(); dc.hour = hour; dc.minute = minute
let trigger = UNCalendarNotificationTrigger(dateMatching: dc, repeats: true)
UNUserNotificationCenter.current().add(
    UNNotificationRequest(identifier: "review_reminder", content: content, trigger: trigger))
```
关时 `removePendingNotificationRequests(withIdentifiers: ["review_reminder"])`。开关/时间存 `@AppStorage`。

- [ ] **Step 5: 重生成 + 编译 + 单测**

Run:
```bash
cd ios && xcodegen generate && \
xcodebuild -project Yiren.xcodeproj -scheme Yiren -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO test && \
xcodebuild -project Yiren.xcodeproj -scheme Yiren -destination 'platform=iOS,id=E21DE821-E25A-50C4-9F85-5FB688014A3D' -derivedDataPath build/dev DEVELOPMENT_TEAM=L35RLT89XN build
```
Expected: TEST SUCCEEDED + BUILD SUCCEEDED

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat(srs/ios): 钩子+回填+Learn聚合UI+错题本+每日提醒"
```

---

## Phase 9 — 收尾

### Task 15: 双端联编 + 文档 + 装机

- [ ] **Step 1: 双端全量编译 + 单测**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :app:testDebugUnitTest -Dkotlin.compiler.execution.strategy=in-process
cd ios && xcodebuild -project Yiren.xcodeproj -scheme Yiren -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO test
```
Expected: 双端全绿

- [ ] **Step 2: 更新清单 + README**

`docs/待修复清单.md` 记一条「SRS 学习闭环已落地」；`README.md` 学习 Tab 段落补「间隔重复 SRS / 今日到期 / 连续打卡 / 每日提醒 / 错题本」。

- [ ] **Step 3: 真机装（不发版，等用户验收说"发"）**

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
adb -s 10AD5K0ZJP001R0 install -r app/build/outputs/apk/debug/app-debug.apk   # vivo（如连接）
# iPhone：devicectl install（见顶部命令构建产物路径）
```

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "docs(srs): 清单/README 更新；SRS 学习闭环落地"
```

---

## 验收标准

- SRS 调度器 + streak + 池筛选 + 回填映射：双端单测全绿（Android +5 用例、iOS +5 用例）
- 升级（Room v5→v6 / SwiftData 加 ReviewCard）不丢用户数据；回填只跑一次
- Learn 页：连续天数 + 今日到期数正确；开始复习走统一翻卡；答对升档、答错回队尾且 missCount+1
- 错题本列 missCount≥3 卡；可单独强化
- 提醒开关默认关，开后到点本地通知，无任何联网
- 双端算法/间隔/常量逐字一致
