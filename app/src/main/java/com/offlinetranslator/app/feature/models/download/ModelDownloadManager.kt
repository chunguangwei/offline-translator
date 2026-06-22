package com.offlinetranslator.app.feature.models.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.offlinetranslator.app.core.data.model.ModelInfo
import com.offlinetranslator.app.feature.models.DownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级模型下载协调器（@Singleton，与进程同寿）。
 *
 * 为什么不用 WorkManager：国产 ROM（vivo OriginOS / MIUI 等）的后台限制会卡死
 * WorkManager 的 Job（CONSTRAINT_BACKGROUND_NOT_RESTRICTED）。改用
 * 「应用级协程跑下载 + 前台服务护住进程」对齐 iOS 后台 URLSession：
 *  - 下载协程挂在应用级 scope，不随某个 Activity/ViewModel 销毁而取消；
 *  - 有任一下载在跑时拉起 [ModelDownloadService]（dataSync 前台服务 + 常驻进度通知），
 *    熄屏/切后台时系统不冻结进程，下载得以继续；
 *  - 仍保留 [ModelDownloader] 的 HTTP Range 断点续传：极端情况下进程仍被杀，
 *    回来点「继续」即接着下，不重头来。
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: ModelDownloader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    @Synchronized
    fun start(info: ModelInfo) {
        if (jobs[info.id]?.isActive == true) return
        _downloads.update {
            it + (info.id to DownloadState(running = true, displayName = info.displayName))
        }
        ensureServiceRunning()
        jobs[info.id] = scope.launch {
            try {
                downloader.download(info).collect { p ->
                    when (p) {
                        is DownloadProgress.Connecting -> _downloads.update {
                            it + (info.id to (it[info.id] ?: DownloadState()).copy(
                                running = true, error = null, displayName = info.displayName,
                            ))
                        }
                        is DownloadProgress.Progress -> _downloads.update {
                            it + (info.id to DownloadState(
                                downloadedBytes = p.downloaded,
                                totalBytes = p.total,
                                running = true,
                                displayName = info.displayName,
                            ))
                        }
                        is DownloadProgress.Completed -> _downloads.update {
                            it + (info.id to DownloadState(
                                downloadedBytes = info.sizeBytes,
                                totalBytes = info.sizeBytes,
                                running = false,
                                displayName = info.displayName,
                            ))
                        }
                        is DownloadProgress.Failed -> _downloads.update {
                            it + (info.id to (it[info.id] ?: DownloadState()).copy(
                                running = false, error = p.error, displayName = info.displayName,
                            ))
                        }
                    }
                }
            } finally {
                synchronized(this@ModelDownloadManager) {
                    jobs.remove(info.id)
                    maybeStopService()
                }
            }
        }
    }

    @Synchronized
    fun cancel(info: ModelInfo) {
        jobs.remove(info.id)?.cancel()
        _downloads.update {
            it + (info.id to (it[info.id] ?: DownloadState()).copy(running = false))
        }
        maybeStopService()
    }

    /** 删除模型时清掉其下载状态（同时取消在途任务）。 */
    @Synchronized
    fun clear(modelId: String) {
        jobs.remove(modelId)?.cancel()
        _downloads.update { it - modelId }
        maybeStopService()
    }

    private fun anyRunning(): Boolean = _downloads.value.values.any { it.running }

    private fun ensureServiceRunning() {
        // start() 由前台 UI 点击触发，App 处于前台，允许拉起前台服务。
        ContextCompat.startForegroundService(
            context, Intent(context, ModelDownloadService::class.java)
        )
    }

    private fun maybeStopService() {
        if (!anyRunning()) {
            context.stopService(Intent(context, ModelDownloadService::class.java))
        }
    }
}
