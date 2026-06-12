package com.offlinetranslator.app.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.offlinetranslator.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-update via GitHub Releases (sideload / GMS-free distribution).
 *
 * Flow: query the repo's `releases/latest`, compare its tag against
 * [BuildConfig.VERSION_NAME], download the `.apk` asset to cache, then hand the
 * file to the system package installer through our FileProvider. Because every
 * release is signed with the *same* (debug) keystore via CI, the OS accepts the
 * download as an in-place upgrade of the installed app.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val repoPath = "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}"

    /**
     * 更新清单多源（CI 发版自动写 branding/update/latest.json）：
     * gh-proxy(国内可达且实时) → jsDelivr(CDN，@main 约 12h 缓存) → raw 直连。
     * api.github.com 只作最后兜底：未认证限流 60 次/小时/IP，部分网络直接 403
     * （用户真机实测；ImagePilot 同坑）。
     */
    private val manifestUrls = listOf(
        "https://gh-proxy.com/https://raw.githubusercontent.com/$repoPath/main/branding/update/latest.json",
        "https://cdn.jsdelivr.net/gh/$repoPath@main/branding/update/latest.json",
        "https://raw.githubusercontent.com/$repoPath/main/branding/update/latest.json",
    )

    private val apiUrl = "https://api.github.com/repos/$repoPath/releases/latest"

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        // 1) 静态清单多源，不挨 API 限流。
        for (u in manifestUrls) {
            val m = runCatching { fetchManifest(u) }.getOrNull() ?: continue
            return@withContext if (isNewerVersion(m.version, BuildConfig.VERSION_NAME)) {
                UpdateResult.Available(
                    version = m.version,
                    notes = m.notes.trim(),
                    apkUrl = m.apk,
                    sizeBytes = m.size,
                )
            } else {
                UpdateResult.UpToDate
            }
        }
        // 2) 兜底：GitHub API（清单源全不可达才走到这）。
        checkViaApi()
    }

    private fun fetchManifest(url: String): UpdateManifest? {
        val req = Request.Builder().url(url).header("User-Agent", "Yiren-App").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            return json.decodeFromString<UpdateManifest>(body)
        }
    }

    private fun checkViaApi(): UpdateResult {
        return runCatching {
            val req = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                // 部分 GitHub 边缘节点对缺显式 UA 的请求 403（ImagePilot 实测）。
                .header("User-Agent", "Yiren-App")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@use UpdateResult.UpToDate // no releases yet
                if (!resp.isSuccessful) return@use UpdateResult.Error("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use UpdateResult.Error("空响应")
                val release = json.decodeFromString<GithubRelease>(body)
                val latest = release.tagName.removePrefix("v").trim()
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: return@use UpdateResult.Error("Release 未附带 APK")
                if (isNewerVersion(latest, BuildConfig.VERSION_NAME)) {
                    UpdateResult.Available(
                        version = latest,
                        notes = (release.body ?: release.name).orEmpty().trim(),
                        apkUrl = apk.downloadUrl,
                        sizeBytes = apk.size,
                    )
                } else {
                    UpdateResult.UpToDate
                }
            }
        }.getOrElse { UpdateResult.Error(it.message ?: "网络错误") }
    }

    fun download(url: String): Flow<DownloadStep> = callbackFlow {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, "yiren-update.apk")
        trySend(DownloadStep.Progress(0, 0))

        // github.com 直链在部分网络不可达 → gh-proxy 加速候选优先、直连兜底
        // （ImagePilot 同款）。APK 升级安装受系统签名校验，代理无法篡改得逞。
        val candidates = buildList {
            if (url.startsWith("https://github.com/")) add("https://gh-proxy.com/$url")
            add(url)
        }

        var lastError: Throwable? = null
        var done = false
        for (candidate in candidates) {
            if (file.exists()) file.delete()
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(candidate).header("User-Agent", "Yiren-App").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val source = resp.body?.byteStream() ?: throw IOException("空响应体")
                        val total = resp.body?.contentLength() ?: -1L
                        RandomAccessFile(file, "rw").use { out ->
                            val buf = ByteArray(64 * 1024)
                            var downloaded = 0L
                            while (true) {
                                val n = source.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                trySend(DownloadStep.Progress(downloaded, total))
                            }
                        }
                    }
                    file
                }
            }
            if (outcome.isSuccess) {
                trySend(DownloadStep.Completed(file))
                done = true
                break
            }
            lastError = outcome.exceptionOrNull()
        }
        if (!done) {
            file.delete()
            trySend(DownloadStep.Failed(lastError?.message ?: "下载失败"))
        }
        close()
        awaitClose { }
    }

    /** API 26+ requires per-app "install unknown apps" consent before launching the installer. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Intent to the system "install unknown apps" toggle for this package. */
    fun installPermissionIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

}
