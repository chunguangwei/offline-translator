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

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class, TranslationEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun translationDao(): TranslationDao
}
