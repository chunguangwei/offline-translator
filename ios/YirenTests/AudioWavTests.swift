import XCTest
@testable import Yiren

final class AudioWavTests: XCTestCase {

    func testWavHeaderMagicAndSize() {
        let pcm = Data(count: 320)
        let wav = AudioRecorder.wavData(from: pcm)
        XCTAssertEqual(wav.count, 44 + pcm.count)
        XCTAssertEqual(String(data: wav[0..<4], encoding: .ascii), "RIFF")
        XCTAssertEqual(String(data: wav[8..<12], encoding: .ascii), "WAVE")
        XCTAssertEqual(String(data: wav[36..<40], encoding: .ascii), "data")
    }

    func testWavHeaderFieldsLittleEndian() {
        let pcm = Data(count: 1000)
        let wav = AudioRecorder.wavData(from: pcm)
        func le32(_ off: Int) -> Int {
            Int(wav[off]) | Int(wav[off + 1]) << 8 | Int(wav[off + 2]) << 16 | Int(wav[off + 3]) << 24
        }
        XCTAssertEqual(le32(24), 16_000)        // sample rate
        XCTAssertEqual(le32(40), pcm.count)     // data size
        XCTAssertEqual(le32(4), pcm.count + 36) // riff size
    }

    func testPeakSilenceAndFullScale() {
        XCTAssertEqual(AudioRecorder.peak(of: Data(count: 64)), 0, accuracy: 0.0001)
        var loud = Data(count: 64)
        loud[10] = 0xFF
        loud[11] = 0x7F // little-endian 0x7FFF 满幅样本
        XCTAssertGreaterThan(AudioRecorder.peak(of: loud), 0.99)
    }

    func testPeakEmptySafe() {
        XCTAssertEqual(AudioRecorder.peak(of: Data()), 0)
        XCTAssertEqual(AudioRecorder.peak(of: Data([0x01])), 0)
    }
}
