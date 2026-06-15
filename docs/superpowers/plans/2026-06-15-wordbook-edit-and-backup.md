# 单词本编辑/添加/去重 + 全量备份/还原 实现计划

> **For agentic workers:** 用 superpowers:subagent-driven-development 逐任务执行。每任务 TDD、提交、两段式审查。
> 分支 `feat/wordbook-edit-backup`（崩溃修复 + spec 已提交）。

**Goal:** 单词本支持编辑/添加/去重，并提供跨平台全量备份-还原。

**Architecture:** 去重与备份的核心都抽成纯函数（`dedupDrafts`、`BackupCodec.encode/decode/merge`）双端各实现 +
单测；IO/UI 薄层落库与交互。备份 JSON 平台中立、不导 id、靠内容嵌套重建关系。

**Tech Stack:** Android Kotlin/Room/Compose/Hilt + JUnit；iOS Swift/SwiftData/SwiftUI + XCTest。
JSON：Android 用 `org.json`（已在依赖）或手写；iOS 用 `Codable`。

---

## Block A —— 编辑 / 添加 / 去重

### Task A1: Android 去重纯函数 + 单测

**Files:**
- Modify: `app/.../feature/wordbook/WordBookViewModel.kt`（顶层加 `dedupDrafts`）
- Test: `app/src/test/.../wordbook/DedupTest.kt`

- [ ] 顶层纯函数：
```kotlin
/** 批次内按英文键（trim+lowercase）去重 + 排除 existingKeys，保持原顺序。 */
fun dedupDrafts(drafts: List<VocabDraft>, existingKeys: Set<String>): List<VocabDraft> {
    val seen = HashSet(existingKeys)
    val out = ArrayList<VocabDraft>()
    for (d in drafts) {
        val k = d.english.trim().lowercase()
        if (k.isEmpty()) continue
        if (seen.add(k)) out.add(d)
    }
    return out
}
```
- [ ] 单测：批次内重复只留首条；命中 existingKeys 跳过；大小写/空白不敏感；空英文丢弃；顺序保持。
- [ ] 接入：`saveBook` 用 `dedupDrafts(drafts, emptySet())`；提取累积草稿处（`_import` 更新点）用
  `dedupDrafts(next, emptySet())` 保证预览不含重复，`extractedCount` 用去重后大小。
- [ ] `./gradlew :app:testDebugUnitTest` 绿；提交。

### Task A2: Android DAO + VM 添加/编辑

**Files:**
- Modify: `app/.../core/data/db/AppDatabase.kt`（WordBookDao）
- Modify: `app/.../feature/wordbook/WordBookViewModel.kt`

- [ ] DAO 加：
```kotlin
@Query("UPDATE word_book SET name=:name, purpose=:purpose, dailyGoal=:dailyGoal WHERE id=:id")
suspend fun updateBook(id: Long, name: String, purpose: String, dailyGoal: Int)
@Query("UPDATE word_entry SET english=:english, chinese=:chinese, note=:note WHERE id=:id")
suspend fun updateEntry(id: Long, english: String, chinese: String, note: String)
```
- [ ] VM 加（新增词条都建 SRS 卡，复用 saveBook 里建卡逻辑；删词已删卡）：
  - `addExtractedToBook(bookId: Long, onDone: ()->Unit)`：取 `_import.drafts`，
    `existingKeys = dao.entriesOnce(bookId).map{it.english.trim().lowercase()}.toSet()`，
    `dedupDrafts(drafts, existingKeys)` → insertEntries → 查回新词 → insertAll 复习卡 → resetImport → onDone。
  - `addManualEntry(bookId, en, zh, note): Boolean`（suspend 或回调）：en/zh 不可空；
    若 `en.trim().lowercase()` 已在本内 → 返回 false；否则插入 1 条 + 建卡 → true。
  - `updateEntry(id, en, zh, note)` / `updateBook(id, name, purpose, dailyGoal)`：校验后调 DAO。
- [ ] 编译 + 现有单测绿；提交。

### Task A3: Android BookDetail UI（添加/编辑弹窗）

**Files:** Modify `app/.../feature/wordbook/WordBookScreens.kt` + `res/values*/strings.xml`

- [ ] 名称行加「编辑」铅笔 IconButton → 本信息编辑弹窗（name/purpose/dailyGoal 三输入，保存调 `updateBook`）。
- [ ] 「添加词」按钮（与今日复习/全量抽查同排或新一排）→ 弹窗，顶部分段 [提取/手动]：
  - 提取段：多行文本框 + 「提取」（调 vm 现有提取，针对粘贴文本——复用 ImportDialog 的提取入口，
    可抽 `extractFromText(text)`）→ 预览草稿可删 → 「保存到本」调 `addExtractedToBook(book.id)`。
  - 手动段：英/中/备注 + 「添加」→ `addManualEntry`；返回 false 时 Toast/提示「已存在」。
- [ ] 列表行点击 → 词条编辑弹窗（en/zh/note + 删除），保存调 `updateEntry`、删除调 `deleteEntry`。
- [ ] 弹窗关闭后 `stats` 刷新（已有 `learnVm.bookStats`）。新增/改名后 entries 流自动更新（已缓存流）。
- [ ] 字符串中英双语；编译；vivo 直装人测前先编译通过。提交。

### Task A4: iOS 编辑/添加/去重（镜像 A1-A3）

**Files:** Modify `ios/Yiren/Data/Store.swift`、`ios/.../Features/Learn/SrsStore.swift`、
`ios/.../Features/WordBook/WordBookViews.swift`；Test `ios/YirenTests/...`（dedup）。新文件需 `xcodegen generate`。

- [ ] `dedupDrafts(_ drafts:[VocabDraft], existingKeys:Set<String>) -> [VocabDraft]` 纯函数 + XCTest。
- [ ] WordBook 编辑：直接改 `book.name/purpose/dailyGoal` + save；词条编辑改 `entry.english/chinese/note` + save。
- [ ] 添加：提取（复用导入提取，对粘贴文本）→ dedup vs 本内 → 插入 WordEntry + `SrsStore.addCard`；
  手动单条同理，重复返回提示。
- [ ] WordBookDetailView：本信息编辑 sheet、添加词 sheet（提取/手动 Picker 分段）、词条编辑 sheet（点行）。
- [ ] 模拟器跑测试绿；设备构建通过。提交。

---

## Block B —— 全量备份 / 还原

### Task B1: Android BackupCodec 纯函数 + 单测

**Files:** Create `app/.../feature/backup/BackupCodec.kt`、`app/.../feature/backup/BackupModels.kt`；
Test `app/src/test/.../backup/BackupCodecTest.kt`

- [ ] 数据类镜像 spec B0：`Backup`（format/version/exportedAt/platform）、`BackupBook`/`BackupEntry`/`BackupSrs`/
  `BackupTranslation`/`BackupChat`/`BackupMessage`/`BackupConfig`。`Snapshot`（当前库全量，供 encode）。
- [ ] `encode(snapshot): String`（org.json，稳定字段顺序）。
- [ ] `decode(json): Result<Backup>`：校验 format=="yiren-backup" && version<=1，否则失败。
- [ ] `merge(current: Snapshot, backup: Backup): MergePlan` 纯函数：按 spec B2 去重，返回「要新增什么」
  （new books、每本 new entries、new translations、new chats、config 套用值、各计数）。键：本=name.trim、
  词=english.trim.lowercase、翻译=sourceText|sourceLang|targetLang、会话=id。
- [ ] 单测：round-trip（encode→decode 字段等价）；merge 同名本合并词、英文键去重、翻译键去重、chat id 去重、
  空备份=0 增量、坏 JSON/错 format/高 version 被拒。
- [ ] `:app:testDebugUnitTest` 绿；提交。

### Task B2: Android 备份 IO + 设置入口

**Files:** Modify `WordBookDao`/新增聚合查询或 DAO、`app/.../feature/backup/BackupViewModel.kt`（新）、
设置界面 Compose（`feature/settings/...`）、strings.xml

- [ ] 全量读取：books+entries+cards、translations all、chat sessions + messages、AppPreferences 快照 → `Snapshot`。
- [ ] 导出：`BackupViewModel.buildJson(): String`；Compose 用 `ACTION_CREATE_DOCUMENT`
  (`rememberLauncherForActivityResult(CreateDocument("application/json"))`) 写入 OutputStream。
  默认名 `译人备份-yyyyMMdd-HHmm.json`。
- [ ] 导入：`OpenDocument(arrayOf("application/json"))` 读 InputStream → `decode` → `merge` → 落库
  （books/entries/cards/translations/chats/config，按 MergePlan，批量/事务）→ 结果计数提示。
  解析失败提示「无法识别的备份文件」。
- [ ] 设置页加「备份与还原」区：导出、导入两按钮 + 简短说明；双语字符串。
- [ ] 编译 + 单测绿；提交。

### Task B3: iOS BackupCodec + 单测（与 B1 互通）

**Files:** Create `ios/Yiren/Features/Backup/BackupCodec.swift`；Test `ios/YirenTests/BackupCodecTests.swift`

- [ ] `Codable` 结构与 B0 字段**逐字对齐**（JSON 互通是硬约束：key 名、嵌套、srs 可空、platform="ios"）。
- [ ] `encode(_ snapshot) -> Data`（`JSONEncoder`，`outputFormatting=.sortedKeys`）、
  `decode(_ data) -> Result<Backup>`（校验 format/version）、`merge(current, backup) -> MergePlan` 同语义。
- [ ] XCTest：round-trip + merge 去重各项 + **跨端兼容**（解码一份 Android 写的样例 JSON 字符串常量，
  字段能读出）。
- [ ] 模拟器测试绿；提交。

### Task B4: iOS 备份 IO + 设置入口

**Files:** Modify `ios/.../Features/Settings/SettingsView.swift`(+VM)、读取 SwiftData 全量

- [ ] 全量读取 SwiftData → Snapshot → `encode`。导出：`UIActivityViewController` 分享 `译人备份-….json`
  （写临时文件再分享）。
- [ ] 导入：`.fileImporter`（`[.json]`）→ 读 Data → decode → merge → 落库（建 WordBook/WordEntry/
  ReviewCard/TranslationRecord/ChatSession/ChatMessage，套配置）→ 计数提示；失败提示。
- [ ] 设置页「备份与还原」区（与 Android 对齐文案）。
- [ ] 设备构建通过；提交。

---

## 收尾

- [ ] 双端构建 + 全量单测绿。
- [ ] 最终整体审查（一个 reviewer 子代理过 diff，重点：JSON 双端字段一致、merge 去重正确、新增词建卡/删词删卡不漂、UI 不再闪）。
- [ ] 真机直装（vivo + iPhone）交付用户验收；通过后合 main、更新 CHANGELOG/README/待修复清单、发 v1.0.15。

## 自检

- 覆盖 spec：A0-A2 去重、A1 添加两式、A2 编辑两级、B0-B3 备份格式/导出/还原/校验 —— 均有任务。✅
- 类型一致：`dedupDrafts(drafts, existingKeys)`、`BackupCodec.encode/decode/merge`、`MergePlan` 在 A/B 任务间命名统一。✅
- 跨端 JSON：B1 与 B3 字段逐字对齐，B3 含跨端解码测试。✅
