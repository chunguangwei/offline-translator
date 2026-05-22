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

    private val apiUrl =
        "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
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
                if (isNewer(latest, BuildConfig.VERSION_NAME)) {
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
        if (file.exists()) file.delete()
        trySend(DownloadStep.Progress(0, 0))

        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).build()
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
        outcome.fold(
            onSuccess = { trySend(DownloadStep.Completed(it)) },
            onFailure = {
                file.delete()
                trySend(DownloadStep.Failed(it.message ?: "下载失败"))
            },
        )
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

    /** Compare dotted/dashed version strings numerically (e.g. "1.0.10" > "1.0.9"). */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
