import XCTest
@testable import Yiren

final class DedupTests: XCTestCase {

    private func d(_ english: String, chinese: String = "中文", note: String = "") -> VocabDraft {
        VocabDraft(english: english, chinese: chinese, note: note)
    }

    func testWithinBatchDupKeepsFirst() {
        let input = [
            d("apple", chinese: "苹果"),
            d("Apple", chinese: "苹果2"),
            d(" apple ", chinese: "苹果3"),
        ]
        let out = dedupDrafts(input, existingKeys: [])
        XCTAssertEqual(out.count, 1)
        XCTAssertEqual(out[0].chinese, "苹果") // 保留第一条
    }

    func testExistingKeysExcludesDrafts() {
        let input = [d("Apple"), d("banana")]
        let out = dedupDrafts(input, existingKeys: ["apple"])
        XCTAssertEqual(out.map { $0.english }, ["banana"])
    }

    func testCaseAndWhitespaceInsensitiveCollapse() {
        let input = [d("Apple"), d(" apple "), d("APPLE")]
        let out = dedupDrafts(input, existingKeys: [])
        XCTAssertEqual(out.count, 1)
        XCTAssertEqual(out[0].english, "Apple")
    }

    func testBlankEnglishDropped() {
        let input = [d(""), d("   "), d("hello")]
        let out = dedupDrafts(input, existingKeys: [])
        XCTAssertEqual(out.map { $0.english }, ["hello"])
    }

    func testOrderPreserved() {
        let input = [d("cat"), d("apple"), d("banana"), d("Apple")]
        let out = dedupDrafts(input, existingKeys: [])
        XCTAssertEqual(out.map { $0.english }, ["cat", "apple", "banana"])
    }

    func testEmptyInputEmptyOutput() {
        XCTAssertEqual(dedupDrafts([], existingKeys: []).count, 0)
    }

    func testEmptyExistingKeysStillDedups() {
        let input = [d("one"), d("two"), d("two"), d("three")]
        let out = dedupDrafts(input, existingKeys: [])
        XCTAssertEqual(out.map { $0.english }, ["one", "two", "three"])
    }
}
