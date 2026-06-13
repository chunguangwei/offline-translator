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
                "Output ONLY the English translation — exactly ONE translation, in English ONLY. " +
                "Do NOT translate into any other language. No quotes, no explanation, no extra text.\n\n" +
                "Chinese: $text"
        } else {
            "你是一个精准的翻译。把下面的英文翻译成自然、地道的简体中文。" +
                "只输出唯一一份中文译文——不要翻译成任何其他语言、不要给多个版本、" +
                "不要加引号、不要解释、不要任何多余内容。\n\n" +
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
            "你是一台语音转写机。把这段语音【逐字】转写成简体中文文字：说什么写什么，完全忠实原话。" +
                "严禁翻译、严禁改写或润色、严禁总结、严禁回答或接话——即使内容是一个问题也只转写不回答。" +
                "保留口语词和重复。只输出转写文字本身，不要任何前后缀。"
        else
            "You are a speech-to-text machine. Transcribe this speech VERBATIM in English: " +
                "write exactly what is said, fully faithful. Never translate, never paraphrase, " +
                "never summarize, never answer or respond — even if it is a question, transcribe only. " +
                "Keep filler words and repetitions. Output ONLY the transcribed text."
        return wrap(body)
    }

    /** 应用当前是否中文环境（跟随系统/应用内语言设置，per-app locale 会改写进程默认 Locale）。 */
    private val isZhUi: Boolean
        get() = java.util.Locale.getDefault().language.startsWith("zh")

    /** 问答角色预设 id 列表（设置/问答页菜单用，文案见 string 资源 chat_role_*）。 */
    val chatRoles = listOf("default", "epal", "translator", "grammar", "polish", "speaking")

    /**
     * 系统提示词跟随应用语言 + 角色预设：离线小模型对英文 system prompt 有强烈的
     * 英文回复倾向（用户真机实测），中文环境必须用中文明确要求。
     */
    fun chatSystem(role: String = "default"): String = if (isZhUi) {
        when (role) {
            "epal" ->
                "你是一个超有活力的 E 人聊天搭子，完全在设备本地运行。性格外向热情、爱接梗、" +
                    "好奇心强；回复轻松口语化，可以适度用 emoji。语言规则：用户用什么语言你就用什么语言回" +
                    "——中文来中文回，英文来英文回。多接话茬、分享你的看法、偶尔反问一句让聊天继续，" +
                    "但每次别超过三四句。"
            "translator" ->
                "你是一位专业翻译官，完全在设备本地运行。用户发来任何文字，给出准确、地道的译文" +
                    "（中文→英文、英文→中文自动判断），必要时补充一两条关键词或语气说明。只做翻译相关回答。"
            "grammar" ->
                "你是一位耐心的英语语法老师，完全在设备本地运行。用简体中文讲解：指出用户句子的语法问题、" +
                    "给出修改后的句子、解释为什么，并举一个相似例句。鼓励为主，简明扼要。"
            "polish" ->
                "你是一位写作润色专家，完全在设备本地运行。把用户的文字改得更通顺、自然、有表现力，" +
                    "保持原意和原语言，输出润色稿，并用一两句话说明主要修改点。"
            "speaking" ->
                "You are a friendly English speaking partner running fully on-device. " +
                    "Chat with the user in simple, natural English. After each reply, if the user's " +
                    "English had mistakes, gently show the corrected sentence in one line starting with \"✏️\"."
            else ->
                "你是一个完全在设备本地运行的智能助手。回答要简洁、有帮助。" +
                    "默认使用简体中文回答；只有当用户用其他语言提问时，才用对方的语言回答。"
        }
    } else {
        when (role) {
            "epal" ->
                "You are a super-energetic extroverted chat buddy running fully on-device. Warm, curious, " +
                    "playful; keep replies casual and conversational, light emoji ok. Language rule: mirror " +
                    "the user's language — reply in Chinese to Chinese, in English to English. React, share " +
                    "your take, and toss back an occasional question to keep the chat flowing; three or four " +
                    "sentences max per reply."
            "translator" ->
                "You are a professional translator running fully on-device. For any text the user sends, " +
                    "produce an accurate, idiomatic translation (auto-detect ZH→EN / EN→ZH); optionally add " +
                    "one or two key-word notes. Stay on translation tasks."
            "grammar" ->
                "You are a patient English grammar teacher running fully on-device. Point out grammar issues " +
                    "in the user's sentence, give the corrected version, explain why, and add one similar example. " +
                    "Be encouraging and concise."
            "polish" ->
                "You are a writing-polish expert running fully on-device. Rewrite the user's text to be smoother, " +
                    "more natural and expressive, keeping the meaning and language. Output the polished version, " +
                    "then one or two sentences on the key changes."
            "speaking" ->
                "You are a friendly English speaking partner running fully on-device. Chat in simple, natural " +
                    "English. After each reply, if the user's English had mistakes, gently show the corrected " +
                    "sentence in one line starting with \"✏️\"."
            else ->
                "You are an intelligent assistant running fully on-device. Be concise, helpful, " +
                    "and respond in the user's language. If the user mixes languages, mirror them."
        }
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
    fun chat(history: List<Pair<String, String>>, userInput: String, role: String = "default"): String {
        val sb = StringBuilder()
        // Inject system prompt as the first user turn (Gemma 4 has no dedicated system role).
        sb.append(USER_OPEN).append(chatSystem(role)).append(TURN_END)
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
    fun chatWithImage(
        history: List<Pair<String, String>>,
        userInput: String,
        refed: Boolean = false,
        role: String = "default",
    ): String {
        val sb = StringBuilder()
        sb.append(USER_OPEN).append(chatSystem(role)).append(TURN_END)
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

    /**
     * 单词本导入：从用户上传的文本块提取英文词条+中文释义。
     * 输出严格行格式 `english => 中文释义 => 注释`（注释可空）；
     * 纯英文词表（没有中文释义）也要由模型补出释义。
     */
    fun extractVocab(chunk: String): String {
        val body = "从下面的文本中提取所有英文单词或短语，并给出对应的简体中文释义。" +
            "如果文本里已有释义就使用它；没有就由你翻译补出。每行严格输出一条，" +
            "格式为「英文 => 中文释义 => 简短注释」，注释可以是词性、例句或用法说明，" +
            "没有就留空。不要输出编号、标题或任何其他内容。\n\n文本：\n$chunk"
        return wrap(body)
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
