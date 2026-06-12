import XCTest
@testable import Yiren

final class ModelRegistryTests: XCTestCase {

    func testAllModelsHaveThreeDistinctSources() {
        for m in ModelRegistry.all {
            let urls = Set([m.urlHf, m.urlMirror, m.urlModelScope].map(\.absoluteString))
            XCTAssertEqual(urls.count, 3, "\(m.id) 三个下载源应互不相同")
            XCTAssertTrue(m.urlModelScope.absoluteString.contains("modelscope.cn"))
            XCTAssertTrue(m.urlHf.absoluteString.contains("huggingface.co"))
        }
    }

    func testDefaultModelIsE2B() {
        XCTAssertEqual(ModelRegistry.defaultModel.id, "gemma-4-e2b-litertlm")
        XCTAssertNotNil(ModelRegistry.byId("gemma-4-e2b-litertlm"))
        XCTAssertNil(ModelRegistry.byId("nope"))
    }

    func testFileNamesMatchUrls() {
        for m in ModelRegistry.all {
            XCTAssertTrue(m.urlModelScope.absoluteString.hasSuffix(m.fileName))
            XCTAssertTrue(m.urlHf.absoluteString.hasSuffix(m.fileName))
        }
    }
}
