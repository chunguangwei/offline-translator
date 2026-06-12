package com.offlinetranslator.app.engine.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.offlinetranslator.app.core.data.AppPreferencesRepository
import com.offlinetranslator.app.core.data.InferenceBackend
import com.offlinetranslator.app.core.data.model.ModelInfo
import com.offlinetranslator.app.core.data.model.ModelRegistry
import com.offlinetranslator.app.core.data.model.ModelStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OT-Gemma"

/** High-level engine state for UI consumption. */
/**
 * Engine lifecycle states.
 *
 * MODEL_MISSING is distinct from ERROR: it means the user simply hasn't
 * downloaded a model yet (a normal first-run state), so we don't want the UI
 * shouting about a failure. Real failures (init crash, OOM, GPU driver bug)
 * use ERROR.
 */
enum class EngineState { IDLE, LOADING, READY, ERROR, MODEL_MISSING }

/** Thrown by [GemmaEngine.ensureLoaded] when the requested model file isn't on disk yet. */
class ModelMissingException(val model: ModelInfo) :
    Exception("Model not downloaded: ${model.displayName}")


/** Concrete backend that ended up being used after fallback negotiation. */
enum class ActiveBackend { GPU, CPU, MIXED, UNKNOWN }

data class EngineStatus(
    val state: EngineState = EngineState.IDLE,
    val activeModel: ModelInfo? = null,
    val errorMessage: String? = null,
    /** Backend the user selected (AUTO/GPU/CPU). */
    val requestedBackend: InferenceBackend = InferenceBackend.AUTO,
    /** Backend actually loaded (after device-capability fallbacks). */
    val activeBackend: ActiveBackend = ActiveBackend.UNKNOWN,
    /** True when user asked for GPU but we had to fall back to CPU. */
    val backendFellBack: Boolean = false,
)

/**
 * Wraps Google's official LiteRT-LM Android SDK (`com.google.ai.edge.litertlm`)
 * which is the runtime designed for the `.litertlm` model bundles produced by
 * `litert-community/gemma-4-*-litert-lm`.
 *
 * Replaces the previous MediaPipe `tasks-genai` integration which couldn't
 * decode the vision encoder graph in those bundles ("Vision encoder must have
 * exactly one input tensor"). LiteRT-LM accepts `Content.ImageBytes(...)`
 * directly and handles the multimodal preprocessing internally.
 *
 * Concurrency: the LiteRT-LM `Engine` is single-instance / single-tenant per
 * process — concurrent generations on the same conversation corrupt the
 * KV-cache. We serialize all `generateStream` calls behind [genMutex].
 */
@Singleton
class GemmaEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: ModelStorage,
    private val prefs: AppPreferencesRepository,
) {
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    /** Guards [ensureLoaded] / [unload] (engine lifecycle). */
    private val mutex = Mutex()
    /** Guards [generateStream] — Conversation.sendMessageAsync is single-tenant. */
    private val genMutex = Mutex()

    private val _status = MutableStateFlow(EngineStatus())
    val status = _status.asStateFlow()

    private var engine: Engine? = null
    private var loadedModelId: String? = null
    /** Tracks whether the active model was initialized with vision modality enabled. */
    private var visionEnabled: Boolean = false
    /** Tracks whether the active model has the audio encoder enabled. */
    private var audioEnabled: Boolean = false

    /** Whether the currently-loaded engine can accept audio input. */
    fun isAudioEnabled(): Boolean = audioEnabled

    suspend fun ensureLoaded(): Result<ModelInfo> = mutex.withLock {
        val activeId = prefs.flow.first().activeModelId ?: ModelRegistry.DEFAULT.id
        val info = ModelRegistry.byId(activeId) ?: ModelRegistry.DEFAULT

        // Engine already loaded with the right model? Done.
        if (engine != null && loadedModelId == info.id) {
            _status.value = EngineStatus(EngineState.READY, info)
            return@withLock Result.success(info)
        }

        val file = storage.fileFor(info)
        if (!file.exists()) {
            // First-run, normal: just say "no model yet". Surface a typed
            // exception so callers can branch (e.g. the Voice screen offers a
            // "设置 Tab 下载模型" CTA instead of an error toast).
            _status.value = EngineStatus(
                state = EngineState.MODEL_MISSING,
                activeModel = info,
                errorMessage = null,
            )
            return@withLock Result.failure(ModelMissingException(info))
        }

        // Tear down any previously loaded engine before loading the new one.
        runCatching { engine?.close() }
        engine = null
        loadedModelId = null

        _status.value = EngineStatus(state = EngineState.LOADING, activeModel = info)
        return@withLock runCatching {
            withContext(ioDispatcher) {
                val backendPref = prefs.flow.first().backend
                // Build (mainCandidates, visionCandidates) per user preference.
                // Each list is tried in order; first successful initialize() wins.
                //  AUTO  → GPU first, fall back to CPU on driver issues.
                //  GPU   → GPU first; fall back to CPU only if GPU truly fails
                //         (with backendFellBack=true so UI can warn the user).
                //  CPU   → CPU only — never silently switch to GPU.
                val (mainCandidates, visionCandidates) = backendChain(backendPref, info)
                Log.i(
                    TAG,
                    "Loading id=${info.id} pref=$backendPref " +
                        "main=$mainCandidates vision=$visionCandidates image=${info.supportsImage}",
                )

                var loadedEngine: Engine? = null
                var mainUsed: Backend? = null
                var visionUsed: Backend? = null
                var audioUsed: Backend? = null
                var lastError: Throwable? = null
                var triedFirstMainCandidate = false

                outer@ for (mb in mainCandidates) {
                    triedFirstMainCandidate = true
                    for (vb in visionCandidates) {
                        // Audio encoder follows the same backend as vision when
                        // the model bundle includes one. CPU is universally
                        // compatible; some devices' GPU drivers misbehave on
                        // the audio encoder graph the same way they do for
                        // vision, so we mirror the chain.
                        val ab: Backend? = if (info.supportsAudio) {
                            if (vb is Backend.GPU) Backend.GPU()
                            else if (vb is Backend.CPU) Backend.CPU()
                            else Backend.CPU()
                        } else null
                        val cfg = EngineConfig(
                            modelPath = file.absolutePath,
                            backend = mb,
                            visionBackend = vb,
                            audioBackend = ab,
                            maxNumTokens = info.maxTokens,
                        )
                        val tryEngine = Engine(cfg)
                        try {
                            Log.i(TAG, "Engine.initialize() main=$mb vision=$vb audio=$ab")
                            tryEngine.initialize()
                            loadedEngine = tryEngine
                            mainUsed = mb
                            visionUsed = vb
                            audioUsed = ab
                            Log.i(TAG, "Engine ready: main=$mb vision=$vb audio=$ab")
                            break@outer
                        } catch (t: Throwable) {
                            Log.w(TAG, "init failed main=$mb vision=$vb audio=$ab: ${t.message}")
                            lastError = t
                            runCatching { tryEngine.close() }
                            // If audio init failed, retry without audio before
                            // moving to next backend combo.
                            if (ab != null) {
                                val cfgNoAudio = EngineConfig(
                                    modelPath = file.absolutePath,
                                    backend = mb,
                                    visionBackend = vb,
                                    audioBackend = null,
                                    maxNumTokens = info.maxTokens,
                                )
                                val retryNoAudio = Engine(cfgNoAudio)
                                try {
                                    retryNoAudio.initialize()
                                    loadedEngine = retryNoAudio
                                    mainUsed = mb
                                    visionUsed = vb
                                    audioUsed = null
                                    Log.w(TAG, "Engine ready WITHOUT audio: main=$mb vision=$vb")
                                    break@outer
                                } catch (t2: Throwable) {
                                    Log.w(TAG, "no-audio retry also failed: ${t2.message}")
                                    runCatching { retryNoAudio.close() }
                                }
                            }
                        }
                    }
                }

                if (loadedEngine == null) {
                    // Last-resort retry on a clean cache (driver state can corrupt across runs).
                    Log.w(TAG, "All initial attempts failed; purging caches and retrying CPU-only")
                    purgeStaleCaches(file)
                    val retryCfg = EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        visionBackend = if (info.supportsImage) Backend.CPU() else null,
                        audioBackend = null,
                        maxNumTokens = info.maxTokens,
                    )
                    val retryEngine = Engine(retryCfg)
                    try {
                        retryEngine.initialize()
                        loadedEngine = retryEngine
                        mainUsed = Backend.CPU()
                        visionUsed = if (info.supportsImage) Backend.CPU() else null
                        Log.i(TAG, "Engine ready after cache purge (CPU-only)")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Cache-purge retry also failed: ${t.message}")
                        runCatching { retryEngine.close() }
                        throw lastError ?: t
                    }
                }

                engine = loadedEngine
                loadedModelId = info.id
                visionEnabled = visionUsed != null
                audioEnabled = audioUsed != null
                _status.value = EngineStatus(
                    state = EngineState.READY,
                    activeModel = info,
                    requestedBackend = backendPref,
                    activeBackend = classifyActiveBackend(mainUsed, visionUsed, info.supportsImage),
                    backendFellBack = didBackendFallBack(backendPref, mainUsed, visionUsed),
                )
                info
            }
        }.onFailure {
            Log.e(TAG, "Model load failed", it)
            _status.value = EngineStatus(EngineState.ERROR, info, it.message)
        }
    }

    /**
     * Build (main, vision) backend candidate lists per user preference.
     *
     * Industry-best-practice rationale:
     *  - AUTO: try GPU first (faster), fall through to CPU on driver issues.
     *    Most consumer apps default here.
     *  - GPU: same chain as AUTO but UI flags the fallback so user knows GPU
     *    isn't actually engaged. We still allow CPU fallback rather than
     *    erroring out, because hard-failing the user when their GPU pick
     *    doesn't work would be worse UX than "silently degraded".
     *  - CPU: strict CPU-only. No GPU attempts at all. This is the
     *    "reliable / battery-friendly" mode and must NOT touch GPU under any
     *    circumstance, including for the vision encoder.
     */
    private fun backendChain(
        pref: InferenceBackend,
        info: ModelInfo,
    ): Pair<List<Backend>, List<Backend?>> {
        val gpuFirst: List<Backend> = listOf(Backend.GPU(), Backend.CPU())
        val cpuOnly: List<Backend> = listOf(Backend.CPU())
        // 视觉编码器 AUTO 档改为 CPU 优先：部分设备的 GPU 驱动上编码器能初始化
        // 但推理产出异常向量，模型表现为"看不到图"（用户真机实测）。编码是
        // 单次前缀代价，CPU 慢一点但跨设备稳定；主模型(decode)不受影响仍 GPU 优先。
        // 音频编码器随视觉走同一后端（见上方 ab 的取值逻辑）。
        val visionCpuFirst: List<Backend?> =
            if (info.supportsImage) listOf(Backend.CPU(), Backend.GPU(), null)
            else listOf(null)
        val visionGpuFirst: List<Backend?> =
            if (info.supportsImage) listOf(Backend.GPU(), Backend.CPU(), null)
            else listOf(null)
        val visionCpuOnly: List<Backend?> =
            if (info.supportsImage) listOf(Backend.CPU(), null)
            else listOf(null)
        return when (pref) {
            InferenceBackend.AUTO -> gpuFirst to visionCpuFirst
            // 用户手选 GPU：尊重选择仍让视觉先试 GPU（backendFellBack 会提示降级）。
            InferenceBackend.GPU -> gpuFirst to visionGpuFirst
            InferenceBackend.CPU -> cpuOnly to visionCpuOnly
        }
    }

    private fun classifyActiveBackend(
        main: Backend?,
        vision: Backend?,
        supportsImage: Boolean,
    ): ActiveBackend {
        val mainGpu = main is Backend.GPU
        val visionGpu = vision is Backend.GPU
        return when {
            main == null -> ActiveBackend.UNKNOWN
            !supportsImage -> if (mainGpu) ActiveBackend.GPU else ActiveBackend.CPU
            mainGpu && visionGpu -> ActiveBackend.GPU
            !mainGpu && !visionGpu -> ActiveBackend.CPU
            else -> ActiveBackend.MIXED
        }
    }

    private fun didBackendFallBack(
        pref: InferenceBackend,
        main: Backend?,
        vision: Backend?,
    ): Boolean = when (pref) {
        InferenceBackend.GPU -> main !is Backend.GPU || (vision != null && vision !is Backend.GPU)
        InferenceBackend.AUTO -> false // AUTO never "falls back" — it picks freely.
        InferenceBackend.CPU -> false  // CPU is by-design CPU.
    }

    /**
     * Stream tokens for plain text (or single-image multimodal) generation.
     *
     * The LiteRT-LM `Conversation` is single-tenant. We acquire [genMutex] for
     * the lifetime of the flow; cancellation cancels in-flight generation via
     * `Conversation.cancelProcess()`.
     *
     * Each call resets the conversation history (we model translate / chat
     * turns ourselves at the ViewModel layer).
     */
    fun generateStream(
        prompt: String,
        includeImage: Bitmap? = null,
        includeAudioWav: ByteArray? = null,
        /** 采样温度：对话 0.7；翻译/转写等确定性任务给低温（如 0.1）防自由发挥。 */
        temperature: Float = 0.7f,
    ): Flow<String> = callbackFlow {
        genMutex.lock()
        var holdsLock = true
        var conv: Conversation? = null
        // Guards the producer/consumer races that can SIGSEGV the
        // single-tenant LiteRT-LM Conversation native object.
        //
        // The classic crash is: user taps Stop → awaitClose runs on the
        // coroutine thread → we close the native Conversation → a still-running
        // LiteRT inference worker fires onMessage / onError on its own thread
        // → use-after-free → SIGSEGV.
        //
        // Fix: never close() the native Conversation from the coroutine. We
        // only call cancelProcess() to signal the worker to stop, then we
        // wait (bounded) for a terminal callback (onDone / onError). Once the
        // worker has drained, close() is safe. If the wait times out we leak
        // this Conversation — acceptable, GC + finalizer will reap it.
        val terminal = java.util.concurrent.CountDownLatch(1)
        val torndown = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val activeEngine = engine ?: error("Engine not loaded")
            if (includeImage != null && !visionEnabled) {
                throw IllegalStateException(
                    "当前模型未启用图像支持，请到\"模型\"页选择支持图像的模型。",
                )
            }
            if (includeAudioWav != null && !audioEnabled) {
                throw IllegalStateException(
                    "当前模型未启用语音识别能力。请重新加载模型后重试。",
                )
            }
            // Fresh conversation per call so we don't bleed history between turns.
            conv = activeEngine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature.toDouble()),
                )
            )

            val parts = mutableListOf<Content>()
            includeImage?.let { bmp -> parts.add(Content.ImageBytes(bmp.toPngBytes())) }
            includeAudioWav?.let { wav -> parts.add(Content.AudioBytes(wav)) }
            if (prompt.isNotEmpty()) parts.add(Content.Text(prompt))

            // Track clean (post stop-token-trim) emitted length for monotonic deltas.
            var emittedCleanLen = 0
            val emitted = StringBuilder()
            var stopped = false
            var msgCount = 0
            val tStart = System.currentTimeMillis()

            conv.sendMessageAsync(
                Contents.of(parts),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (stopped) return
                        val raw = message.toString()
                        msgCount++
                        // Diagnostic: confirm whether Message.toString() returns
                        // delta or full cumulative text. Some LiteRT-LM builds
                        // do one, some the other; if it's cumulative our
                        // emitted.append() would O(N²) and look like "streams
                        // briefly then stalls".
                        if (msgCount <= 3 || msgCount % 20 == 0) {
                            Log.d(
                                TAG,
                                "onMessage #$msgCount len=${raw.length} " +
                                    "emittedLen=${emitted.length} t+${System.currentTimeMillis() - tStart}ms",
                            )
                        }
                        // Heuristic: if the new message starts with the entire
                        // previously-emitted text, treat it as cumulative and
                        // replace instead of append. This makes us robust to
                        // either streaming convention.
                        if (emitted.isNotEmpty() && raw.length >= emitted.length &&
                            raw.startsWith(emitted.toString())
                        ) {
                            emitted.setLength(0)
                            emitted.append(raw)
                        } else {
                            emitted.append(raw)
                        }
                        val cleanFull = PromptTemplates.trimAtStop(emitted.toString())
                        val stopHit = cleanFull.length < emitted.length
                        if (cleanFull.length > emittedCleanLen) {
                            val delta = cleanFull.substring(emittedCleanLen)
                            emittedCleanLen = cleanFull.length
                            // CRITICAL: callbackFlow's default buffer is
                            // RENDEZVOUS (capacity = 0). If the UI thread is
                            // even slightly behind (Compose re-rendering a
                            // growing Text node), trySend returns false and
                            // the token is silently DROPPED — the user sees
                            // "streams a bit then dies, then result appears
                            // at the very end". We retry briefly under back-
                            // pressure (acceptable: this is the inference
                            // worker thread, blocking it slows generation
                            // which is the desired flow-control behavior).
                            var sent = trySend(delta).isSuccess
                            var attempt = 0
                            while (!sent && attempt < 50 && !stopped) {
                                Thread.sleep(2)
                                sent = trySend(delta).isSuccess
                                attempt++
                            }
                            if (!sent) {
                                Log.w(TAG, "Dropped delta after backpressure retries (#$msgCount)")
                            }
                        }
                        if (stopHit) {
                            stopped = true
                            // Don't tear down here — just close the flow and let
                            // awaitClose handle native cleanup once. Calling
                            // cancelProcess() from inside the JNI callback while
                            // the native thread is still mid-emit is racy.
                            close()
                        }
                    }

                    override fun onDone() {
                        Log.d(TAG, "onDone after ${System.currentTimeMillis() - tStart}ms, $msgCount msgs")
                        terminal.countDown()
                        if (!stopped) close()
                    }

                    override fun onError(throwable: Throwable) {
                        terminal.countDown()
                        if (throwable is CancellationException) {
                            close()
                        } else {
                            Log.e(TAG, "Inference error", throwable)
                            close(translateRuntimeError(throwable, hasImage = includeImage != null))
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            terminal.countDown()
            close(translateRuntimeError(t, hasImage = includeImage != null))
        }
        awaitClose {
            // Idempotent native tear-down. Order:
            //  1. Tell the worker to stop.
            //  2. Wait ≤ 2s for terminal callback (worker drains).
            //  3. Close the native handle (now safe).
            if (torndown.compareAndSet(false, true)) {
                val c = conv
                if (c != null) {
                    runCatching { c.cancelProcess() }
                    runCatching { terminal.await(2, java.util.concurrent.TimeUnit.SECONDS) }
                    runCatching { c.close() }
                }
            }
            if (holdsLock) {
                holdsLock = false
                runCatching { genMutex.unlock() }
            }
        }
    }.buffer(64)

    suspend fun unload() = mutex.withLock {
        runCatching { engine?.close() }
        engine = null
        loadedModelId = null
        visionEnabled = false
        audioEnabled = false
        _status.value = EngineStatus()
    }

    private fun Bitmap.toPngBytes(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /**
     * Wipes XNNPack weight caches and MLDrift program caches that sit next to
     * the .litertlm file. They get re-built on the next initialize(). We do
     * this when initialize() keeps failing because force-stops + backend
     * switches can corrupt the cache, manifesting as
     * INTERNAL: ERROR at llm_litert_compiled_model_executor.cc.
     */
    private fun purgeStaleCaches(modelFile: java.io.File) {
        val dir = modelFile.parentFile ?: return
        val base = modelFile.name
        val gone = dir.listFiles { f ->
            val n = f.name
            n.startsWith(base) && (
                n.contains(".xnnpack_cache") ||
                n.contains("_mldrift_program_cache") ||
                n.endsWith(".bin")
            ) && n != base
        }?.also { files ->
            files.forEach { runCatching { it.delete() } }
        }
        Log.i(TAG, "purgeStaleCaches: deleted ${gone?.size ?: 0} cache files near $base")
    }

}

/**
 * Map raw LiteRT-LM C++ errors into user-friendly Kotlin exceptions, e.g. when
 * the active model wasn't built with image support, or vision backend init fails.
 */
private fun translateRuntimeError(t: Throwable, hasImage: Boolean): Throwable {
    val msg = (t.message ?: "").lowercase()
    if (hasImage && (
        msg.contains("vision encoder") ||
        msg.contains("image modality") ||
        msg.contains("vision backend")
        )
    ) {
        return IllegalStateException(
            "当前模型不支持图像识别，请到\"模型\"页选择支持图像的模型。",
            t,
        )
    }
    return t
}
