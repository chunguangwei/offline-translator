# 译人 阶段 4：历史 Tab（翻译记录）实现计划

> 隶属设计：`docs/.../specs/2026-06-09-yiren-restructure-design.md` 阶段 4。验证门：编译 + 真机冒烟。提交：真机验证前不入库。

**Goal:** 翻译成功自动存记录；历史 Tab 倒序列表，可复制译文 / 删除单条 / 清空。

**Architecture:** 现有 Room 库新增 `translation_history` 表（`TranslationEntity` + `TranslationDao`），DB 版本 1→2 配**正式 Migration**（保留现有 chat 数据，不走 destructive）。翻译链路成功后写一条记录。`HistoryScreen`+`HistoryViewModel` 读列表。

---

### Task 1: Room 表 + DAO + 迁移
**Files:** Modify `core/data/db/AppDatabase.kt`

- [ ] Step 1: 在 ChatMessageEntity 之后加实体 + DAO
```kotlin
@Entity(tableName = "translation_history")
data class TranslationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String, // "ZH" | "EN"
    val targetLang: String,
    val createdAt: Long,
)

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translation_history ORDER BY createdAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<TranslationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TranslationEntity): Long

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM translation_history")
    suspend fun clearAll()
}
```
- [ ] Step 2: 加迁移（文件顶部 import + 顶层 val）
```kotlin
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
```
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS translation_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "sourceText TEXT NOT NULL, translatedText TEXT NOT NULL, " +
                "sourceLang TEXT NOT NULL, targetLang TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL)"
        )
    }
}
```
- [ ] Step 3: `@Database` 加实体、升版本、加 dao 抽象方法
```kotlin
@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class, TranslationEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun translationDao(): TranslationDao
}
```
- [ ] Step 4: 编译

### Task 2: DI 提供 dao + 挂迁移
**Files:** Modify `di/AppModule.kt`
- [ ] 把 `provideDatabase` 的 builder 加 `.addMigrations(com.offlinetranslator.app.core.data.db.MIGRATION_1_2)`（放在 fallbackToDestructiveMigration 之前），并新增
```kotlin
    @Provides
    fun provideTranslationDao(db: AppDatabase): com.offlinetranslator.app.core.data.db.TranslationDao =
        db.translationDao()
```
- [ ] 编译

### Task 3: 翻译成功写历史
**Files:** Modify `feature/translate/TranslateViewModel.kt`
- [ ] 构造函数注入 `private val translationDao: com.offlinetranslator.app.core.data.db.TranslationDao,`
- [ ] translate() 流式结束的成功处（`_ui.update { it.copy(isTranslating = false) }` 之前/之后）插入：
```kotlin
                val finalOutput = _ui.value.output.trim()
                if (finalOutput.isNotEmpty()) {
                    translationDao.insert(
                        com.offlinetranslator.app.core.data.db.TranslationEntity(
                            sourceText = cur.input.trim(),
                            translatedText = finalOutput,
                            sourceLang = cur.source.name,
                            targetLang = cur.target.name,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
```
- [ ] 编译

### Task 4: HistoryViewModel + HistoryScreen
**Files:** Create `feature/history/HistoryViewModel.kt`, `feature/history/HistoryScreen.kt`
- [ ] HistoryViewModel：`@HiltViewModel` 注入 TranslationDao，暴露 `items: StateFlow<List<TranslationEntity>>`（observeAll stateIn），`delete(id)`、`clear()`。
- [ ] HistoryScreen(padding)：标题 + 「清空」按钮；空态文案；`LazyColumn` 倒序卡片：源→译文、语言对、时间，操作「复制译文」「删除」。复用 GlassCard 风格、暖色。
- [ ] 编译

### Task 5: 字符串 + AppShell 挂载
**Files:** strings(zh/en)、`feature/shell/AppShell.kt`
- [ ] strings：history_title/ history_empty/ history_clear/ history_copy/ history_delete/ history_copied。
- [ ] AppShell：import HistoryScreen，`Route.History` 的 composable 从 Placeholder 换成 `HistoryScreen(padding = innerPadding)`。
- [ ] 编译

### Task 6: 出包 + 冒烟
- [ ] assembleDebug → 桌面；冒烟：翻译几条 → 历史 Tab 倒序出现；复制译文；删除单条；清空；重启 app 仍在（持久化）。

## Self-Review
- 只存翻译（spec 决策）✅；正式迁移不毁 chat ✅；命名一致 `TranslationDao`/`translationDao`/`observeAll`/`MIGRATION_1_2`。
- 四个 Tab 全部真实化后，进入收尾（提交/分支）。
