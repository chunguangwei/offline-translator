package com.offlinetranslator.app.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private fun sampleBackup(): Backup = Backup(
        format = BACKUP_FORMAT,
        version = BACKUP_VERSION,
        exportedAt = 1718200000000L,
        platform = "android",
        wordBooks = listOf(
            BackupBook(
                name = "考研",
                purpose = "",
                dailyGoal = 10,
                createdAt = 1710000000000L,
                entries = listOf(
                    BackupEntry(
                        english = "abandon",
                        chinese = "放弃",
                        note = "",
                        createdAt = 1710000000000L,
                        srs = BackupSrs(box = 2, dueAt = 1718300000000L, missCount = 1, lastReviewedAt = 1718100000000L),
                    ),
                    BackupEntry(
                        english = "ability",
                        chinese = "能力",
                        note = "n.",
                        createdAt = 1710000001000L,
                        srs = null,
                    ),
                ),
            ),
        ),
        translations = listOf(
            BackupTranslation(
                sourceText = "hello",
                translatedText = "你好",
                sourceLang = "EN",
                targetLang = "ZH",
                createdAt = 1710000000000L,
                starred = true,
                srs = BackupSrs(box = 0, dueAt = 1718000000000L, missCount = 0, lastReviewedAt = 0L),
            ),
            BackupTranslation(
                sourceText = "world",
                translatedText = "世界",
                sourceLang = "EN",
                targetLang = "ZH",
                createdAt = 1710000002000L,
                starred = false,
                srs = null,
            ),
        ),
        chats = listOf(
            BackupChat(
                id = "uuid-1",
                title = "t",
                updatedAt = 1710000000000L,
                modelId = "m",
                summary = null,
                summarizedCount = 0,
                messages = listOf(
                    BackupMessage(role = "user", content = "hi", createdAt = 1710000000000L),
                    BackupMessage(role = "assistant", content = "hello there", createdAt = 1710000001000L),
                ),
            ),
        ),
        config = BackupConfig(
            reminderEnabled = true,
            reminderHour = 20,
            reminderMinute = 0,
            uiLanguage = null,
            activeModelId = "m",
            streakLastDay = 20240601L,
            streakCurrent = 5,
            streakLongest = 12,
        ),
    )

    @Test fun `round-trip 完整往返相等`() {
        val b = sampleBackup()
        val decoded = BackupCodec.decode(BackupCodec.encode(b))
        assertTrue(decoded.isSuccess)
        assertEquals(b, decoded.getOrNull())
    }

    @Test fun `round-trip null 字段往返仍为 null`() {
        val b = sampleBackup()
        val decoded = BackupCodec.decode(BackupCodec.encode(b)).getOrThrow()
        // entry without srs
        assertNull(decoded.wordBooks[0].entries[1].srs)
        // non-starred translation without srs
        assertNull(decoded.translations[1].srs)
        // chat summary null, config uiLanguage null
        assertNull(decoded.chats[0].summary)
        assertNull(decoded.config!!.uiLanguage)
    }

    @Test fun `decode 拒绝非法 JSON`() {
        assertTrue(BackupCodec.decode("not json {{{").isFailure)
    }

    @Test fun `decode 拒绝错误 format`() {
        val json = """{"format":"other","version":1}"""
        assertTrue(BackupCodec.decode(json).isFailure)
    }

    @Test fun `decode 拒绝 version 2`() {
        val json = """{"format":"yiren-backup","version":2}"""
        assertTrue(BackupCodec.decode(json).isFailure)
    }

    @Test fun `decode 拒绝 version 0`() {
        val json = """{"format":"yiren-backup","version":0}"""
        assertTrue(BackupCodec.decode(json).isFailure)
    }

    @Test fun `decode 容忍最小文档`() {
        val json = """{"format":"yiren-backup","version":1}"""
        val r = BackupCodec.decode(json)
        assertTrue(r.isSuccess)
        val b = r.getOrThrow()
        assertTrue(b.wordBooks.isEmpty())
        assertTrue(b.translations.isEmpty())
        assertTrue(b.chats.isEmpty())
        assertNull(b.config)
    }

    @Test fun `merge 同名书追加新词条且大小写去重`() {
        val current = Backup(
            format = BACKUP_FORMAT, version = 1, exportedAt = 0L, platform = "android",
            wordBooks = listOf(
                BackupBook("考研", "", 10, 0L, listOf(
                    BackupEntry("Abandon", "放弃", "", 0L, null),
                )),
            ),
            translations = emptyList(), chats = emptyList(), config = null,
        )
        val incoming = Backup(
            format = BACKUP_FORMAT, version = 1, exportedAt = 0L, platform = "ios",
            wordBooks = listOf(
                BackupBook(" 考研 ", "", 10, 0L, listOf(
                    BackupEntry("abandon", "放弃", "", 0L, null), // dup (case-insensitive, trimmed key)
                    BackupEntry("ability", "能力", "", 0L, null), // new
                )),
            ),
            translations = emptyList(), chats = emptyList(), config = null,
        )
        val plan = BackupCodec.merge(current, incoming)
        assertTrue(plan.newBooks.isEmpty())
        assertEquals(1, plan.entriesToExistingBook.size)
        val added = plan.entriesToExistingBook[" 考研 "]!!
        assertEquals(1, added.size)
        assertEquals("ability", added[0].english)
        assertEquals(0, plan.addedBooks)
        assertEquals(1, plan.addedEntries)
    }

    @Test fun `merge 不同名书整本新建`() {
        val current = Backup(
            format = BACKUP_FORMAT, version = 1, exportedAt = 0L, platform = "android",
            wordBooks = emptyList(), translations = emptyList(), chats = emptyList(), config = null,
        )
        val incoming = Backup(
            format = BACKUP_FORMAT, version = 1, exportedAt = 0L, platform = "ios",
            wordBooks = listOf(
                BackupBook("四级", "", 5, 0L, listOf(
                    BackupEntry("apple", "苹果", "", 0L, null),
                    BackupEntry("banana", "香蕉", "", 0L, null),
                )),
            ),
            translations = emptyList(), chats = emptyList(), config = null,
        )
        val plan = BackupCodec.merge(current, incoming)
        assertEquals(1, plan.newBooks.size)
        assertEquals(1, plan.addedBooks)
        assertEquals(2, plan.addedEntries)
        assertTrue(plan.entriesToExistingBook.isEmpty())
    }

    @Test fun `merge 翻译与对话按键去重`() {
        val current = Backup(
            format = BACKUP_FORMAT, version = 1, exportedAt = 0L, platform = "android",
            wordBooks = emptyList(),
            translations = listOf(
                BackupTranslation("hello", "你好", "EN", "ZH", 0L, false, null),
            ),
            chats = listOf(
                BackupChat("id-1", "a", 0L, "m", null, 0, emptyList()),
            ),
            config = null,
        )
        val incoming = Backup(
            format = BACKUP_FORMAT, version = 1, exportedAt = 0L, platform = "ios",
            wordBooks = emptyList(),
            translations = listOf(
                BackupTranslation("hello", "你好啊", "EN", "ZH", 0L, false, null), // dup key
                BackupTranslation("hello", "你好", "EN", "JA", 0L, false, null),   // new (diff lang)
            ),
            chats = listOf(
                BackupChat("id-1", "a2", 0L, "m", null, 0, emptyList()), // dup id
                BackupChat("id-2", "b", 0L, "m", null, 0, emptyList()),  // new
            ),
            config = null,
        )
        val plan = BackupCodec.merge(current, incoming)
        assertEquals(1, plan.addedTranslations)
        assertEquals("JA", plan.newTranslations[0].targetLang)
        assertEquals(1, plan.addedChats)
        assertEquals("id-2", plan.newChats[0].id)
    }

    @Test fun `merge 空 incoming 全零计划`() {
        val current = sampleBackup()
        val incoming = Backup(
            format = BACKUP_FORMAT, version = 1, exportedAt = 0L, platform = "ios",
            wordBooks = emptyList(), translations = emptyList(), chats = emptyList(), config = null,
        )
        val plan = BackupCodec.merge(current, incoming)
        assertEquals(0, plan.addedBooks)
        assertEquals(0, plan.addedEntries)
        assertEquals(0, plan.addedTranslations)
        assertEquals(0, plan.addedChats)
        assertNull(plan.resolvedConfig)
    }

    @Test fun `merge streak 取较晚 lastDay 与较大 longest`() {
        val current = sampleBackup().copy(
            config = BackupConfig(
                reminderEnabled = false, reminderHour = 8, reminderMinute = 0,
                uiLanguage = "zh", activeModelId = "old",
                streakLastDay = 20240610L, streakCurrent = 3, streakLongest = 20,
            ),
        )
        // incoming later lastDay → incoming streak wins; longest = max(20, 12) = 20
        val incoming = sampleBackup().copy(
            config = BackupConfig(
                reminderEnabled = true, reminderHour = 21, reminderMinute = 30,
                uiLanguage = "en", activeModelId = "new",
                streakLastDay = 20240615L, streakCurrent = 7, streakLongest = 12,
            ),
        )
        val plan = BackupCodec.merge(current, incoming)
        val cfg = plan.resolvedConfig!!
        assertEquals(20240615L, cfg.streakLastDay)
        assertEquals(7, cfg.streakCurrent)
        assertEquals(20, cfg.streakLongest)
        // reminder/ui/model from incoming
        assertTrue(cfg.reminderEnabled)
        assertEquals(21, cfg.reminderHour)
        assertEquals("en", cfg.uiLanguage)
        assertEquals("new", cfg.activeModelId)
    }

    @Test fun `merge streak current 较早 lastDay 用 current`() {
        val current = sampleBackup().copy(
            config = BackupConfig(
                reminderEnabled = false, reminderHour = 8, reminderMinute = 0,
                uiLanguage = null, activeModelId = null,
                streakLastDay = 20240620L, streakCurrent = 9, streakLongest = 30,
            ),
        )
        val incoming = sampleBackup().copy(
            config = BackupConfig(
                reminderEnabled = true, reminderHour = 21, reminderMinute = 30,
                uiLanguage = "en", activeModelId = "new",
                streakLastDay = 20240615L, streakCurrent = 7, streakLongest = 12,
            ),
        )
        val plan = BackupCodec.merge(current, incoming)
        val cfg = plan.resolvedConfig!!
        assertEquals(20240620L, cfg.streakLastDay)
        assertEquals(9, cfg.streakCurrent)
        assertEquals(30, cfg.streakLongest)
        assertFalse(plan.resolvedConfig === incoming.config)
    }
}
