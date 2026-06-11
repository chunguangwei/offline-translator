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
