import XCTest
@testable import Yiren

final class BackupCodecTests: XCTestCase {

    // MARK: - Fixtures

    private func sampleBackup() -> Backup {
        let entryWithSrs = BackupEntry(
            english: "abandon", chinese: "放弃", note: "", createdAt: 1710000000000,
            srs: BackupSrs(box: 2, dueAt: 1718300000000, missCount: 1, lastReviewedAt: 1718100000000)
        )
        let entryNoSrs = BackupEntry(
            english: "benefit", chinese: "益处", note: "n", createdAt: 1710000000001, srs: nil
        )
        let book = BackupBook(
            name: "考研", purpose: "", dailyGoal: 10, createdAt: 1710000000000,
            entries: [entryWithSrs, entryNoSrs]
        )
        let starredTrans = BackupTranslation(
            sourceText: "hello", translatedText: "你好", sourceLang: "EN", targetLang: "ZH",
            createdAt: 1710000000000, starred: true,
            srs: BackupSrs(box: 0, dueAt: 1718000000000, missCount: 0, lastReviewedAt: 0)
        )
        let plainTrans = BackupTranslation(
            sourceText: "world", translatedText: "世界", sourceLang: "EN", targetLang: "ZH",
            createdAt: 1710000000002, starred: false, srs: nil
        )
        let chat = BackupChat(
            id: "uuid-1", title: "t", updatedAt: 1710000000000, modelId: "m",
            summary: nil, summarizedCount: 0,
            messages: [
                BackupMessage(role: "user", content: "hi", createdAt: 1710000000000),
                BackupMessage(role: "assistant", content: "你好", createdAt: 1710000000001),
            ]
        )
        let config = BackupConfig(
            reminderEnabled: true, reminderHour: 20, reminderMinute: 0,
            uiLanguage: nil, activeModelId: "m",
            streakLastDay: 20240601, streakCurrent: 5, streakLongest: 12
        )
        return Backup(
            format: backupFormat, version: backupVersion, exportedAt: 1718200000000, platform: "ios",
            wordBooks: [book], translations: [starredTrans, plainTrans], chats: [chat], config: config
        )
    }

    // MARK: - Round-trip

    func testRoundTrip() {
        let original = sampleBackup()
        let data = BackupCodec.encode(original)
        let result = BackupCodec.decode(data)
        guard case .success(let decoded) = result else {
            XCTFail("decode failed: \(result)")
            return
        }
        XCTAssertEqual(decoded, original)
        // nil-omission round-trips to nil.
        XCTAssertNil(decoded.wordBooks[0].entries[1].srs)
        XCTAssertNil(decoded.translations[1].srs)
        XCTAssertNil(decoded.chats[0].summary)
        XCTAssertNil(decoded.config?.uiLanguage)
    }

    func testEncodeOmitsNilFields() {
        let original = sampleBackup()
        let json = String(data: BackupCodec.encode(original), encoding: .utf8) ?? ""
        // Explicit null must never be emitted for omitted optionals.
        XCTAssertFalse(json.contains("null"))
        // The non-starred translation has no srs -> only one "srs" object expected from translations.
        XCTAssertFalse(json.contains("\"uiLanguage\""))
        XCTAssertFalse(json.contains("\"summary\""))
    }

    // MARK: - Decode rejection

    func testDecodeRejectsGarbage() {
        let data = Data("not json at all }{".utf8)
        if case .success = BackupCodec.decode(data) {
            XCTFail("garbage should not decode")
        }
    }

    func testDecodeRejectsWrongFormat() {
        let json = #"{"format":"other-app","version":1}"#
        if case .success = BackupCodec.decode(Data(json.utf8)) {
            XCTFail("wrong format should be rejected")
        }
    }

    func testDecodeRejectsWrongVersion() {
        let json = #"{"format":"yiren-backup","version":2}"#
        if case .success = BackupCodec.decode(Data(json.utf8)) {
            XCTFail("version 2 should be rejected")
        }
    }

    // MARK: - Minimal file

    func testDecodeMinimal() {
        let json = #"{"format":"yiren-backup","version":1}"#
        let result = BackupCodec.decode(Data(json.utf8))
        guard case .success(let b) = result else {
            XCTFail("minimal file should decode: \(result)")
            return
        }
        XCTAssertTrue(b.wordBooks.isEmpty)
        XCTAssertTrue(b.translations.isEmpty)
        XCTAssertTrue(b.chats.isEmpty)
        XCTAssertNil(b.config)
        XCTAssertEqual(b.exportedAt, 0)
        XCTAssertEqual(b.platform, "")
    }

    // MARK: - Cross-platform decode (Android-exported byte-for-byte string)

    func testCrossPlatformAndroidDecode() {
        let json = #"{"format":"yiren-backup","version":1,"exportedAt":1718200000000,"platform":"android","wordBooks":[{"name":"考研","purpose":"","dailyGoal":10,"createdAt":1710000000000,"entries":[{"english":"abandon","chinese":"放弃","note":"","createdAt":1710000000000,"srs":{"box":2,"dueAt":1718300000000,"missCount":1,"lastReviewedAt":1718100000000}}]}],"translations":[],"chats":[],"config":{"reminderEnabled":true,"reminderHour":20,"reminderMinute":0,"activeModelId":"m","streakLastDay":20240601,"streakCurrent":5,"streakLongest":12}}"#
        let result = BackupCodec.decode(Data(json.utf8))
        guard case .success(let b) = result else {
            XCTFail("android json should decode: \(result)")
            return
        }
        XCTAssertEqual(b.wordBooks.count, 1)
        XCTAssertEqual(b.wordBooks[0].name, "考研")
        XCTAssertEqual(b.wordBooks[0].entries[0].english, "abandon")
        XCTAssertEqual(b.wordBooks[0].entries[0].srs?.box, 2)
        XCTAssertNil(b.config?.uiLanguage)
        XCTAssertEqual(b.config?.activeModelId, "m")
    }

    // MARK: - Merge

    private func entry(_ english: String) -> BackupEntry {
        BackupEntry(english: english, chinese: "x", note: "", createdAt: 0, srs: nil)
    }

    private func trans(_ src: String, _ s: String = "EN", _ t: String = "ZH") -> BackupTranslation {
        BackupTranslation(sourceText: src, translatedText: "x", sourceLang: s, targetLang: t,
                          createdAt: 0, starred: false, srs: nil)
    }

    private func chat(_ id: String) -> BackupChat {
        BackupChat(id: id, title: "t", updatedAt: 0, modelId: "m", summary: nil, summarizedCount: 0, messages: [])
    }

    private func wrap(books: [BackupBook] = [], trans: [BackupTranslation] = [],
                      chats: [BackupChat] = [], config: BackupConfig? = nil) -> Backup {
        Backup(format: backupFormat, version: backupVersion, exportedAt: 0, platform: "ios",
               wordBooks: books, translations: trans, chats: chats, config: config)
    }

    func testMergeSameNameBookAppendsNewEntriesCaseInsensitive() {
        let current = wrap(books: [
            BackupBook(name: "考研", purpose: "", dailyGoal: 10, createdAt: 0,
                       entries: [entry("Apple"), entry("Banana")])
        ])
        let incoming = wrap(books: [
            BackupBook(name: " 考研 ", purpose: "", dailyGoal: 10, createdAt: 0,
                       entries: [entry(" apple "), entry("Cherry")])  // apple dup (case+trim), cherry new
        ])
        let plan = BackupCodec.merge(current: current, incoming: incoming)
        XCTAssertTrue(plan.newBooks.isEmpty)
        // dict key is incoming.name (untrimmed)
        XCTAssertEqual(plan.entriesToExistingBook[" 考研 "]?.map { $0.english }, ["Cherry"])
        XCTAssertEqual(plan.addedBooks, 0)
        XCTAssertEqual(plan.addedEntries, 1)
    }

    func testMergeDifferentNameBookIsNew() {
        let current = wrap(books: [
            BackupBook(name: "考研", purpose: "", dailyGoal: 10, createdAt: 0, entries: [entry("a")])
        ])
        let incoming = wrap(books: [
            BackupBook(name: "四级", purpose: "", dailyGoal: 5, createdAt: 0, entries: [entry("b"), entry("c")])
        ])
        let plan = BackupCodec.merge(current: current, incoming: incoming)
        XCTAssertEqual(plan.newBooks.count, 1)
        XCTAssertEqual(plan.addedBooks, 1)
        XCTAssertEqual(plan.addedEntries, 2)
        XCTAssertTrue(plan.entriesToExistingBook.isEmpty)
    }

    func testMergeTranslationDupExcluded() {
        let current = wrap(trans: [trans("hello")])
        let incoming = wrap(trans: [trans("hello"), trans("world")])
        let plan = BackupCodec.merge(current: current, incoming: incoming)
        XCTAssertEqual(plan.newTranslations.map { $0.sourceText }, ["world"])
        XCTAssertEqual(plan.addedTranslations, 1)
    }

    func testMergeChatDupByIdExcluded() {
        let current = wrap(chats: [chat("a")])
        let incoming = wrap(chats: [chat("a"), chat("b")])
        let plan = BackupCodec.merge(current: current, incoming: incoming)
        XCTAssertEqual(plan.newChats.map { $0.id }, ["b"])
        XCTAssertEqual(plan.addedChats, 1)
    }

    func testMergeEmptyIncomingAllZero() {
        let current = wrap(books: [
            BackupBook(name: "考研", purpose: "", dailyGoal: 10, createdAt: 0, entries: [entry("a")])
        ], trans: [trans("hello")], chats: [chat("a")])
        let plan = BackupCodec.merge(current: current, incoming: wrap())
        XCTAssertEqual(plan.addedBooks, 0)
        XCTAssertEqual(plan.addedEntries, 0)
        XCTAssertEqual(plan.addedTranslations, 0)
        XCTAssertEqual(plan.addedChats, 0)
        XCTAssertNil(plan.resolvedConfig)
    }

    func testMergeStreakLaterDayWinsLongestIsMax() {
        let curConfig = BackupConfig(reminderEnabled: false, reminderHour: 8, reminderMinute: 0,
                                     uiLanguage: nil, activeModelId: "old",
                                     streakLastDay: 20240610, streakCurrent: 7, streakLongest: 20)
        let incConfig = BackupConfig(reminderEnabled: true, reminderHour: 21, reminderMinute: 30,
                                     uiLanguage: "zh", activeModelId: "new",
                                     streakLastDay: 20240615, streakCurrent: 3, streakLongest: 12)
        let plan = BackupCodec.merge(current: wrap(config: curConfig), incoming: wrap(config: incConfig))
        let r = plan.resolvedConfig
        XCTAssertNotNil(r)
        // later lastDay (incoming) wins -> takes incoming streakCurrent
        XCTAssertEqual(r?.streakLastDay, 20240615)
        XCTAssertEqual(r?.streakCurrent, 3)
        // longest = max
        XCTAssertEqual(r?.streakLongest, 20)
        // reminder/ui/active from incoming
        XCTAssertEqual(r?.reminderEnabled, true)
        XCTAssertEqual(r?.reminderHour, 21)
        XCTAssertEqual(r?.uiLanguage, "zh")
        XCTAssertEqual(r?.activeModelId, "new")
    }

    func testMergeStreakCurrentDayWinsWhenNewer() {
        let curConfig = BackupConfig(reminderEnabled: false, reminderHour: 8, reminderMinute: 0,
                                     uiLanguage: nil, activeModelId: "old",
                                     streakLastDay: 20240620, streakCurrent: 9, streakLongest: 30)
        let incConfig = BackupConfig(reminderEnabled: true, reminderHour: 21, reminderMinute: 30,
                                     uiLanguage: "zh", activeModelId: "new",
                                     streakLastDay: 20240615, streakCurrent: 3, streakLongest: 12)
        let plan = BackupCodec.merge(current: wrap(config: curConfig), incoming: wrap(config: incConfig))
        let r = plan.resolvedConfig
        // current lastDay is newer -> keep current lastDay/current
        XCTAssertEqual(r?.streakLastDay, 20240620)
        XCTAssertEqual(r?.streakCurrent, 9)
        XCTAssertEqual(r?.streakLongest, 30)
    }

    func testMergeConfigNilWhenIncomingNil() {
        let curConfig = BackupConfig(reminderEnabled: true, reminderHour: 8, reminderMinute: 0,
                                     uiLanguage: nil, activeModelId: nil,
                                     streakLastDay: 1, streakCurrent: 1, streakLongest: 1)
        let plan = BackupCodec.merge(current: wrap(config: curConfig), incoming: wrap(config: nil))
        XCTAssertNil(plan.resolvedConfig)
    }
}
