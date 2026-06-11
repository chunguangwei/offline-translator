import Foundation

/// Prompt 模板 —— 指令文本与 Android `PromptTemplates.kt` 完全一致。
/// 差异：iOS 的 LiteRT-LM Swift `Conversation` 自带 Gemma 4 聊天模板渲染，
/// 这里**只给指令正文**，不再手工包 `<|turn>` 标记（Android 是裸 prompt 需要手工包）。
enum PromptTemplates {

    /// Gemma 4 的回复终止标记 —— 防御性裁剪用（引擎通常已处理）。
    static let stopTokens = ["<turn|>", "<|turn>", "<eos>", "<end_of_turn>", "<start_of_turn>"]

    /// 在最早出现的停止符处截断。
    static func trimAtStop(_ text: String) -> String {
        var cut: String.Index? = nil
        for token in stopTokens {
            if let r = text.range(of: token) {
                if cut == nil || r.lowerBound < cut! { cut = r.lowerBound }
            }
        }
        if let cut { return String(text[..<cut]) }
        return text
    }

    static func translate(_ text: String, fromZh: Bool) -> String {
        if fromZh {
            return "You are a precise translator. Translate the following Chinese text to natural, idiomatic English. "
                + "Output ONLY the English translation — exactly ONE translation, in English ONLY. "
                + "Do NOT translate into any other language. No quotes, no explanation, no extra text.\n\nChinese: \(text)"
        } else {
            return "你是一个精准的翻译。把下面的英文翻译成自然、地道的简体中文。"
                + "只输出唯一一份中文译文——不要翻译成任何其他语言、不要给多个版本、"
                + "不要加引号、不要解释、不要任何多余内容。\n\nEnglish: \(text)"
        }
    }
}
