import AVFoundation
import Foundation

/// 16-kHz 单声道 PCM16 录音器 —— 对应 Android `PcmAudioRecorder`。
/// 产出裸 PCM 字节 + 实时幅度（驱动波形 UI）；`wavData()` 包 44 字节 RIFF 头
/// 供 LiteRT-LM 音频输入（引擎要 WAV 不要裸 PCM）。
@MainActor
final class AudioRecorder: ObservableObject {
    static let shared = AudioRecorder()

    @Published private(set) var isRecording = false
    /// 最近的归一化幅度序列（0~1，驱动波形条）。
    @Published private(set) var amplitudes: [Float] = []

    private let engine = AVAudioEngine()
    private var captured = Data()

    /// 请求麦克风权限（异步，true=已授权）。
    func requestPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioApplication.requestRecordPermission { granted in
                cont.resume(returning: granted)
            }
        }
    }

    func start() throws {
        // 防重入：上一段没停先停掉丢弃（与 Android 跨页防御一致）。
        if isRecording { _ = stop() }
        captured.removeAll()
        amplitudes.removeAll()

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement)
        try session.setActive(true)

        let input = engine.inputNode
        let hwFormat = input.outputFormat(forBus: 0)
        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true
        ), let converter = AVAudioConverter(from: hwFormat, to: targetFormat) else {
            throw NSError(domain: "AudioRecorder", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "音频格式初始化失败"])
        }

        input.installTap(onBus: 0, bufferSize: 4096, format: hwFormat) { [weak self] buffer, _ in
            // 硬件采样率 → 16k PCM16 转换。
            let ratio = 16_000.0 / hwFormat.sampleRate
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 16
            guard let out = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else { return }
            var consumed = false
            converter.convert(to: out, error: nil) { _, status in
                if consumed {
                    status.pointee = .noDataNow
                    return nil
                }
                consumed = true
                status.pointee = .haveData
                return buffer
            }
            guard out.frameLength > 0, let ch = out.int16ChannelData?[0] else { return }
            let count = Int(out.frameLength)
            let data = Data(bytes: ch, count: count * 2)
            var peak: Float = 0
            for i in 0..<count {
                let v = abs(Float(ch[i]) / 32767)
                if v > peak { peak = v }
            }
            Task { @MainActor [weak self] in
                guard let self, self.isRecording else { return }
                self.captured.append(data)
                self.amplitudes.append(min(peak * 2.4, 1))
                if self.amplitudes.count > 48 { self.amplitudes.removeFirst(self.amplitudes.count - 48) }
            }
        }

        engine.prepare()
        try engine.start()
        isRecording = true
    }

    /// 停止并返回累积的 16k PCM16 裸字节。
    func stop() -> Data {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        isRecording = false
        let pcm = captured
        captured = Data()
        return pcm
    }

    /// PCM16 峰值（0~1），判断是否真采到了声音。
    nonisolated static func peak(of pcm: Data) -> Float {
        guard pcm.count >= 2 else { return 0 }
        var maxAbs: Int32 = 0
        pcm.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) in
            let samples = ptr.bindMemory(to: Int16.self)
            for s in samples {
                let a = abs(Int32(s))
                if a > maxAbs { maxAbs = a }
            }
        }
        return Float(maxAbs) / 32767
    }

    /// 裸 PCM16 → 最小 RIFF/WAVE（44 字节头）。
    nonisolated static func wavData(from pcm: Data, sampleRate: Int = 16_000) -> Data {
        var out = Data(capacity: 44 + pcm.count)
        func w16(_ v: Int) { out.append(UInt8(v & 0xff)); out.append(UInt8((v >> 8) & 0xff)) }
        func w32(_ v: Int) {
            w16(v & 0xffff); w16((v >> 16) & 0xffff)
        }
        out.append(contentsOf: Array("RIFF".utf8)); w32(pcm.count + 36)
        out.append(contentsOf: Array("WAVE".utf8))
        out.append(contentsOf: Array("fmt ".utf8)); w32(16); w16(1); w16(1)
        w32(sampleRate); w32(sampleRate * 2); w16(2); w16(16)
        out.append(contentsOf: Array("data".utf8)); w32(pcm.count)
        out.append(pcm)
        return out
    }
}
