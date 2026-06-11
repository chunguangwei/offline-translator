import Foundation
import SwiftData

// SwiftData 持久化 —— 对应 Android Room 的三张表（AppDatabase.kt）。

/// 问答会话（对应 ChatSessionEntity）。
@Model
final class ChatSession {
    @Attribute(.unique) var id: String
    var title: String
    var updatedAt: Date
    var modelId: String
    /// 旧消息的压缩摘要（长会话上下文压缩），覆盖前 summarizedCount 条消息。
    var summary: String?
    var summarizedCount: Int

    init(id: String = UUID().uuidString, title: String, updatedAt: Date = .now,
         modelId: String = "", summary: String? = nil, summarizedCount: Int = 0) {
        self.id = id
        self.title = title
        self.updatedAt = updatedAt
        self.modelId = modelId
        self.summary = summary
        self.summarizedCount = summarizedCount
    }
}

/// 问答消息（对应 ChatMessageEntity）。role = "user" | "assistant"。
@Model
final class ChatMessage {
    var sessionId: String
    var role: String
    var content: String
    var imagePath: String?
    var createdAt: Date

    init(sessionId: String, role: String, content: String,
         imagePath: String? = nil, createdAt: Date = .now) {
        self.sessionId = sessionId
        self.role = role
        self.content = content
        self.imagePath = imagePath
        self.createdAt = createdAt
    }
}

/// 翻译历史（对应 TranslationEntity）。lang = "ZH" | "EN"。
@Model
final class TranslationRecord {
    var sourceText: String
    var translatedText: String
    var sourceLang: String
    var targetLang: String
    var createdAt: Date

    init(sourceText: String, translatedText: String,
         sourceLang: String, targetLang: String, createdAt: Date = .now) {
        self.sourceText = sourceText
        self.translatedText = translatedText
        self.sourceLang = sourceLang
        self.targetLang = targetLang
        self.createdAt = createdAt
    }
}

/// 共享容器：App 与 ViewModel 都从这里拿主线程 context。
enum DataStore {
    static let container: ModelContainer = {
        do {
            return try ModelContainer(
                for: ChatSession.self, ChatMessage.self, TranslationRecord.self
            )
        } catch {
            fatalError("SwiftData container init failed: \(error)")
        }
    }()

    @MainActor
    static var context: ModelContext { container.mainContext }
}
