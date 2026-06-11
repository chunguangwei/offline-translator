package com.offlinetranslator.app.core.data.model

/**
 * Available LiteRT-compatible models that the user can download / import.
 *
 * Currently exposes Google's Gemma 4 (LiteRT-LM) family — multimodal (text + image),
 * no Hugging Face token required. Mirrored on hf-mirror.com for fast access in CN.
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val ramRequirementMb: Int,
    val supportsImage: Boolean,
    val supportsAudio: Boolean,
    val maxTokens: Int,
    val urlHf: String,
    val urlMirror: String,
    /**
     * ModelScope（魔搭/阿里云 OSS）直链 —— 国内最快源（ImagePilot 实测）。
     * hf-mirror 对 HF Xet 仓库只会 308 跳回被墙的 huggingface.co，没有加速效果。
     */
    val urlModelScope: String,
    val sha256: String? = null,
    val fileName: String,
    val requiresToken: Boolean = false,
    val description: String = "",
)

object ModelRegistry {

    // ──────────────── Gemma 4 (LiteRT-LM, no token, multimodal) ────────────────
    //
    // These `.litertlm` bundles include both the language model AND a vision
    // encoder. Loaded via the official LiteRT-LM Android SDK
    // (`com.google.ai.edge.litertlm:litertlm-android`), they support text +
    // image input out of the box — no HF token, no .task file, just download
    // and go.

    /** Gemma 4 E2B IT — small multimodal model. **No HF token required.** */
    val GEMMA4_E2B = ModelInfo(
        id = "gemma-4-e2b-litertlm",
        displayName = "Gemma 4 · E2B IT · 多模态",
        sizeBytes = 2_588_147_712L,
        ramRequirementMb = 4096,
        supportsImage = true,
        supportsAudio = true,
        maxTokens = 4096,
        urlHf = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        urlMirror = "https://hf-mirror.com/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        urlModelScope = "https://modelscope.cn/models/litert-community/gemma-4-E2B-it-litert-lm/resolve/master/gemma-4-E2B-it.litertlm",
        fileName = "gemma-4-E2B-it.litertlm",
        requiresToken = false,
        description = "免 token · 文本＋图像＋语音 · 推荐默认",
    )

    /** Gemma 4 E4B IT — larger multimodal model. **No token required.** */
    val GEMMA4_E4B = ModelInfo(
        id = "gemma-4-e4b-litertlm",
        displayName = "Gemma 4 · E4B IT · 多模态",
        sizeBytes = 3_659_530_240L,
        ramRequirementMb = 5632,
        supportsImage = true,
        supportsAudio = true,
        maxTokens = 4096,
        urlHf = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        urlMirror = "https://hf-mirror.com/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        urlModelScope = "https://modelscope.cn/models/litert-community/gemma-4-E4B-it-litert-lm/resolve/master/gemma-4-E4B-it.litertlm",
        fileName = "gemma-4-E4B-it.litertlm",
        requiresToken = false,
        description = "免 token · 大尺寸 · 文本＋图像＋语音",
    )

    /** Default model when first launching the app. */
    val DEFAULT: ModelInfo = GEMMA4_E2B

    /** All exposed models. */
    val ALL: List<ModelInfo> = listOf(
        GEMMA4_E2B,
        GEMMA4_E4B,
    )

    fun byId(id: String?): ModelInfo? = ALL.firstOrNull { it.id == id }
}
