import Foundation
import SwiftData

// 把 SwiftData 现状 ←→ 跨平台 Backup 模型互转，并执行合并落库。
// 与安卓端备份逐字段互通（同一 JSON）。SwiftData 仅主线程访问，故全 @MainActor。

// 让导出文件 URL 可直接驱动 .sheet(item:)。
extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}

/// 还原结果（计数 / 失败原因）。
enum RestoreResult {
    case success(books: Int, entries: Int, translations: Int, chats: Int)
    case failure(String)
}

@MainActor
enum BackupIO {

    // ───────── 时间戳边界换算 ─────────
    // 备份格式所有时间戳为 epoch 毫秒(Int64)；iOS 模型用 Date。
    // 例外：ReviewCard.dueAt/lastReviewedAt 本就是毫秒(Int64)，原样透传不换算。

    private static func msToDate(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000.0) }
    private static func dateToMs(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000.0).rounded()) }

    // ───────── 导出：现状 → Backup ─────────

    static func buildBackup(_ context: ModelContext) -> Backup {
        // 单词本 + 对应 WORD_ENTRY 调度卡（按 sourceId 索引）。
        let wordCards = (try? context.fetch(FetchDescriptor<ReviewCard>(
            predicate: #Predicate { $0.sourceType == "WORD_ENTRY" }))) ?? []
        var wordCardBySource: [String: ReviewCard] = [:]
        for c in wordCards { wordCardBySource[c.sourceId] = c }

        let books = (try? context.fetch(FetchDescriptor<WordBook>())) ?? []
        let backupBooks: [BackupBook] = books.map { book in
            let entries = book.entries.sorted { $0.createdAt < $1.createdAt }.map { (e: WordEntry) -> BackupEntry in
                let srs = wordCardBySource[e.uid].map {
                    BackupSrs(box: $0.box, dueAt: $0.dueAt, missCount: $0.missCount, lastReviewedAt: $0.lastReviewedAt)
                }
                return BackupEntry(english: e.english, chinese: e.chinese, note: e.note,
                                   createdAt: dateToMs(e.createdAt), srs: srs)
            }
            return BackupBook(name: book.name, purpose: book.purpose, dailyGoal: book.dailyGoal,
                              createdAt: dateToMs(book.createdAt), entries: entries)
        }

        // 翻译历史 + 已星标记录的 STARRED 调度卡。
        let starredCards = (try? context.fetch(FetchDescriptor<ReviewCard>(
            predicate: #Predicate { $0.sourceType == "STARRED" }))) ?? []
        var starredCardBySource: [String: ReviewCard] = [:]
        for c in starredCards { starredCardBySource[c.sourceId] = c }

        let records = (try? context.fetch(FetchDescriptor<TranslationRecord>())) ?? []
        let backupTranslations: [BackupTranslation] = records.map { r in
            let srs: BackupSrs? = (r.starred ? starredCardBySource[r.uid] : nil).map {
                BackupSrs(box: $0.box, dueAt: $0.dueAt, missCount: $0.missCount, lastReviewedAt: $0.lastReviewedAt)
            }
            return BackupTranslation(sourceText: r.sourceText, translatedText: r.translatedText,
                                     sourceLang: r.sourceLang, targetLang: r.targetLang,
                                     createdAt: dateToMs(r.createdAt), starred: r.starred, srs: srs)
        }

        // 问答会话 + 其消息（imagePath 为设备本地路径，不导出）。
        let sessions = (try? context.fetch(FetchDescriptor<ChatSession>())) ?? []
        let backupChats: [BackupChat] = sessions.map { s in
            let sid = s.id
            let msgs = ((try? context.fetch(FetchDescriptor<ChatMessage>(
                predicate: #Predicate { $0.sessionId == sid }))) ?? [])
                .sorted { $0.createdAt < $1.createdAt }
                .map { BackupMessage(role: $0.role, content: $0.content, createdAt: dateToMs($0.createdAt)) }
            return BackupChat(id: s.id, title: s.title, updatedAt: dateToMs(s.updatedAt),
                              modelId: s.modelId, summary: s.summary,
                              summarizedCount: s.summarizedCount, messages: msgs)
        }

        let config = currentConfig()

        return Backup(format: backupFormat, version: backupVersion,
                      exportedAt: dateToMs(Date()), platform: "ios",
                      wordBooks: backupBooks, translations: backupTranslations,
                      chats: backupChats, config: config)
    }

    // 从当前设置读取 config。uiLanguage 在 iOS 由系统级 per-app 语言决定、无独立可写偏好，故导出 nil。
    private static func currentConfig() -> BackupConfig {
        let d = UserDefaults.standard
        return BackupConfig(
            reminderEnabled: d.bool(forKey: "reminderEnabled"),
            reminderHour: d.object(forKey: "reminderHour") != nil ? d.integer(forKey: "reminderHour") : 20,
            reminderMinute: d.integer(forKey: "reminderMinute"),
            uiLanguage: nil,
            activeModelId: ModelStorage.shared.activeModelId,
            streakLastDay: Int64(d.integer(forKey: "streakLastDay")),
            streakCurrent: d.integer(forKey: "streakCurrent"),
            streakLongest: d.integer(forKey: "streakLongest")
        )
    }

    // ───────── 导入：解码 → 合并 → 落库 ─────────

    static func restore(_ context: ModelContext, json: Data) -> RestoreResult {
        let incoming: Backup
        switch BackupCodec.decode(json) {
        case .failure: return .failure("无法识别的备份文件")
        case .success(let b): incoming = b
        }

        let current = buildBackup(context)
        let plan = BackupCodec.merge(current: current, incoming: incoming)

        // 新单词本（连同词条 + 调度卡）。
        for bb in plan.newBooks {
            let book = WordBook(name: bb.name, purpose: bb.purpose, dailyGoal: bb.dailyGoal)
            book.createdAt = msToDate(bb.createdAt)
            context.insert(book)
            addEntries(context, book, bb.entries)
        }

        // 既有本追加词条（按 trim 后名匹配）。
        if !plan.entriesToExistingBook.isEmpty {
            let books = (try? context.fetch(FetchDescriptor<WordBook>())) ?? []
            for (bookName, entries) in plan.entriesToExistingBook {
                let key = bookName.trimmingCharacters(in: .whitespaces)
                guard let book = books.first(where: {
                    $0.name.trimmingCharacters(in: .whitespaces) == key
                }) else { continue }
                addEntries(context, book, entries)
            }
        }

        // 新翻译记录（已星标且带 srs 的补 STARRED 卡）。
        for bt in plan.newTranslations {
            let r = TranslationRecord(sourceText: bt.sourceText, translatedText: bt.translatedText,
                                      sourceLang: bt.sourceLang, targetLang: bt.targetLang)
            r.createdAt = msToDate(bt.createdAt)
            r.starred = bt.starred
            context.insert(r)
            if bt.starred, let srs = bt.srs {
                context.insert(ReviewCard(sourceType: "STARRED", sourceId: r.uid,
                                          box: srs.box, dueAt: srs.dueAt,
                                          missCount: srs.missCount, lastReviewedAt: srs.lastReviewedAt))
            }
        }

        // 新问答会话 + 消息。
        for bc in plan.newChats {
            let s = ChatSession(id: bc.id, title: bc.title, modelId: bc.modelId,
                                summary: bc.summary, summarizedCount: bc.summarizedCount)
            s.updatedAt = msToDate(bc.updatedAt)
            context.insert(s)
            for bm in bc.messages {
                let m = ChatMessage(sessionId: bc.id, role: bm.role, content: bm.content)
                m.createdAt = msToDate(bm.createdAt)
                context.insert(m)
            }
        }

        // 配置 / 连续天数。复用现有 setter/键，不新增持久化。
        if let cfg = plan.resolvedConfig {
            applyConfig(cfg)
        }

        try? context.save()
        return .success(books: plan.addedBooks, entries: plan.addedEntries,
                        translations: plan.addedTranslations, chats: plan.addedChats)
    }

    /// 给某本追加词条 + 建对应 WORD_ENTRY 调度卡。
    private static func addEntries(_ context: ModelContext, _ book: WordBook, _ entries: [BackupEntry]) {
        for be in entries {
            let e = WordEntry(english: be.english, chinese: be.chinese, note: be.note)
            e.createdAt = msToDate(be.createdAt)
            e.book = book
            context.insert(e)
            context.insert(ReviewCard(
                sourceType: "WORD_ENTRY", sourceId: e.uid,
                box: be.srs?.box ?? 0,
                dueAt: be.srs?.dueAt ?? dateToMs(Date()),
                missCount: be.srs?.missCount ?? 0,
                lastReviewedAt: be.srs?.lastReviewedAt ?? 0))
        }
    }

    // config 落到 SettingsView/SrsStore 现用的同一存储；尽力而为。
    // uiLanguage 在 iOS 由系统设置控制、无可写偏好，故忽略不应用。
    private static func applyConfig(_ cfg: BackupConfig) {
        let d = UserDefaults.standard
        d.set(cfg.reminderEnabled, forKey: "reminderEnabled")
        d.set(cfg.reminderHour, forKey: "reminderHour")
        d.set(cfg.reminderMinute, forKey: "reminderMinute")
        if cfg.reminderEnabled {
            ReviewReminder.enable(hour: cfg.reminderHour, minute: cfg.reminderMinute)
        } else {
            ReviewReminder.disable()
        }
        d.set(Int(cfg.streakLastDay), forKey: "streakLastDay")
        d.set(cfg.streakCurrent, forKey: "streakCurrent")
        d.set(cfg.streakLongest, forKey: "streakLongest")
        if let mid = cfg.activeModelId, !mid.isEmpty {
            ModelStorage.shared.activeModelId = mid
        }
    }
}
