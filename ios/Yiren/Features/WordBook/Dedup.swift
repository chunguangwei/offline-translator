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

/// 一行去重输入：词条 uid、英文键（已规范化）、SRS box、创建时间。
struct DedupRow: Equatable {
    let id: String
    let key: String
    let box: Int
    let createdAt: Double   // timeIntervalSince1970
}

/// 找出单词本内重复英文键应删除的词条 uid：每组重复保留 box 最高、并列取 createdAt 最早的一条，其余删除。
func entriesToRemoveForDedup(_ rows: [DedupRow]) -> Set<String> {
    var remove = Set<String>()
    let groups = Dictionary(grouping: rows, by: { $0.key })
    for (_, group) in groups where group.count > 1 {
        // 保留：box 最大；并列取 createdAt 最小（最早）。
        let keep = group.max { a, b in
            if a.box != b.box { return a.box < b.box }       // box 大的胜出
            return a.createdAt > b.createdAt                 // box 相同：createdAt 小（早）的胜出
        }!
        for r in group where r.id != keep.id { remove.insert(r.id) }
    }
    return remove
}
