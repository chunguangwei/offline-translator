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

    /// 应用当前是否中文环境（跟随系统/应用语言）。
    static var isZhUi: Bool { Locale.preferredLanguages.first?.hasPrefix("zh") ?? false }

    /// 问答角色预设 id 列表（与 Android 一致）。
    static let chatRoles = ["default", "epal", "translator", "grammar", "polish", "speaking"]

    /// 角色展示名（菜单用）。
    static func roleLabel(_ id: String) -> String {
        if isZhUi {
            switch id {
            case "epal": return "E人陪聊"
            case "translator": return "翻译官"
            case "grammar": return "语法老师"
            case "polish": return "写作润色"
            case "speaking": return "英语陪练"
            default: return "默认助手"
            }
        }
        switch id {
        case "epal": return "Chatty pal"
        case "translator": return "Translator"
        case "grammar": return "Grammar coach"
        case "polish": return "Writing polish"
        case "speaking": return "English partner"
        default: return "Assistant"
        }
    }

    /// 问答系统提示词 —— 随应用语言 + 角色预设。离线小模型对英文 system prompt
    /// 有强烈的英文回复倾向（真机实测），中文环境必须用中文明确要求。
    static func chatSystem(role: String = "default") -> String {
        if isZhUi {
            switch role {
            case "epal":
                return "你是一个超有活力的 E 人聊天搭子，完全在设备本地运行。性格外向热情、爱接梗、"
                    + "好奇心强；回复轻松口语化，可以适度用 emoji。语言规则：用户用什么语言你就用什么语言回"
                    + "——中文来中文回，英文来英文回。多接话茬、分享你的看法、偶尔反问一句让聊天继续，"
                    + "但每次别超过三四句。"
            case "translator":
                return "你是一位专业翻译官，完全在设备本地运行。用户发来任何文字，给出准确、地道的译文"
                    + "（中文→英文、英文→中文自动判断），必要时补充一两条关键词或语气说明。只做翻译相关回答。"
            case "grammar":
                return "你是一位耐心的英语语法老师，完全在设备本地运行。用简体中文讲解：指出用户句子的语法问题、"
                    + "给出修改后的句子、解释为什么，并举一个相似例句。鼓励为主，简明扼要。"
            case "polish":
                return "你是一位写作润色专家，完全在设备本地运行。把用户的文字改得更通顺、自然、有表现力，"
                    + "保持原意和原语言，输出润色稿，并用一两句话说明主要修改点。"
            case "speaking":
                return "You are a friendly English speaking partner running fully on-device. "
                    + "Chat with the user in simple, natural English. After each reply, if the user's "
                    + "English had mistakes, gently show the corrected sentence in one line starting with \"✏️\"."
            default:
                return "你是一个完全在设备本地运行的智能助手。回答要简洁、有帮助。"
                    + "默认使用简体中文回答；只有当用户用其他语言提问时，才用对方的语言回答。"
            }
        }
        switch role {
        case "epal":
            return "You are a super-energetic extroverted chat buddy running fully on-device. Warm, curious, "
                + "playful; keep replies casual and conversational, light emoji ok. Language rule: mirror "
                + "the user's language — reply in Chinese to Chinese, in English to English. React, share "
                + "your take, and toss back an occasional question to keep the chat flowing; three or four "
                + "sentences max per reply."
        case "translator":
            return "You are a professional translator running fully on-device. For any text the user sends, "
                + "produce an accurate, idiomatic translation (auto-detect ZH→EN / EN→ZH); optionally add "
                + "one or two key-word notes. Stay on translation tasks."
        case "grammar":
            return "You are a patient English grammar teacher running fully on-device. Point out grammar issues "
                + "in the user's sentence, give the corrected version, explain why, and add one similar example. "
                + "Be encouraging and concise."
        case "polish":
            return "You are a writing-polish expert running fully on-device. Rewrite the user's text to be smoother, "
                + "more natural and expressive, keeping the meaning and language. Output the polished version, "
                + "then one or two sentences on the key changes."
        case "speaking":
            return "You are a friendly English speaking partner running fully on-device. Chat in simple, natural "
                + "English. After each reply, if the user's English had mistakes, gently show the corrected "
                + "sentence in one line starting with \"✏️\"."
        default:
            return "You are an intelligent assistant running fully on-device. Be concise, helpful, "
                + "and respond in the user's language. If the user mixes languages, mirror them."
        }
    }

    /// 语音逐字转写（翻译/问答的语音输入法：转写回填输入框，用户确认后再发）。
    static func transcribeVerbatim(zh: Bool) -> String {
        if zh {
            return "请把这段语音逐字转写为简体中文。只输出转写出的文字本身，"
                + "不要翻译、不要解释、不要加任何前后缀或标点说明。"
        }
        return "Transcribe this speech verbatim in English. Output ONLY the transcribed "
            + "text itself — do not translate, explain, or add any prefix/suffix."
    }

    /// 把较早的对话压缩成简短摘要（长会话上下文压缩用）。
    static func summarize(conversation: String, previousSummary: String?) -> String {
        if isZhUi {
            let pre = (previousSummary?.isEmpty == false) ? "已有摘要：\(previousSummary!)\n\n" : ""
            return "请把下面的对话压缩成简洁的中文要点摘要，保留关键事实、名字、数字和结论，"
                + "不超过 200 字。只输出摘要本身，不要任何前后缀。\n\n\(pre)对话：\n\(conversation)"
        }
        let pre = (previousSummary?.isEmpty == false) ? "Existing summary: \(previousSummary!)\n\n" : ""
        return "Summarize the conversation below into concise bullet points, keeping key facts, "
            + "names, numbers and conclusions, within 150 words. Output ONLY the summary.\n\n\(pre)Conversation:\n\(conversation)"
    }

    /// 上下文压缩摘要的承上启下两轮（user → assistant），文案随应用语言。
    static func summaryBridge(_ summary: String) -> (user: String, assistant: String) {
        if isZhUi {
            return ("（此前对话的摘要）\(summary)", "好的，我已了解上文。")
        }
        return ("(Summary of our earlier conversation) \(summary)", "Got it, I'm caught up.")
    }

    /// 历史里带图轮次的标注。
    static func historyImageNote(_ content: String) -> String {
        let note = isZhUi ? "（发送了一张图片）" : "(sent an image) "
        return content.isEmpty ? note.trimmingCharacters(in: .whitespaces) : note + content
    }

    /// 当前轮图片指令（命令式：明确"图片已附上"，防小模型反过来索要图片）。
    /// - Parameter refed: true = 重喂此前发过的图（追问场景）。
    static func imageTurn(_ userInput: String, refed: Bool) -> String {
        if isZhUi {
            let q = userInput.isEmpty ? "请识别并描述这张图片的内容。" : userInput
            let marker = refed
                ? "（随本条消息重新附上了我此前发过的那张图片，请结合它回答。）"
                : "（一张图片已随本条消息一并发给你了，请直接基于这张图片回答，不要再让我提供图片。）"
            return marker + q
        }
        let q = userInput.isEmpty ? "Describe what you see in this image." : userInput
        let marker = refed
            ? "(My earlier image is re-attached to this message; answer with it in mind.) "
            : "(An image IS attached to this message. Answer directly based on it; do not ask me to provide one.) "
        return marker + q
    }

    /// 拍照/选图翻译：识别图中文字并逐行「原文 => 译文」对照（中英互译自动判向）。
    static func visionTranslateText() -> String {
        if isZhUi {
            return "你能看到附带的图片。找出图片里的全部文字，把每一行翻译成对应的另一种语言"
                + "（中文→英文、英文→中文）。按「原文 => 译文」格式逐行输出，不要其他内容。"
        }
        return "You can see the attached image. Find ALL the text in the image, then translate "
            + "every line into the OPPOSITE language (Chinese ↔ English). "
            + "Output as: \"<original> => <translation>\" per line, nothing else."
    }

    /// 单词本导入：从文本块提取英文词条+中文释义（与 Android 同文案）。
    static func extractVocab(_ chunk: String) -> String {
        "从下面的文本中提取所有英文单词或短语，并给出对应的简体中文释义。"
            + "如果文本里已有释义就使用它；没有就由你翻译补出。每行严格输出一条，"
            + "格式为「英文 => 中文释义 => 简短注释」，注释可以是词性、例句或用法说明，"
            + "没有就留空。不要输出编号、标题或任何其他内容。\n\n文本：\n\(chunk)"
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
