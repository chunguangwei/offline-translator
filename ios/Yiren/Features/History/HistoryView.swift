import SwiftData
import SwiftUI
import UIKit

/// 历史 Tab —— 纯翻译记录（学习入口已独立成「学习」Tab）。
/// 星标 = 收藏进生词本（练习去「学习」页）。
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
                                    // 收藏进生词本（练习入口在「学习」Tab）。
                                    Button {
                                        r.starred.toggle()
                                        try? context.save()
                                        if r.starred { SrsStore.addCard(context, sourceType: "STARRED", sourceId: r.uid) }
                                        else { SrsStore.removeCard(context, sourceType: "STARRED", sourceId: r.uid) }
                                    } label: {
                                        Image(systemName: r.starred ? "star.fill" : "star")
                                            .foregroundStyle(r.starred ? Color.brandPrimary : Color.secondary)
                                    }
                                    .buttonStyle(.borderless)
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
                                    SrsStore.removeCard(context, sourceType: "STARRED", sourceId: r.uid)
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
                            SrsStore.removeAll(context, sourceType: "STARRED")
                            for r in records { context.delete(r) }
                            try? context.save()
                        }
                    }
                }
            }
        }
    }
}
