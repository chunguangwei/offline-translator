import Foundation

/// 批次内按英文键（trim+lowercase）去重 + 排除 existingKeys，保持原顺序；空英文丢弃。
func dedupDrafts(_ drafts: [VocabDraft], existingKeys: Set<String>) -> [VocabDraft] {
    var seen = existingKeys
    var out: [VocabDraft] = []
    for d in drafts {
        let k = d.english.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if k.isEmpty { continue }
        if !seen.contains(k) { seen.insert(k); out.append(d) }
    }
    return out
}
