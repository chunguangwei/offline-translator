package com.offlinetranslator.app.feature.backup

/**
 * 跨平台备份数据模型（JSON v1）。字段名与嵌套结构即为权威格式，
 * iOS 端将逐字段镜像，切勿擅自改名。
 */

data class BackupSrs(
    val box: Int,
    val dueAt: Long,
    val missCount: Int,
    val lastReviewedAt: Long,
)

data class BackupEntry(
    val english: String,
    val chinese: String,
    val note: String,
    val createdAt: Long,
    val srs: BackupSrs?,
)

data class BackupBook(
    val name: String,
    val purpose: String,
    val dailyGoal: Int,
    val createdAt: Long,
    val entries: List<BackupEntry>,
)

data class BackupTranslation(
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val createdAt: Long,
    val starred: Boolean,
    val srs: BackupSrs?,
)

data class BackupMessage(
    val role: String,
    val content: String,
    val createdAt: Long,
)

data class BackupChat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val modelId: String,
    val summary: String?,
    val summarizedCount: Int,
    val messages: List<BackupMessage>,
)

data class BackupConfig(
    val reminderEnabled: Boolean,
    val reminderHour: Int,
    val reminderMinute: Int,
    val uiLanguage: String?,
    val activeModelId: String?,
    val streakLastDay: Long,
    val streakCurrent: Int,
    val streakLongest: Int,
)

data class Backup(
    val format: String,        // "yiren-backup"
    val version: Int,          // 1
    val exportedAt: Long,
    val platform: String,      // "android" | "ios"
    val wordBooks: List<BackupBook>,
    val translations: List<BackupTranslation>,
    val chats: List<BackupChat>,
    val config: BackupConfig?,
)

data class MergePlan(
    val newBooks: List<BackupBook>,                            // 名称不存在 → 整本新建
    val entriesToExistingBook: Map<String, List<BackupEntry>>, // 已存在书名 -> 待追加词条
    val newTranslations: List<BackupTranslation>,
    val newChats: List<BackupChat>,
    val resolvedConfig: BackupConfig?,                         // 已解析 streak 后的配置
    val addedBooks: Int,
    val addedEntries: Int,
    val addedTranslations: Int,
    val addedChats: Int,
)

const val BACKUP_FORMAT = "yiren-backup"
const val BACKUP_VERSION = 1
