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

    fun chatSystem(): String =
        "You are an intelligent assistant running fully on-device. Be concise, helpful, " +
            "and respond in the user's language. If the user mixes languages, mirror them."

    fun chat(history: List<Pair<String, String>>, userInput: String): String {
        val sb = StringBuilder()
        // Inject system prompt as the first user turn (Gemma 4 has no dedicated system role).
        sb.append(USER_OPEN).append(chatSystem()).append(TURN_END)
        sb.append(MODEL_OPEN).append("Understood.").append(TURN_END)
        for ((role, content) in history.takeLast(8)) {
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
