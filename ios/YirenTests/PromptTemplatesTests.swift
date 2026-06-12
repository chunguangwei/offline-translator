import XCTest
@testable import Yiren

final class PromptTemplatesTests: XCTestCase {

    // ── trimAtStop ──

    func testTrimAtStopPassThrough() {
        XCTAssertEqual(PromptTemplates.trimAtStop("Hello, world"), "Hello, world")
    }

    func testTrimAtEarliestStopToken() {
        XCTAssertEqual(PromptTemplates.trimAtStop("你好<turn|>多余<eos>"), "你好")
        XCTAssertEqual(PromptTemplates.trimAtStop("abc<eos>def<turn|>"), "abc")
    }

    func testTrimAtStopLeadingToken() {
        XCTAssertEqual(PromptTemplates.trimAtStop("<turn|>xxx"), "")
    }

    // ── 翻译指令约束（防"多国语言刹不住车"回归）──

    func testTranslatePromptDemandsSingleLanguage() {
        let zh2en = PromptTemplates.translate("你好", fromZh: true)
        XCTAssertTrue(zh2en.contains("ONE translation"))
        XCTAssertTrue(zh2en.contains("你好"))
        let en2zh = PromptTemplates.translate("hello", fromZh: false)
        XCTAssertTrue(en2zh.contains("唯一一份"))
    }

    // ── 角色预设 ──

    func testRolesHaveDistinctPromptsAndLabels() {
        let prompts = Set(PromptTemplates.chatRoles.map { PromptTemplates.chatSystem(role: $0) })
        XCTAssertEqual(prompts.count, PromptTemplates.chatRoles.count)
        let labels = Set(PromptTemplates.chatRoles.map { PromptTemplates.roleLabel($0) })
        XCTAssertEqual(labels.count, PromptTemplates.chatRoles.count)
    }

    func testUnknownRoleFallsBackToDefault() {
        XCTAssertEqual(PromptTemplates.chatSystem(role: "nope"), PromptTemplates.chatSystem(role: "default"))
    }

    // ── 图片标注 ──

    func testHistoryImageNote() {
        let empty = PromptTemplates.historyImageNote("")
        XCTAssertFalse(empty.isEmpty)
        XCTAssertTrue(PromptTemplates.historyImageNote("看这个").contains("看这个"))
    }

    // ── 摘要桥接 ──

    func testSummaryBridgeContainsSummary() {
        let bridge = PromptTemplates.summaryBridge("此前聊了天气")
        XCTAssertTrue(bridge.user.contains("此前聊了天气"))
        XCTAssertFalse(bridge.assistant.isEmpty)
    }
}
