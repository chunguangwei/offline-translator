package com.offlinetranslator.app.feature.models.download

import com.offlinetranslator.app.core.data.AppPreferencesRepository
import com.offlinetranslator.app.core.data.ModelSource
import com.offlinetranslator.app.core.data.model.ModelInfo
import com.offlinetranslator.app.core.data.model.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadProgress {
    data class Connecting(val url: String) : DownloadProgress()
    data class Progress(val downloaded: Long, val total: Long) : DownloadProgress()
    data object Completed : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
}

@Singleton
class ModelDownloader @Inject constructor(
    private val client: OkHttpClient,
    private val storage: ModelStorage,
    private val prefs: AppPreferencesRepository,
) {
    fun download(info: ModelInfo): Flow<DownloadProgress> = callbackFlow {
        val pref = prefs.flow.first()
        val urls = buildUrlList(info, pref.modelSource, pref.customMirrorBase)
        if (urls.isEmpty()) {
            trySend(DownloadProgress.Failed(
                when (pref.modelSource) {
                    ModelSource.LOCAL_ONLY -> "已设为「仅本地」模式，请改为其它模型源后再下载。"
                    ModelSource.CUSTOM -> "未配置自定义下载地址，请前往「设置」中填写。"
                    else -> "无可用下载源"
                }
            ))
            close()
            return@callbackFlow
        }

        val partFile = storage.partFileFor(info)
        val finalFile = storage.fileFor(info)
        if (finalFile.exists() && finalFile.length() >= info.sizeBytes * 0.99) {
            trySend(DownloadProgress.Completed)
            close()
            return@callbackFlow
        }
        partFile.parentFile?.mkdirs()

        var lastError: String? = null
        for (url in urls) {
            trySend(DownloadProgress.Connecting(url))
            val attempt = runCatching {
                withContext(Dispatchers.IO) { fetch(url, info, partFile) { downloaded, total ->
                    trySend(DownloadProgress.Progress(downloaded, total))
                } }
            }
            if (attempt.isSuccess) {
                if (partFile.renameTo(finalFile)) {
                    storage.bumpRefresh()
                    trySend(DownloadProgress.Completed)
                    close()
                    return@callbackFlow
                } else {
                    lastError = "Rename failed"
                }
            } else {
                lastError = attempt.exceptionOrNull()?.message
            }
        }
        trySend(DownloadProgress.Failed(lastError ?: "Unknown error"))
        close()
        awaitClose { /* nothing extra */ }
    }

    /** Resolve the ordered list of candidate URLs for the current source preference. */
    private fun buildUrlList(
        info: ModelInfo,
        src: ModelSource,
        customBase: String,
    ): List<String> = when (src) {
        // 多源互为兜底：首选源失败时自动顺延（download() 的 for 循环逐个重试）。
        //  HF_MIRROR(默认"国内优先") → ModelScope(阿里云 OSS，国内最快) → hf-mirror → 官方
        //  HF_DIRECT → 官方 → ModelScope → hf-mirror
        // 注：hf-mirror 对 HF Xet 仓库只 308 跳回被墙的 huggingface.co（无加速效果，
        // 用户实测"下载太慢"的根因）；ModelScope 直连阿里云 CDN，ImagePilot 实测快。
        ModelSource.HF_DIRECT -> listOf(info.urlHf, info.urlModelScope, info.urlMirror).distinct()
        ModelSource.HF_MIRROR -> listOf(info.urlModelScope, info.urlMirror, info.urlHf).distinct()
        // CUSTOM uses ONLY the user-supplied base URL — no silent fallback.
        ModelSource.CUSTOM -> {
            val base = customBase.trim()
            if (base.isBlank()) emptyList() else {
                val customUrl = if (base.endsWith("/")) base + info.fileName else "$base/${info.fileName}"
                listOf(customUrl)
            }
        }
        ModelSource.LOCAL_ONLY -> emptyList()
    }

    private fun fetch(
        url: String,
        info: ModelInfo,
        partFile: java.io.File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val existing = if (partFile.exists()) partFile.length() else 0L
        val req = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
            header("User-Agent", "OfflineTranslator/1.0")
        }.build()

        val resp: Response = client.newCall(req).execute()
        if (!resp.isSuccessful && resp.code != 206 && resp.code != 200) {
            resp.close()
            throw IOException("HTTP ${resp.code}")
        }
        val body = resp.body ?: throw IOException("Empty body")
        val totalLen = (body.contentLength().takeIf { it > 0 } ?: info.sizeBytes) +
            (if (resp.code == 206) existing else 0L)

        body.byteStream().use { input ->
            RandomAccessFile(partFile, "rw").use { raf ->
                if (resp.code == 206) raf.seek(existing) else raf.setLength(0)
                val buf = ByteArray(64 * 1024)
                var written = if (resp.code == 206) existing else 0L
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    raf.write(buf, 0, n)
                    written += n
                    onProgress(written, totalLen)
                }
            }
        }
    }
}
