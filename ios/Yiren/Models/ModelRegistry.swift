import Foundation

/// 可下载的 LiteRT-LM 模型 —— 与 Android `ModelRegistry.kt` 同源同 URL。
struct ModelInfo: Identifiable, Equatable {
    let id: String
    let displayName: String
    let sizeBytes: Int64
    let supportsImage: Bool
    let supportsAudio: Bool
    let maxTokens: Int
    let urlHf: URL
    let urlMirror: URL
    /// ModelScope（魔搭/阿里云 OSS）直链 —— 国内最快源（ImagePilot 实测）。
    /// hf-mirror 对 HF Xet 仓库只 308 跳回被墙的 huggingface.co，没有加速效果。
    let urlModelScope: URL
    let fileName: String
    let summary: String
}

enum ModelRegistry {
    /// Gemma 4 E2B IT — 默认推荐，多模态，免 token。
    static let gemma4E2B = ModelInfo(
        id: "gemma-4-e2b-litertlm",
        displayName: "Gemma 4 · E2B IT · 多模态",
        sizeBytes: 2_588_147_712,
        supportsImage: true,
        supportsAudio: true,
        maxTokens: 4096,
        urlHf: URL(string: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm")!,
        urlMirror: URL(string: "https://hf-mirror.com/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm")!,
        urlModelScope: URL(string: "https://modelscope.cn/models/litert-community/gemma-4-E2B-it-litert-lm/resolve/master/gemma-4-E2B-it.litertlm")!,
        fileName: "gemma-4-E2B-it.litertlm",
        summary: "免 token · 文本＋图像＋语音 · 推荐默认"
    )

    /// Gemma 4 E4B IT — 大尺寸多模态。
    static let gemma4E4B = ModelInfo(
        id: "gemma-4-e4b-litertlm",
        displayName: "Gemma 4 · E4B IT · 多模态",
        sizeBytes: 3_659_530_240,
        supportsImage: true,
        supportsAudio: true,
        maxTokens: 4096,
        urlHf: URL(string: "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm")!,
        urlMirror: URL(string: "https://hf-mirror.com/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm")!,
        urlModelScope: URL(string: "https://modelscope.cn/models/litert-community/gemma-4-E4B-it-litert-lm/resolve/master/gemma-4-E4B-it.litertlm")!,
        fileName: "gemma-4-E4B-it.litertlm",
        summary: "免 token · 大尺寸 · 文本＋图像＋语音"
    )

    static let all: [ModelInfo] = [gemma4E2B, gemma4E4B]

    /// 首次启动的默认模型。
    static let defaultModel = gemma4E2B

    static func byId(_ id: String?) -> ModelInfo? {
        all.first { $0.id == id }
    }
}
