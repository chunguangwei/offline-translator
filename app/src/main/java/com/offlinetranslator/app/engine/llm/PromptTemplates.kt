package com.offlinetranslator.app.engine.llm

/**
 * Gemma 4 (litert-lm) chat template:
 *   <|turn>user\n{content}<turn|>\n<|turn>model\n
 * Model emits `<turn|>` (and/or `<eos>`) at end of its response.
 *
 * We post-process the streamed output to strip everything from the first
 * stop token onward — see [stopTokens] / [trimAtStop].
 */
object PromptTemplates {

    private const val USER_OPEN = "<|turn>user\n"
    private const val MODEL_OPEN = "<|turn>model\n"
    private const val TURN_END = "<turn|>\n"

    /** Tokens that mark the end of the model's reply for Gemma 4. */
    val stopTokens: List<String> = listOf("<turn|>", "<|turn>", "<eos>", "<end_of_turn>", "<start_of_turn>")

    /** Trim at the earliest stop-token occurrence (if any). */
    fun trimAtStop(text: String): String {
        var cut = -1
        for (tok in stopTokens) {
            val idx = text.indexOf(tok)
            if (idx >= 0 && (cut < 0 || idx < cut)) cut = idx
        }
        return if (cut >= 0) text.substring(0, cut) else text
    }

    private fun wrap(userContent: String): String =
        USER_OPEN + userContent.trim() + TURN_END + MODEL_OPEN

    fun translate(text: String, fromZh: Boolean): String {
        val body = if (fromZh) {
            "You are a precise translator. Translate the following Chinese text to natural, idiomatic English. " +
                "Output ONLY the translation — no quotes, no explanation, no extra text.\n\n" +
                "Chinese: $text"
        } else {
            "你是一个精准的翻译。把下面的英文翻译成自然、地道的中文。" +
                "只输出译文，不要加引号、不要解释、不要任何多余内容。\n\n" +
                "English: $text"
        }
        return wrap(body)
    }

    /**
     * 纯逐字转写（ASR），不翻译。用于翻译页的语音输入：把语音转成文字回填输入框，
     * 由用户确认后再走正常翻译流程。
     */
    fun transcribeVerbatim(fromZh: Boolean): String {
        val body = if (fromZh)
            "请把这段语音逐字转写为简体中文。只输出转写出的文字本身，" +
                "不要翻译、不要解释、不要加任何前后缀或标点说明。"
        else
            "Transcribe this speech verbatim in English. Output ONLY the transcribed " +
                "text itself — do not translate, explain, or add any prefix/suffix."
        return wrap(body)
    }

    /** 应用当前是否中文环境（跟随系统/应用内语言设置，per-app locale 会改写进程默认 Locale）。 */
    private val isZhUi: Boolean
        get() = java.util.Locale.getDefault().language.startsWith("zh")

    /**
     * 系统提示词跟随应用语言：离线小模型对英文 system prompt 有强烈的英文回复倾向
    （用户真机实测中文提问也回英文），中文环境必须用中文明确要求。
     */
    fun chatSystem(): String = if (isZhUi) {
        "你是一个完全在设备本地运行的智能助手。回答要简洁、有帮助。" +
            "默认使用简体中文回答；只有当用户用其他语言提问时，才用对方的语言回答。"
    } else {
        "You are an intelligent assistant running fully on-device. Be concise, helpful, " +
            "and respond in the user's language. If the user mixes languages, mirror them."
    }

    /** 历史里带图轮次的标注（让模型知道哪轮发过图）。 */
    fun historyImageNote(content: String): String {
        val note = if (isZhUi) "（发送了一张图片）" else "(sent an image) "
        return if (content.isBlank()) note.trim() else note + content
    }

    /** 上下文压缩摘要的承上启下两轮（user/assistant），文案随应用语言。 */
    fun summaryBridgeTurns(summary: String): List<Pair<String, String>> = if (isZhUi) {
        listOf(
            "user" to "（此前对话的摘要）$summary",
            "assistant" to "好的，我已了解上文。",
        )
    } else {
        listOf(
            "user" to "(Summary of our earlier conversation) $summary",
            "assistant" to "Got it, I'm caught up.",
        )
    }

    /**
     * 多轮对话 prompt。history 含用户与助手双方消息，窗口大小由调用方控制
     * （ChatViewModel 负责摘要压缩 + 字数预算裁剪），这里不再二次截断。
     */
    fun chat(history: List<Pair<String, String>>, userInput: String): String {
        val sb = StringBuilder()
        // Inject system prompt as the first user turn (Gemma 4 has no dedicated system role).
        sb.append(USER_OPEN).append(chatSystem()).append(TURN_END)
        sb.append(MODEL_OPEN).append("Understood.").append(TURN_END)
        for ((role, content) in history) {
            if (role == "user") {
                sb.append(USER_OPEN).append(content.trim()).append(TURN_END)
            } else {
                sb.append(MODEL_OPEN).append(content.trim()).append(TURN_END)
            }
        }
        sb.append(USER_OPEN).append(userInput.trim()).append(TURN_END)
        sb.append(MODEL_OPEN)
        return sb.toString()
    }

    /** 把较早的对话压缩成简短摘要（长会话上下文压缩用）。 */
    fun summarize(conversation: String, previousSummary: String?): String {
        val pre = if (previousSummary.isNullOrBlank()) "" else "已有摘要：$previousSummary\n\n"
        val body = "请把下面的对话压缩成简洁的中文要点摘要，保留关键事实、名字、数字和结论，" +
            "不超过 200 字。只输出摘要本身，不要任何前后缀。\n\n${pre}对话：\n$conversation"
        return wrap(body)
    }

    /**
     * 带对话历史的图像问答：在多轮上下文之后追加一个"我发了张图片 + 问题"的用户轮，
     * 图片本体通过 [GemmaEngine.generateStream] 的 includeImage 一并送入。
     * 让看图提问也能延续上下文（而非每次孤立单轮）。
     *
     * @param refed true = 本轮没发新图，是把会话里此前发过的图重新喂入
     *              （追问场景），文案上要区分，免得模型以为又收到一张新图。
     */
    fun chatWithImage(history: List<Pair<String, String>>, userInput: String, refed: Boolean = false): String {
        val sb = StringBuilder()
        sb.append(USER_OPEN).append(chatSystem()).append(TURN_END)
        sb.append(MODEL_OPEN).append("Understood.").append(TURN_END)
        for ((role, content) in history) {
            if (role == "user") sb.append(USER_OPEN).append(content.trim()).append(TURN_END)
            else sb.append(MODEL_OPEN).append(content.trim()).append(TURN_END)
        }
        // 指令必须命令式：小模型偶尔意识不到附件存在而反过来索要图片，
        // 明确告知"图片已附上、直接回答、别再要"。文案随应用语言。
        val q: String
        val marker: String
        if (isZhUi) {
            q = userInput.ifBlank { "请识别并描述这张图片的内容。" }
            marker = if (refed) "（随本条消息重新附上了我此前发过的那张图片，请结合它回答。）"
            else "（一张图片已随本条消息一并发给你了，请直接基于这张图片回答，不要再让我提供图片。）"
        } else {
            q = userInput.ifBlank { "Describe what you see in this image." }
            marker = if (refed) "(My earlier image is re-attached to this message; answer with it in mind.) "
            else "(An image IS attached to this message. Answer directly based on it; do not ask me to provide one.) "
        }
        sb.append(USER_OPEN).append(marker).append(q).append(TURN_END)
        sb.append(MODEL_OPEN)
        return sb.toString()
    }

    fun visionDescribe(userPrompt: String): String {
        val q = userPrompt.ifBlank { "Please describe what you see in detail." }
        val body = "You can see the attached image. Answer the user's question accurately, " +
            "grounded in what is actually visible. Reply in the user's language.\n\nQuestion: $q"
        return wrap(body)
    }

    fun visionTranslateText(): String {
        val body = "You can see an image. Find ALL the text in the image, " +
            "then translate every line into the OPPOSITE language (Chinese ↔ English). " +
            "Output as: \"<original> => <translation>\" on each line."
        return wrap(body)
    }
}
