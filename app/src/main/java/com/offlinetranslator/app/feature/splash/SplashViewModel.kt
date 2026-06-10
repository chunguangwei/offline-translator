package com.offlinetranslator.app.feature.splash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject

/**
 * 远程启动图配置（仓库 `branding/splash/splash.json`）：
 *
 * ```json
 * { "enabled": true, "imageUrl": "https://…/splash.png" }
 * ```
 *
 * 改这个文件即可换启动图，**无需发版**。`enabled=false` 或拉取失败时
 * 回落到内置的眨眼 logo 动画，离线完全可用。
 */
@Serializable
data class SplashRemoteConfig(
    val enabled: Boolean = false,
    val imageUrl: String = "",
)

@HiltViewModel
class SplashViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    /** 已缓存的远程启动图（上一次启动时拉好的）。null = 用默认眨眼 logo。 */
    private val _remoteImage = MutableStateFlow<Bitmap?>(null)
    val remoteImage = _remoteImage.asStateFlow()

    private val dir get() = File(context.filesDir, "splash")
    private val imageFile get() = File(dir, "splash_image")

    /** 记录缓存图对应的 imageUrl，URL 没变就不重复下载。 */
    private val urlFile get() = File(dir, "splash_image.url")

    // jsDelivr CDN 国内可达性好，优先；raw.githubusercontent 兜底。
    // 注意 jsDelivr 对 @main 有 ~12h 缓存，换图后最迟半天生效。
    private val configUrls = listOf(
        "https://cdn.jsdelivr.net/gh/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}@main/branding/splash/splash.json",
        "https://raw.githubusercontent.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/main/branding/splash/splash.json",
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // 1) 本次启动直接用上次的缓存 —— 启动页绝不等网络，离线照常。
            runCatching {
                if (imageFile.exists()) {
                    _remoteImage.value = BitmapFactory.decodeFile(imageFile.absolutePath)
                }
            }
            // 2) 后台静默刷新远程配置，为下次启动备好；任何失败都静默。
            runCatching { refreshRemote() }
        }
    }

    private fun refreshRemote() {
        val cfgText = configUrls.firstNotNullOfOrNull { url ->
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
        } ?: return
        val cfg = runCatching { json.decodeFromString<SplashRemoteConfig>(cfgText) }.getOrNull() ?: return

        if (!cfg.enabled || cfg.imageUrl.isBlank()) {
            // 远程关闭 → 清缓存，下次启动回默认眨眼 logo。
            imageFile.delete()
            urlFile.delete()
            return
        }
        val cachedUrl = urlFile.takeIf { it.exists() }?.readText()
        if (cachedUrl == cfg.imageUrl && imageFile.exists()) return // 已是最新

        client.newCall(Request.Builder().url(cfg.imageUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) return
            val bytes = resp.body?.bytes() ?: return
            // 防呆：解不出图的内容不落盘。
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            dir.mkdirs()
            val tmp = File(dir, "splash_image.tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(imageFile)) urlFile.writeText(cfg.imageUrl)
        }
    }
}
