import XCTest
@testable import Yiren

final class VocabExtractorTests: XCTestCase {

    func testParseLineFull() {
        let d = VocabExtractor.parseLine("ubiquitous => 无处不在的 => adj. The smartphone is ubiquitous.")
        XCTAssertEqual(d?.english, "ubiquitous")
        XCTAssertEqual(d?.chinese, "无处不在的")
        XCTAssertTrue(d?.note.contains("ubiquitous") == true)
    }

    func testParseLineNoNote() {
        let d = VocabExtractor.parseLine("apple => 苹果 =>")
        XCTAssertEqual(d?.english, "apple")
        XCTAssertEqual(d?.chinese, "苹果")
        XCTAssertEqual(d?.note, "")
    }

    func testParseLineRejectsGarbage() {
        XCTAssertNil(VocabExtractor.parseLine("没有箭头的行"))
        XCTAssertNil(VocabExtractor.parseLine("123 => 456"))      // 无字母
        XCTAssertNil(VocabExtractor.parseLine("word =>"))          // 无中文释义
        XCTAssertNil(VocabExtractor.parseLine("<turn|>x => y"))    // 停止符开头截没了
    }

    func testChunkedRespectsSizeAndLines() {
        let text = Array(repeating: String(repeating: "a", count: 100), count: 30).joined(separator: "\n")
        let chunks = VocabExtractor.chunked(text, size: 500)
        XCTAssertGreaterThan(chunks.count, 1)
        XCTAssertTrue(chunks.allSatisfy { $0.count <= 600 }) // 行边界切，略有余量
        XCTAssertEqual(chunks.joined().filter { $0 == "a" }.count, 3000) // 无丢字
    }
}
