import XCTest
@testable import Yiren

/// 覆盖单词本内已有词条的去重：entriesToRemoveForDedup。
final class DedupExistingTests: XCTestCase {

    private func r(_ id: String, _ key: String, box: Int = 0, createdAt: Double = 0) -> DedupRow {
        DedupRow(id: id, key: key, box: box, createdAt: createdAt)
    }

    func testKeepsHighestBox() {
        // 同键三条（大小写/空白已规范化为同一 key），box 不同 → 保留 box 最高，删其余两条。
        let rows = [
            r("a", "apple", box: 1, createdAt: 100),
            r("b", "apple", box: 5, createdAt: 200),
            r("c", "apple", box: 3, createdAt: 300),
        ]
        XCTAssertEqual(entriesToRemoveForDedup(rows), ["a", "c"])
    }

    func testBoxTieKeepsEarliestCreatedAt() {
        // box 并列 → 保留 createdAt 最早的，删其余。
        let rows = [
            r("a", "cat", box: 2, createdAt: 300),
            r("b", "cat", box: 2, createdAt: 100), // 最早 → 保留
            r("c", "cat", box: 2, createdAt: 200),
        ]
        XCTAssertEqual(entriesToRemoveForDedup(rows), ["a", "c"])
    }

    func testNoDuplicatesEmpty() {
        let rows = [
            r("a", "apple", box: 1),
            r("b", "banana", box: 2),
            r("c", "cat", box: 0),
        ]
        XCTAssertEqual(entriesToRemoveForDedup(rows), [])
    }

    func testMultipleIndependentGroups() {
        let rows = [
            r("a", "apple", box: 1, createdAt: 100),
            r("b", "apple", box: 4, createdAt: 200), // apple 保留
            r("c", "dog", box: 2, createdAt: 100),   // dog 保留（最早，box 并列）
            r("d", "dog", box: 2, createdAt: 300),
            r("e", "egg", box: 0, createdAt: 100),   // 单条不动
        ]
        XCTAssertEqual(entriesToRemoveForDedup(rows), ["a", "d"])
    }

    func testSingleRowEmpty() {
        XCTAssertEqual(entriesToRemoveForDedup([r("a", "apple", box: 3)]), [])
    }

    func testEmptyInputEmpty() {
        XCTAssertEqual(entriesToRemoveForDedup([]), [])
    }
}
