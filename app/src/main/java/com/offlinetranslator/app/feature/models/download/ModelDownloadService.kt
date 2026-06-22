package com.offlinetranslator.app.feature.models.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.offlinetranslator.app.MainActivity
import com.offlinetranslator.app.OfflineTranslatorApp
import com.offlinetranslator.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 模型下载前台服务：只负责「护住进程 + 常驻进度通知」。
 *
 * 实际下载在 [ModelDownloadManager] 的应用级协程里跑；本服务观察其状态刷新通知。
 * 由 manager 在有下载时 startForegroundService 拉起、全部结束时 stopService。
 * START_NOT_STICKY：进程被杀后不带空 intent 重建（无下载状态的僵尸服务无意义；
 * 真要续传由用户在 App 内点「继续」触发，配合 Range 断点续传）。
 */
@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject lateinit var manager: ModelDownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5s 内 startForeground（startForegroundService 的硬性约束），先挂个初始通知。
        startForegroundCompat(notification(initialText()))
        if (observer == null) {
            observer = scope.launch {
                var lastPct = -1
                manager.downloads.collectLatest { map ->
                    val active = map.values.filter { it.running }
                    if (active.isEmpty()) { lastPct = -1; return@collectLatest }
                    val first = active.first()
                    val pct = if (first.totalBytes > 0)
                        ((first.downloadedBytes * 100) / first.totalBytes).toInt() else 0
                    // 按整数百分比节流，避免每 64KB 刷一次通知。
                    if (pct == lastPct && active.size == 1) return@collectLatest
                    lastPct = pct
                    val text = active.joinToString(" · ") { st ->
                        val p = if (st.totalBytes > 0)
                            ((st.downloadedBytes * 100) / st.totalBytes).toInt() else 0
                        if (st.displayName.isNotBlank()) "${st.displayName} $p%" else "$p%"
                    }
                    startForegroundCompat(notification(text, pct))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        super.onDestroy()
    }

    private fun initialText(): String =
        manager.downloads.value.values.firstOrNull { it.running }?.displayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.models_dl_notification_title)

    private fun startForegroundCompat(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun notification(text: String, progress: Int = 0): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, OfflineTranslatorApp.CHANNEL_MODEL_DOWNLOAD)
            .setContentTitle(getString(R.string.models_dl_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object { const val NOTIFICATION_ID = 1001 }
}
