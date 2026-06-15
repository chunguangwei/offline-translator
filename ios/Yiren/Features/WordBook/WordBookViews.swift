import SwiftData
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// 单词本系统 —— 设计规则见 docs/superpowers/specs/2026-06-12-yiren-wordbook-design.md。
// 上传文本 → Gemma 提取「english => 中文 => 注释」→ 命名建库 →
// 今日学习（未掌握取 dailyGoal 个）/ 全量抽查（整本随机）→ 翻卡自评。

/// 提取草稿（预览阶段）。
struct VocabDraft: Identifiable, Equatable {
    let id = UUID()
    let english: String
    let chinese: String
    let note: String
}

/// 提取引擎逻辑（分块 + 流式解析 + 去重）。
@MainActor
final class VocabExtractor: ObservableObject {
    @Published var isExtracting = false
    @Published var loadingModel = false
    @Published var drafts: [VocabDraft] = []
    @Published var errorMessage: String?

    private let gemma = GemmaService.shared
    private var task: Task<Void, Never>?

    func extract(_ text: String) {
        guard !isExtracting else { return }
        task?.cancel()
        isExtracting = true
        loadingModel = true
        drafts = []
        errorMessage = nil
        task = Task {
            if case .failure(let f) = await gemma.ensureLoaded() {
                loadingModel = false
                isExtracting = false
                if case .modelMissing = f {
                    errorMessage = PromptTemplates.isZhUi ? "请先到「设置 → 模型管理」下载模型" : "Download a model first"
                }
                return
            }
            loadingModel = false
            var seen: [String: VocabDraft] = [:]
            var order: [String] = []
            do {
                for chunk in Self.chunked(text) {
                    if Task.isCancelled { break }
                    let stream = try await gemma.generateStream(
                        prompt: PromptTemplates.extractVocab(chunk),
                        sampler: Samplers.precise
                    )
                    var buf = ""
                    for try await delta in stream {
                        if Task.isCancelled { break }
                        buf += delta
                        while let nl = buf.firstIndex(of: "\n") {
                            let line = String(buf[..<nl])
                            buf.removeSubrange(...nl)
                            if let d = Self.parseLine(line), seen[d.english.lowercased()] == nil {
                                seen[d.english.lowercased()] = d
                                order.append(d.english.lowercased())
                            }
                        }
                        drafts = order.compactMap { seen[$0] }
                    }
                    if let d = Self.parseLine(buf), seen[d.english.lowercased()] == nil {
                        seen[d.english.lowercased()] = d
                        order.append(d.english.lowercased())
                        drafts = order.compactMap { seen[$0] }
                    }
                }
                isExtracting = false
            } catch {
                if !Task.isCancelled { errorMessage = error.localizedDescription }
                isExtracting = false
            }
        }
    }

    func cancel() {
        task?.cancel()
        gemma.cancelGeneration()
        isExtracting = false
        loadingModel = false
    }

    func reset() {
        cancel()
        drafts = []
        errorMessage = nil
    }

    nonisolated static func chunked(_ text: String, size: Int = 1200) -> [String] {
        var chunks: [String] = []
        var cur = ""
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            if cur.count + line.count + 1 > size, !cur.isEmpty {
                chunks.append(cur)
                cur = ""
            }
            cur += line + "\n"
        }
        if !cur.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { chunks.append(cur) }
        return chunks
    }

    nonisolated static func parseLine(_ raw: String) -> VocabDraft? {
        let line = PromptTemplates.trimAtStop(raw).trimmingCharacters(in: .whitespaces)
        guard line.contains("=>") else { return nil }
        let parts = line.components(separatedBy: "=>").map {
            $0.trimmingCharacters(in: CharacterSet(charactersIn: " 「」\"\t"))
        }
        let english = parts.count > 0 ? parts[0] : ""
        let chinese = parts.count > 1 ? parts[1] : ""
        let note = parts.count > 2 ? parts[2] : ""
        guard english.rangeOfCharacter(from: .letters) != nil, english.count <= 60, !chinese.isEmpty else { return nil }
        return VocabDraft(english: english, chinese: chinese, note: note)
    }
}

// ───────────────────────── 列表 ─────────────────────────

struct WordBooksSection: View {
    @Query(sort: \WordBook.createdAt, order: .reverse) private var books: [WordBook]
    @Environment(\.modelContext) private var context
    @State private var showImport = false

    private var zh: Bool { PromptTemplates.isZhUi }

    var body: some View {
        Group {
            if books.isEmpty {
                ContentUnavailableView(
                    zh ? "还没有单词本" : "No word books yet",
                    systemImage: "character.book.closed",
                    description: Text(zh ? "上传一份生词文本，AI 帮你提取建库" : "Upload a vocab text and AI extracts it")
                )
            } else {
                List {
                    ForEach(books, id: \.persistentModelID) { book in
                        NavigationLink {
                            WordBookDetailView(book: book)
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(book.name).font(.headline)
                                if !book.purpose.isEmpty {
                                    Text(book.purpose).font(.caption).foregroundStyle(.secondary)
                                }
                                let mastered = book.entries.filter { $0.proficiency >= 3 }.count
                                Text(zh ? "\(book.entries.count) 词 · 已掌握 \(mastered) · 每日 \(book.dailyGoal)"
                                        : "\(book.entries.count) words · \(mastered) mastered · \(book.dailyGoal)/day")
                                    .font(.caption)
                                    .foregroundStyle(Color.brandPrimary)
                            }
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                for e in book.entries { SrsStore.removeCard(context, sourceType: "WORD_ENTRY", sourceId: e.uid) }
                                context.delete(book) // 级联删词条
                                try? context.save()
                            } label: { Label(zh ? "删除" : "Delete", systemImage: "trash") }
                        }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button {
                showImport = true
            } label: {
                Label(zh ? "新建单词本" : "New word book", systemImage: "plus")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.borderedProminent)
            .tint(.brandPrimary)
            .padding(.horizontal, 16)
            .padding(.bottom, 6)
        }
        .sheet(isPresented: $showImport) { ImportWordBookView() }
    }
}

// ───────────────────────── 导入 ─────────────────────────

struct ImportWordBookView: View {
    @StateObject private var extractor = VocabExtractor()
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var name = ""
    @State private var purpose = ""
    @State private var dailyGoal = 10
    @State private var showFile = false
    @FocusState private var editorFocused: Bool

    private var zh: Bool { PromptTemplates.isZhUi }

    var body: some View {
        NavigationStack {
            Form {
                if extractor.drafts.isEmpty && !extractor.isExtracting {
                    Section(zh ? "生词文本" : "Vocab text") {
                        TextEditor(text: $text)
                            // 限高内部滚动：文字再多也不会把下方按钮挤出屏幕。
                            .frame(minHeight: 140, maxHeight: 280)
                            .focused($editorFocused)
                            .overlay(alignment: .topLeading) {
                                if text.isEmpty {
                                    Text(zh ? "粘贴生词文本（英文词表或带中文释义都行）…" : "Paste vocab text…")
                                        .foregroundStyle(.tertiary)
                                        .padding(.top, 8)
                                        .allowsHitTesting(false)
                                }
                            }
                        Button(zh ? "选 txt 文件" : "Pick .txt file") { showFile = true }
                        Button(zh ? "开始提取" : "Extract") { extractor.extract(text) }
                            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .fontWeight(.semibold)
                            .foregroundStyle(Color.brandPrimary)
                        if let err = extractor.errorMessage {
                            Text(err).font(.footnote).foregroundStyle(.red)
                        }
                    }
                } else {
                    // 状态条（提取中含停止 / 完成提示）
                    Section {
                        if extractor.isExtracting {
                            HStack {
                                ProgressView().controlSize(.small)
                                Text(extractor.loadingModel
                                     ? (zh ? "正在加载模型…" : "Loading model…")
                                     : (zh ? "AI 提取中… 已提取 \(extractor.drafts.count) 条" : "Extracting… \(extractor.drafts.count)"))
                                    .font(.caption)
                                    .foregroundStyle(Color.brandPrimary)
                                Spacer()
                                Button(zh ? "停止" : "Stop") { extractor.cancel() }
                                    .font(.caption)
                            }
                        } else {
                            Text(zh ? "提取完成，共 \(extractor.drafts.count) 条（下方左滑删错误项）"
                                    : "Done, \(extractor.drafts.count) entries")
                                .font(.caption)
                                .foregroundStyle(Color.brandPrimary)
                        }
                    }
                    // 保存区放在词条列表之前：单词多也不用拉到底命名/保存。
                    Section(zh ? "保存" : "Save") {
                        TextField(zh ? "单词本名称（必填）" : "Name (required)", text: $name)
                        TextField(zh ? "用途说明（可选）" : "Purpose (optional)", text: $purpose)
                        Picker(zh ? "每日学习量" : "Daily goal", selection: $dailyGoal) {
                            ForEach([5, 10, 20, 30], id: \.self) { Text("\($0)").tag($0) }
                        }
                        .pickerStyle(.segmented)
                        Button(zh ? "保存单词本" : "Save word book") { save() }
                            .disabled(extractor.isExtracting || extractor.drafts.isEmpty
                                      || name.trimmingCharacters(in: .whitespaces).isEmpty)
                            .fontWeight(.semibold)
                            .foregroundStyle(Color.brandPrimary)
                    }
                    // 词条列表放最后（可长，不再挡住保存）
                    Section(zh ? "已提取 \(extractor.drafts.count) 条" : "\(extractor.drafts.count) entries") {
                        ForEach(extractor.drafts) { d in
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(d.english) — \(d.chinese)").font(.subheadline)
                                if !d.note.isEmpty {
                                    Text(d.note).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .swipeActions {
                                Button(role: .destructive) {
                                    extractor.drafts.removeAll { $0.id == d.id }
                                } label: { Label(zh ? "删除" : "Delete", systemImage: "trash") }
                            }
                        }
                    }
                }
            }
            .navigationTitle(zh ? "新建单词本" : "New word book")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(zh ? "取消" : "Cancel") {
                        extractor.reset()
                        dismiss()
                    }
                }
                // 主操作放 toolbar：始终可见、不被内容挤出屏幕。
                // 输入阶段=提取；提取完成=保存（滚到词条列表里也点得到）。
                ToolbarItem(placement: .topBarTrailing) {
                    if extractor.drafts.isEmpty && !extractor.isExtracting {
                        Button(zh ? "提取" : "Extract") { extractor.extract(text) }
                            .fontWeight(.semibold)
                            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    } else if !extractor.isExtracting {
                        Button(zh ? "保存" : "Save") { save() }
                            .fontWeight(.semibold)
                            .disabled(extractor.drafts.isEmpty
                                      || name.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                // 键盘上方「完成」收起键盘（多行输入框点别处不一定收起）。
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(zh ? "完成" : "Done") { editorFocused = false }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .fileImporter(isPresented: $showFile, allowedContentTypes: [.plainText, .rtf]) { result in
                if case .success(let url) = result {
                    let secured = url.startAccessingSecurityScopedResource()
                    defer { if secured { url.stopAccessingSecurityScopedResource() } }
                    if let content = Self.readImportedText(from: url) {
                        text = content
                    }
                }
            }
        }
        .interactiveDismissDisabled(extractor.isExtracting)
    }

    /// 读导入文件为纯文本：RTF 提取纯文字；其余按 UTF-8（失败再兜底其它编码）。
    /// 同时防御被命名为 .txt 实为 RTF 的文件（开头 `{\rtf`）。
    static func readImportedText(from url: URL) -> String? {
        if url.pathExtension.lowercased() == "rtf",
           let attr = try? NSAttributedString(url: url, options: [:], documentAttributes: nil) {
            return attr.string
        }
        if let s = try? String(contentsOf: url, encoding: .utf8) {
            if s.hasPrefix("{\\rtf"), let data = s.data(using: .utf8),
               let plain = try? NSAttributedString(
                   data: data,
                   options: [.documentType: NSAttributedString.DocumentType.rtf],
                   documentAttributes: nil).string {
                return plain
            }
            return s
        }
        return try? String(contentsOf: url) // 非 UTF-8 编码兜底
    }

    private func save() {
        let book = WordBook(name: name.trimmingCharacters(in: .whitespaces),
                            purpose: purpose.trimmingCharacters(in: .whitespaces),
                            dailyGoal: dailyGoal)
        context.insert(book)
        for d in extractor.drafts {
            let e = WordEntry(english: d.english, chinese: d.chinese, note: d.note)
            e.book = book
            context.insert(e)
            SrsStore.addCard(context, sourceType: "WORD_ENTRY", sourceId: e.uid)
        }
        try? context.save()
        extractor.reset()
        dismiss()
    }
}

// ───────────────────────── 详情 + 测试 ─────────────────────────

enum QuizDirection: String, CaseIterable {
    case zh2en, en2zh, mixed
}

struct WordBookDetailView: View {
    let book: WordBook
    @Environment(\.modelContext) private var context
    @State private var direction: QuizDirection = .mixed
    // 稳定 id 的 payload（不要每次渲染新建 UUID，否则评分 save 触发重渲染会重置队列）。
    @State private var quizPayload: QuizPayload?
    // 「今日学习」现走 SRS：本册到期卡 → 翻卡 → 认识后隔天才再现（不再原地重复）。
    @State private var srsItems: [SrsStore.ReviewItem] = []
    @State private var showSrsReview = false
    // SRS 统计（到期数 / 已掌握=box≥6 / 已掌握词 uid 集），进页与复习后刷新。
    @State private var stats: (due: Int, mastered: Int, masteredUids: Set<String>) = (0, 0, [])
    // 编辑本信息 / 添加词 / 编辑词条 三个 sheet。
    @State private var showEditBook = false
    @State private var showAddWords = false
    @State private var editingEntry: WordEntry?

    private var zh: Bool { PromptTemplates.isZhUi }

    private func refreshStats() { stats = SrsStore.bookStats(context, book) }

    var body: some View {
        List {
            Section {
                Picker("", selection: $direction) {
                    Text(zh ? "中→英" : "ZH→EN").tag(QuizDirection.zh2en)
                    Text(zh ? "英→中" : "EN→ZH").tag(QuizDirection.en2zh)
                    Text(zh ? "混合" : "Mixed").tag(QuizDirection.mixed)
                }
                .pickerStyle(.segmented)
                HStack(spacing: 12) {
                    // 今日复习：本册 SRS 到期卡（认识后隔天再现，不再原地重复）。
                    Button(zh ? "今日复习" : "Review due") {
                        srsItems = SrsStore.buildBookSession(context, book)
                        showSrsReview = true
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.brandPrimary)
                    .disabled(stats.due == 0)
                    // 全量抽查：整本打散纯练手，不改 SRS 进度。
                    Button(zh ? "全量抽查" : "Random quiz") {
                        quizPayload = QuizPayload(batch: book.entries.shuffled(), direction: direction)
                    }
                    .buttonStyle(.bordered)
                    .disabled(book.entries.isEmpty)
                }
                Button {
                    showAddWords = true
                } label: {
                    Label(zh ? "添加词" : "Add words", systemImage: "plus.circle")
                }
            } footer: {
                Text(zh ? "\(book.entries.count) 词 · 已掌握 \(stats.mastered) · 今日到期 \(stats.due)"
                        : "\(book.entries.count) words · \(stats.mastered) mastered · \(stats.due) due today")
            }
            Section {
                ForEach(book.entries.sorted { $0.createdAt < $1.createdAt }, id: \.persistentModelID) { e in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(e.english) — \(e.chinese)" + (stats.masteredUids.contains(e.uid) ? " ✓" : ""))
                            .font(.subheadline)
                        if !e.note.isEmpty {
                            Text(e.note).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { editingEntry = e }
                    .swipeActions {
                        Button(role: .destructive) {
                            SrsStore.removeCard(context, sourceType: "WORD_ENTRY", sourceId: e.uid)
                            context.delete(e)
                            try? context.save()
                        } label: { Label(zh ? "删除" : "Delete", systemImage: "trash") }
                    }
                }
            }
        }
        .navigationTitle(book.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showEditBook = true } label: { Image(systemName: "pencil") }
            }
        }
        .onAppear { refreshStats() }
        .sheet(item: $quizPayload) { payload in
            WordQuizView(payload: payload)
        }
        .sheet(isPresented: $showSrsReview, onDismiss: { refreshStats() }) {
            SrsReviewView(
                items: srsItems,
                onGrade: { c, ok in SrsStore.grade(context, c, correct: ok) },
                onFinished: { n in if n > 0 { SrsStore.markStudiedToday() }; refreshStats() }
            )
        }
        .sheet(isPresented: $showEditBook, onDismiss: { refreshStats() }) {
            EditWordBookView(book: book)
        }
        .sheet(isPresented: $showAddWords, onDismiss: { refreshStats() }) {
            AddWordsView(book: book)
        }
        .sheet(item: $editingEntry, onDismiss: { refreshStats() }) { entry in
            EditWordEntryView(entry: entry)
        }
    }
}

struct QuizPayload: Identifiable {
    let id = UUID()
    let batch: [WordEntry]
    let direction: QuizDirection
}

/// 翻卡测试：正面按方向出题 → 「查看」翻面（答案+注释）→ 认识(+1，封顶3)/不认识(归0放回队尾)。
struct WordQuizView: View {
    let payload: QuizPayload
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var queue: [WordEntry] = []
    @State private var cardDirs: [PersistentIdentifier: QuizDirection] = [:]
    @State private var missed: Set<PersistentIdentifier> = []
    @State private var firstPass = 0
    @State private var revealed = false

    private var zh: Bool { PromptTemplates.isZhUi }
    private var total: Int { payload.batch.count }

    var body: some View {
        VStack(spacing: 18) {
            if queue.isEmpty {
                Spacer()
                Text("🎉").font(.system(size: 56))
                Text(zh ? "本轮 \(total) 张全部过关\n一次记住 \(firstPass) 张"
                        : "All \(total) cleared\n\(firstPass) on first try")
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)
                Button(zh ? "完成" : "Done") { dismiss() }
                    .buttonStyle(.borderedProminent)
                    .tint(.brandPrimary)
                Spacer()
            } else {
                let card = queue[0]
                let dir = cardDirs[card.persistentModelID] ?? .en2zh
                Text(zh ? "第 \(total - queue.count + 1) / \(total) 张" : "Card \(total - queue.count + 1) / \(total)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 22)
                Spacer()
                VStack(spacing: 12) {
                    Text(dir == .zh2en ? card.chinese : card.english)
                        .font(.title2.weight(.semibold))
                        .multilineTextAlignment(.center)
                    if revealed {
                        Text(dir == .zh2en ? card.english : card.chinese)
                            .font(.title3)
                            .foregroundStyle(Color.brandPrimary)
                            .multilineTextAlignment(.center)
                        if !card.note.isEmpty {
                            Text(card.note)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                    } else {
                        Text(zh ? "想好了？点击查看答案" : "Got it in mind? Tap to reveal")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(26)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
                .padding(.horizontal, 22)
                .onTapGesture { revealed.toggle() }
                Spacer()
                HStack(spacing: 16) {
                    Button(zh ? "不认识" : "Again") {
                        grade(card, known: false)
                        missed.insert(card.persistentModelID)
                        let c = queue.removeFirst()
                        queue.append(c)
                        revealed = false
                    }
                    .buttonStyle(.bordered)
                    Button(zh ? "认识" : "Got it") {
                        grade(card, known: true)
                        if !missed.contains(card.persistentModelID) { firstPass += 1 }
                        queue.removeFirst()
                        revealed = false
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.brandPrimary)
                }
                .padding(.bottom, 30)
            }
        }
        .onAppear {
            queue = payload.batch
            cardDirs = Dictionary(uniqueKeysWithValues: payload.batch.map { e in
                (e.persistentModelID, payload.direction == .mixed ? (Bool.random() ? .zh2en : .en2zh) : payload.direction)
            })
        }
    }

    private func grade(_ e: WordEntry, known: Bool) {
        e.proficiency = known ? min(e.proficiency + 1, 3) : 0
        e.lastSeenAt = .now
        try? context.save()
    }
}

// ───────────────────────── 编辑本信息 ─────────────────────────

/// 编辑单词本基本信息：名称 / 用途 / 每日学习量。
struct EditWordBookView: View {
    let book: WordBook
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var purpose: String
    @State private var dailyGoal: Int

    private var zh: Bool { PromptTemplates.isZhUi }

    init(book: WordBook) {
        self.book = book
        _name = State(initialValue: book.name)
        _purpose = State(initialValue: book.purpose)
        _dailyGoal = State(initialValue: book.dailyGoal)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(zh ? "单词本名称（必填）" : "Name (required)", text: $name)
                    TextField(zh ? "用途说明（可选）" : "Purpose (optional)", text: $purpose)
                    Picker(zh ? "每日学习量" : "Daily goal", selection: $dailyGoal) {
                        ForEach([5, 10, 20, 30], id: \.self) { Text("\($0)").tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle(zh ? "编辑单词本" : "Edit word book")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(zh ? "取消" : "Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(zh ? "保存" : "Save") { save() }
                        .fontWeight(.semibold)
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func save() {
        book.name = name.trimmingCharacters(in: .whitespaces)
        book.purpose = purpose.trimmingCharacters(in: .whitespaces)
        book.dailyGoal = max(1, dailyGoal)
        try? context.save()
        dismiss()
    }
}

// ───────────────────────── 添加词（提取 / 手动）─────────────────────────

/// 给已有单词本添加词：提取（粘贴文本 → AI 提取 → 批量保存）或手动逐条添加。
/// 两种方式保存前都按英文键去重（dedupDrafts / 手动 contains 检查），跳过已存在词。
struct AddWordsView: View {
    let book: WordBook
    @StateObject private var extractor = VocabExtractor()
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss

    private enum Mode: Hashable { case extract, manual }
    @State private var mode: Mode = .extract

    // 提取模式
    @State private var text = ""
    @State private var showFile = false
    @FocusState private var editorFocused: Bool

    // 手动模式
    @State private var mEnglish = ""
    @State private var mChinese = ""
    @State private var mNote = ""
    @State private var manualError: String?
    @State private var addedCount = 0

    private var zh: Bool { PromptTemplates.isZhUi }

    /// 当前本里已有的英文键（trim+lowercase）。
    private var existingKeys: Set<String> {
        Set(book.entries.map { $0.english.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() })
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("", selection: $mode) {
                        Text(zh ? "提取" : "Extract").tag(Mode.extract)
                        Text(zh ? "手动" : "Manual").tag(Mode.manual)
                    }
                    .pickerStyle(.segmented)
                }
                if mode == .extract { extractSection } else { manualSection }
            }
            .navigationTitle(zh ? "添加词" : "Add words")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(zh ? "取消" : "Cancel") {
                        extractor.reset()
                        dismiss()
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(zh ? "完成" : "Done") { editorFocused = false }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .fileImporter(isPresented: $showFile, allowedContentTypes: [.plainText, .rtf]) { result in
                if case .success(let url) = result {
                    let secured = url.startAccessingSecurityScopedResource()
                    defer { if secured { url.stopAccessingSecurityScopedResource() } }
                    if let content = ImportWordBookView.readImportedText(from: url) {
                        text = content
                    }
                }
            }
        }
        .interactiveDismissDisabled(extractor.isExtracting)
    }

    @ViewBuilder private var extractSection: some View {
        if extractor.drafts.isEmpty && !extractor.isExtracting {
            Section(zh ? "生词文本" : "Vocab text") {
                TextEditor(text: $text)
                    .frame(minHeight: 140, maxHeight: 280)
                    .focused($editorFocused)
                    .overlay(alignment: .topLeading) {
                        if text.isEmpty {
                            Text(zh ? "粘贴生词文本（英文词表或带中文释义都行）…" : "Paste vocab text…")
                                .foregroundStyle(.tertiary)
                                .padding(.top, 8)
                                .allowsHitTesting(false)
                        }
                    }
                Button(zh ? "选 txt 文件" : "Pick .txt file") { showFile = true }
                Button(zh ? "开始提取" : "Extract") { extractor.extract(text) }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .fontWeight(.semibold)
                    .foregroundStyle(Color.brandPrimary)
                if let err = extractor.errorMessage {
                    Text(err).font(.footnote).foregroundStyle(.red)
                }
            }
        } else {
            Section {
                if extractor.isExtracting {
                    HStack {
                        ProgressView().controlSize(.small)
                        Text(extractor.loadingModel
                             ? (zh ? "正在加载模型…" : "Loading model…")
                             : (zh ? "AI 提取中… 已提取 \(extractor.drafts.count) 条" : "Extracting… \(extractor.drafts.count)"))
                            .font(.caption)
                            .foregroundStyle(Color.brandPrimary)
                        Spacer()
                        Button(zh ? "停止" : "Stop") { extractor.cancel() }
                            .font(.caption)
                    }
                } else {
                    Text(zh ? "提取完成，共 \(extractor.drafts.count) 条（下方左滑删错误项）"
                            : "Done, \(extractor.drafts.count) entries")
                        .font(.caption)
                        .foregroundStyle(Color.brandPrimary)
                }
            }
            Section {
                Button(zh ? "保存到本" : "Save to book") { saveExtracted() }
                    .disabled(extractor.isExtracting || extractor.drafts.isEmpty)
                    .fontWeight(.semibold)
                    .foregroundStyle(Color.brandPrimary)
            }
            Section(zh ? "已提取 \(extractor.drafts.count) 条" : "\(extractor.drafts.count) entries") {
                ForEach(extractor.drafts) { d in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(d.english) — \(d.chinese)").font(.subheadline)
                        if !d.note.isEmpty {
                            Text(d.note).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            extractor.drafts.removeAll { $0.id == d.id }
                        } label: { Label(zh ? "删除" : "Delete", systemImage: "trash") }
                    }
                }
            }
        }
    }

    @ViewBuilder private var manualSection: some View {
        Section(zh ? "新词" : "New word") {
            TextField(zh ? "英文（必填）" : "English (required)", text: $mEnglish)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            TextField(zh ? "中文（必填）" : "Chinese (required)", text: $mChinese)
            TextField(zh ? "注释（可选）" : "Note (optional)", text: $mNote)
            if let err = manualError {
                Text(err).font(.footnote).foregroundStyle(.red)
            }
            Button(zh ? "添加" : "Add") { addManual() }
                .fontWeight(.semibold)
                .foregroundStyle(Color.brandPrimary)
        }
        if addedCount > 0 {
            Section {
                Text(zh ? "本次已添加 \(addedCount) 词" : "Added \(addedCount) word(s)")
                    .font(.caption)
                    .foregroundStyle(Color.brandPrimary)
            }
        }
    }

    private func saveExtracted() {
        let toAdd = dedupDrafts(extractor.drafts, existingKeys: existingKeys)
        for d in toAdd {
            let e = WordEntry(english: d.english, chinese: d.chinese, note: d.note)
            e.book = book
            context.insert(e)
            SrsStore.addCard(context, sourceType: "WORD_ENTRY", sourceId: e.uid)
        }
        try? context.save()
        extractor.reset()
        dismiss()
    }

    private func addManual() {
        let english = mEnglish.trimmingCharacters(in: .whitespacesAndNewlines)
        let chinese = mChinese.trimmingCharacters(in: .whitespacesAndNewlines)
        let note = mNote.trimmingCharacters(in: .whitespacesAndNewlines)
        if english.isEmpty || chinese.isEmpty {
            manualError = zh ? "英文和中文都要填" : "English and Chinese required"
            return
        }
        if existingKeys.contains(english.lowercased()) {
            manualError = zh ? "已存在" : "Already exists"
            return
        }
        let e = WordEntry(english: english, chinese: chinese, note: note)
        e.book = book
        context.insert(e)
        SrsStore.addCard(context, sourceType: "WORD_ENTRY", sourceId: e.uid)
        try? context.save()
        // 清空输入，保持 sheet 打开以便继续添加。
        mEnglish = ""
        mChinese = ""
        mNote = ""
        manualError = nil
        addedCount += 1
    }
}

// ───────────────────────── 编辑词条 ─────────────────────────

/// 编辑单条词：改英文/中文/注释，或删除。
struct EditWordEntryView: View {
    let entry: WordEntry
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var english: String
    @State private var chinese: String
    @State private var note: String

    private var zh: Bool { PromptTemplates.isZhUi }

    init(entry: WordEntry) {
        self.entry = entry
        _english = State(initialValue: entry.english)
        _chinese = State(initialValue: entry.chinese)
        _note = State(initialValue: entry.note)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(zh ? "英文（必填）" : "English (required)", text: $english)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField(zh ? "中文（必填）" : "Chinese (required)", text: $chinese)
                    TextField(zh ? "注释（可选）" : "Note (optional)", text: $note)
                }
                Section {
                    Button(role: .destructive) { deleteEntry() } label: {
                        Text(zh ? "删除" : "Delete")
                    }
                }
            }
            .navigationTitle(zh ? "编辑词条" : "Edit entry")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(zh ? "取消" : "Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(zh ? "保存" : "Save") { save() }
                        .fontWeight(.semibold)
                        .disabled(english.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                  || chinese.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func save() {
        entry.english = english.trimmingCharacters(in: .whitespacesAndNewlines)
        entry.chinese = chinese.trimmingCharacters(in: .whitespacesAndNewlines)
        entry.note = note.trimmingCharacters(in: .whitespacesAndNewlines)
        try? context.save()
        dismiss()
    }

    private func deleteEntry() {
        SrsStore.removeCard(context, sourceType: "WORD_ENTRY", sourceId: entry.uid)
        context.delete(entry)
        try? context.save()
        dismiss()
    }
}
