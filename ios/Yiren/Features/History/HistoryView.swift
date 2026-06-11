import SwiftData
import SwiftUI

/// 历史 Tab —— 只存翻译记录（产品决策），对应 Android HistoryScreen。
struct HistoryView: View {
    @Query(sort: \TranslationRecord.createdAt, order: .reverse)
    private var records: [TranslationRecord]
    @Environment(\.modelContext) private var context

    private var zh: Bool { PromptTemplates.isZhUi }

    var body: some View {
        NavigationStack {
            Group {
                if records.isEmpty {
                    ContentUnavailableView(
                        zh ? "还没有翻译记录" : "No translations yet",
                        systemImage: "clock",
                        description: Text(zh ? "翻译成功后会自动记录在这里" : "Successful translations appear here")
                    )
                } else {
                    List {
                        ForEach(records, id: \.persistentModelID) { r in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text("\(r.sourceLang == "ZH" ? "中" : "EN") → \(r.targetLang == "ZH" ? "中" : "EN")")
                                        .font(.caption2.weight(.semibold))
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 2)
                                        .background(Capsule().fill(Color.brandPrimary.opacity(0.14)))
                                        .foregroundStyle(Color.brandPrimary)
                                    Spacer()
                                    Text(r.createdAt.formatted(date: .numeric, time: .shortened))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                                Text(r.sourceText)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(3)
                                Text(r.translatedText)
                                    .font(.body)
                                    .textSelection(.enabled)
                            }
                            .padding(.vertical, 4)
                            .swipeActions {
                                Button(role: .destructive) {
                                    context.delete(r)
                                    try? context.save()
                                } label: { Label(zh ? "删除" : "Delete", systemImage: "trash") }
                                Button {
                                    UIPasteboard.general.string = r.translatedText
                                } label: { Label(zh ? "复制" : "Copy", systemImage: "doc.on.doc") }
                            }
                        }
                    }
                }
            }
            .navigationTitle(zh ? "历史" : "History")
            .toolbar {
                if !records.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(zh ? "清空" : "Clear", role: .destructive) {
                            for r in records { context.delete(r) }
                            try? context.save()
                        }
                    }
                }
            }
        }
    }
}
