package com.offlinetranslator.app.feature.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Subset of the GitHub Releases API payload we care about.
 * See https://docs.github.com/rest/releases/releases#get-the-latest-release
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

/**
 * 仓库内静态更新清单 `branding/update/latest.json`（CI 每次发版自动写入）。
 * 检查更新首选它（gh-proxy/jsDelivr/raw 多源，国内可达），api.github.com
 * 仅作兜底——未认证限流 60 次/小时/IP + 部分网络直接 403。
 */
@Serializable
data class UpdateManifest(
    val version: String,
    val notes: String = "",
    val apk: String,
    val size: Long = 0,
)

/** 版本号数值比较（"1.0.10" > "1.0.9"；忽略非数字段）。抽出为顶层函数以便单测。 */
internal fun isNewerVersion(remote: String, local: String): Boolean {
    val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
    val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(r.size, l.size)) {
        val rv = r.getOrElse(i) { 0 }
        val lv = l.getOrElse(i) { 0 }
        if (rv != lv) return rv > lv
    }
    return false
}

/** Result of a "check for update" round-trip. */
sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    ) : UpdateResult
    data class Error(val message: String) : UpdateResult
}

/** Streaming progress of the APK download. */
sealed interface DownloadStep {
    data class Progress(val downloaded: Long, val total: Long) : DownloadStep
    data class Completed(val file: File) : DownloadStep
    data class Failed(val message: String) : DownloadStep
}
