import Foundation

// 跨平台备份数据模型（JSON v1）。字段名与嵌套结构即为权威格式，
// 与安卓端 com.offlinetranslator.app.feature.backup 逐字段镜像，切勿擅自改名。

struct BackupSrs: Codable, Equatable {
    let box: Int
    let dueAt: Int64
    let missCount: Int
    let lastReviewedAt: Int64
}

struct BackupEntry: Codable, Equatable {
    let english: String
    let chinese: String
    let note: String
    let createdAt: Int64
    let srs: BackupSrs?
}

struct BackupBook: Codable, Equatable {
    let name: String
    let purpose: String
    let dailyGoal: Int
    let createdAt: Int64
    let entries: [BackupEntry]

    enum CodingKeys: String, CodingKey {
        case name, purpose, dailyGoal, createdAt, entries
    }

    init(name: String, purpose: String, dailyGoal: Int, createdAt: Int64, entries: [BackupEntry]) {
        self.name = name
        self.purpose = purpose
        self.dailyGoal = dailyGoal
        self.createdAt = createdAt
        self.entries = entries
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        purpose = try c.decodeIfPresent(String.self, forKey: .purpose) ?? ""
        dailyGoal = try c.decodeIfPresent(Int.self, forKey: .dailyGoal) ?? 0
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        entries = try c.decodeIfPresent([BackupEntry].self, forKey: .entries) ?? []
    }
}

struct BackupTranslation: Codable, Equatable {
    let sourceText: String
    let translatedText: String
    let sourceLang: String
    let targetLang: String
    let createdAt: Int64
    let starred: Bool
    let srs: BackupSrs?
}

struct BackupMessage: Codable, Equatable {
    let role: String
    let content: String
    let createdAt: Int64
}

struct BackupChat: Codable, Equatable {
    let id: String
    let title: String
    let updatedAt: Int64
    let modelId: String
    let summary: String?
    let summarizedCount: Int
    let messages: [BackupMessage]

    enum CodingKeys: String, CodingKey {
        case id, title, updatedAt, modelId, summary, summarizedCount, messages
    }

    init(id: String, title: String, updatedAt: Int64, modelId: String, summary: String?, summarizedCount: Int, messages: [BackupMessage]) {
        self.id = id
        self.title = title
        self.updatedAt = updatedAt
        self.modelId = modelId
        self.summary = summary
        self.summarizedCount = summarizedCount
        self.messages = messages
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        modelId = try c.decodeIfPresent(String.self, forKey: .modelId) ?? ""
        summary = try c.decodeIfPresent(String.self, forKey: .summary)
        summarizedCount = try c.decodeIfPresent(Int.self, forKey: .summarizedCount) ?? 0
        messages = try c.decodeIfPresent([BackupMessage].self, forKey: .messages) ?? []
    }
}

struct BackupConfig: Codable, Equatable {
    let reminderEnabled: Bool
    let reminderHour: Int
    let reminderMinute: Int
    let uiLanguage: String?
    let activeModelId: String?
    let streakLastDay: Int64
    let streakCurrent: Int
    let streakLongest: Int
}

struct Backup: Codable, Equatable {
    let format: String
    let version: Int
    let exportedAt: Int64
    let platform: String
    let wordBooks: [BackupBook]
    let translations: [BackupTranslation]
    let chats: [BackupChat]
    let config: BackupConfig?

    enum CodingKeys: String, CodingKey {
        case format, version, exportedAt, platform, wordBooks, translations, chats, config
    }

    init(format: String, version: Int, exportedAt: Int64, platform: String,
         wordBooks: [BackupBook], translations: [BackupTranslation], chats: [BackupChat],
         config: BackupConfig?) {
        self.format = format
        self.version = version
        self.exportedAt = exportedAt
        self.platform = platform
        self.wordBooks = wordBooks
        self.translations = translations
        self.chats = chats
        self.config = config
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        format = try c.decodeIfPresent(String.self, forKey: .format) ?? ""
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 0
        exportedAt = try c.decodeIfPresent(Int64.self, forKey: .exportedAt) ?? 0
        platform = try c.decodeIfPresent(String.self, forKey: .platform) ?? ""
        wordBooks = try c.decodeIfPresent([BackupBook].self, forKey: .wordBooks) ?? []
        translations = try c.decodeIfPresent([BackupTranslation].self, forKey: .translations) ?? []
        chats = try c.decodeIfPresent([BackupChat].self, forKey: .chats) ?? []
        config = try c.decodeIfPresent(BackupConfig.self, forKey: .config)
    }
}

struct MergePlan: Equatable {
    let newBooks: [BackupBook]
    let entriesToExistingBook: [String: [BackupEntry]]
    let newTranslations: [BackupTranslation]
    let newChats: [BackupChat]
    let resolvedConfig: BackupConfig?
    let addedBooks: Int
    let addedEntries: Int
    let addedTranslations: Int
    let addedChats: Int
}

let backupFormat = "yiren-backup"
let backupVersion = 1

struct BackupError: LocalizedError, Equatable {
    let message: String
    var errorDescription: String? { message }
}

enum BackupCodec {

    // ---------- encode ----------

    static func encode(_ backup: Backup) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        // nil 可选字段（srs/summary/uiLanguage/activeModelId/config）由合成 Codable 自动省略，不会写成 null。
        return (try? encoder.encode(backup)) ?? Data()
    }

    // ---------- decode ----------

    static func decode(_ data: Data) -> Result<Backup, Error> {
        let decoder = JSONDecoder()
        let backup: Backup
        do {
            backup = try decoder.decode(Backup.self, from: data)
        } catch {
            return .failure(BackupError(message: "无法识别的备份文件"))
        }
        guard backup.format == backupFormat, backup.version == backupVersion else {
            return .failure(BackupError(message: "无法识别的备份文件"))
        }
        return .success(backup)
    }

    // ---------- merge ----------

    static func merge(current: Backup, incoming: Backup) -> MergePlan {
        // Books by trimmed name.
        var currentBooksByKey: [String: BackupBook] = [:]
        for book in current.wordBooks {
            currentBooksByKey[book.name.trimmingCharacters(in: .whitespaces)] = book
        }

        var newBooks: [BackupBook] = []
        var entriesToExistingBook: [String: [BackupEntry]] = [:]

        for book in incoming.wordBooks {
            let key = book.name.trimmingCharacters(in: .whitespaces)
            guard let existing = currentBooksByKey[key] else {
                newBooks.append(book)
                continue
            }
            let existingEntryKeys = Set(existing.entries.map {
                $0.english.trimmingCharacters(in: .whitespaces).lowercased()
            })
            let toAdd = book.entries.filter {
                !existingEntryKeys.contains($0.english.trimmingCharacters(in: .whitespaces).lowercased())
            }
            if !toAdd.isEmpty {
                entriesToExistingBook[book.name] = toAdd
            }
        }

        // Translations by (sourceText, sourceLang, targetLang).
        let currentTransKeys = Set(current.translations.map {
            transKey($0.sourceText, $0.sourceLang, $0.targetLang)
        })
        let newTranslations = incoming.translations.filter {
            !currentTransKeys.contains(transKey($0.sourceText, $0.sourceLang, $0.targetLang))
        }

        // Chats by id.
        let currentChatIds = Set(current.chats.map { $0.id })
        let newChats = incoming.chats.filter { !currentChatIds.contains($0.id) }

        // Config / streak resolution.
        let resolvedConfig: BackupConfig?
        if let inc = incoming.config {
            let cur = current.config
            let resolvedLongest = max(cur?.streakLongest ?? 0, inc.streakLongest)
            let resolvedLastDay: Int64
            let resolvedCurrent: Int
            if cur == nil || inc.streakLastDay > cur!.streakLastDay {
                resolvedLastDay = inc.streakLastDay
                resolvedCurrent = inc.streakCurrent
            } else {
                resolvedLastDay = cur!.streakLastDay
                resolvedCurrent = cur!.streakCurrent
            }
            resolvedConfig = BackupConfig(
                reminderEnabled: inc.reminderEnabled,
                reminderHour: inc.reminderHour,
                reminderMinute: inc.reminderMinute,
                uiLanguage: inc.uiLanguage,
                activeModelId: inc.activeModelId,
                streakLastDay: resolvedLastDay,
                streakCurrent: resolvedCurrent,
                streakLongest: resolvedLongest
            )
        } else {
            resolvedConfig = nil
        }

        let addedEntries = newBooks.reduce(0) { $0 + $1.entries.count } +
            entriesToExistingBook.values.reduce(0) { $0 + $1.count }

        return MergePlan(
            newBooks: newBooks,
            entriesToExistingBook: entriesToExistingBook,
            newTranslations: newTranslations,
            newChats: newChats,
            resolvedConfig: resolvedConfig,
            addedBooks: newBooks.count,
            addedEntries: addedEntries,
            addedTranslations: newTranslations.count,
            addedChats: newChats.count
        )
    }

    private static func transKey(_ sourceText: String, _ sourceLang: String, _ targetLang: String) -> String {
        "\(sourceText) \(sourceLang) \(targetLang)"
    }
}
