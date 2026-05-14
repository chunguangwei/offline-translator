package com.offlinetranslator.app.core.data.db

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: Long,
    val modelId: String,
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

    @Query("DELETE FROM chat_session WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_message WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)
}

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
