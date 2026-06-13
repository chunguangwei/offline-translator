package com.offlinetranslator.app.core.data.db

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: Long,
    val modelId: String,
    /** 旧消息的压缩摘要（长会话上下文压缩），覆盖前 summarizedCount 条消息。 */
    val summary: String? = null,
    val summarizedCount: Int = 0,
)

@Entity(tableName = "chat_message")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String, // "user" | "assistant"
    val content: String,
    val thinking: String? = null,
    val imageUri: String? = null,
    val createdAt: Long,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_session ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_session ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_session WHERE id = :id")
    suspend fun getSession(id: String): ChatSessionEntity?

    /** 只更新元信息，避免 REPLACE 把 summary 字段抹掉。 */
    @Query("UPDATE chat_session SET title = :title, updatedAt = :updatedAt, modelId = :modelId WHERE id = :id")
    suspend fun updateSessionMeta(id: String, title: String, updatedAt: Long, modelId: String)

    @Query("UPDATE chat_session SET summary = :summary, summarizedCount = :count WHERE id = :id")
    suspend fun updateSummary(id: String, summary: String, count: Int)

    /** 只刷新活跃时间（暂停落库等场景，把会话顶到列表最前）。 */
    @Query("UPDATE chat_session SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchSession(id: String, updatedAt: Long)

    @Query("DELETE FROM chat_session WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun messagesOnce(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_message WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)
}

@Entity(tableName = "translation_history")
data class TranslationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String, // "ZH" | "EN"
    val targetLang: String,
    val createdAt: Long,
    /** 收藏进生词本（单词练习用）。 */
    val starred: Boolean = false,
)

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translation_history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TranslationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TranslationEntity): Long

    /** 去重：同一原文+方向只留最新一条（插入前调用）。 */
    @Query(
        "DELETE FROM translation_history WHERE sourceText = :sourceText " +
            "AND sourceLang = :sourceLang AND targetLang = :targetLang"
    )
    suspend fun deleteDuplicates(sourceText: String, sourceLang: String, targetLang: String)

    /** 收藏/取消收藏（生词本）。 */
    @Query("UPDATE translation_history SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM translation_history")
    suspend fun clearAll()
}

/** v1→v2：新增翻译历史表（保留现有 chat 数据，不走 destructive）。 */
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

/** v2→v3：会话表加上下文压缩字段。 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_session ADD COLUMN summary TEXT")
        db.execSQL("ALTER TABLE chat_session ADD COLUMN summarizedCount INTEGER NOT NULL DEFAULT 0")
    }
}

/** v3→v4：翻译历史加生词本收藏列。 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE translation_history ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
    }
}

// ───────────────────────── 单词本（用户上传文本 → AI 提取） ─────────────────────────

@Entity(tableName = "word_book")
data class WordBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 用途说明（可选，如"考研核心词"）。 */
    val purpose: String = "",
    /** 每日学习量（5/10/20/30）。 */
    val dailyGoal: Int = 10,
    val createdAt: Long,
)

@Entity(tableName = "word_entry")
data class WordEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val english: String,
    val chinese: String,
    /** 注释：词性/例句/用法，可空。 */
    val note: String = "",
    /** 熟练度 0..3：测试「认识」+1、「不认识」归 0；≥3 视为已掌握。 */
    val proficiency: Int = 0,
    val lastSeenAt: Long = 0,
    val createdAt: Long,
)

@Dao
interface WordBookDao {
    @Query("SELECT * FROM word_book ORDER BY createdAt DESC")
    fun observeBooks(): Flow<List<WordBookEntity>>

    @Insert
    suspend fun insertBook(book: WordBookEntity): Long

    @Query("DELETE FROM word_book WHERE id = :id")
    suspend fun deleteBook(id: Long)

    @Query("SELECT * FROM word_entry WHERE bookId = :bookId ORDER BY createdAt ASC")
    fun observeEntries(bookId: Long): Flow<List<WordEntryEntity>>

    @Query("SELECT * FROM word_entry WHERE bookId = :bookId")
    suspend fun entriesOnce(bookId: Long): List<WordEntryEntity>

    @Insert
    suspend fun insertEntries(entries: List<WordEntryEntity>)

    @Query("DELETE FROM word_entry WHERE bookId = :bookId")
    suspend fun deleteEntries(bookId: Long)

    @Query("DELETE FROM word_entry WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    /** 测试自评后更新熟练度。 */
    @Query("UPDATE word_entry SET proficiency = :proficiency, lastSeenAt = :seenAt WHERE id = :id")
    suspend fun updateProficiency(id: Long, proficiency: Int, seenAt: Long)

    @Query("SELECT COUNT(*) FROM word_entry WHERE bookId = :bookId")
    suspend fun countEntries(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM word_entry WHERE bookId = :bookId AND proficiency >= 3")
    suspend fun countMastered(bookId: Long): Int
}

/** v4→v5：新增单词本两表。 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS word_book (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, purpose TEXT NOT NULL, " +
                "dailyGoal INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS word_entry (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "bookId INTEGER NOT NULL, english TEXT NOT NULL, chinese TEXT NOT NULL, " +
                "note TEXT NOT NULL, proficiency INTEGER NOT NULL, " +
                "lastSeenAt INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
        )
    }
}

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

@Database(
    entities = [
        ChatSessionEntity::class, ChatMessageEntity::class, TranslationEntity::class,
        WordBookEntity::class, WordEntryEntity::class,
        ReviewCardEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun translationDao(): TranslationDao
    abstract fun wordBookDao(): WordBookDao
    abstract fun reviewCardDao(): ReviewCardDao
}
