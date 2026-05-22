package com.offlinetranslator.app.feature.models.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.offlinetranslator.app.MainActivity
import com.offlinetranslator.app.OfflineTranslatorApp
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.model.ModelRegistry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloader: ModelDownloader,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val info = ModelRegistry.byId(modelId) ?: return Result.failure()

        // Don't crash if foreground promotion fails (e.g. permission denied);
        // just continue downloading in the background.
        runCatching { setForeground(makeForegroundInfo(info.displayName, 0)) }

        var lastResult: DownloadProgress = DownloadProgress.Failed("not_started")
        downloader.download(info).collect { p ->
            lastResult = p
            if (p is DownloadProgress.Progress && p.total > 0) {
                val pct = ((p.downloaded * 100f) / p.total).toInt()
                runCatching { setForeground(makeForegroundInfo(info.displayName, pct)) }
            }
        }
        return when (lastResult) {
            is DownloadProgress.Completed -> Result.success()
            else -> Result.retry()
        }
    }

    private fun makeForegroundInfo(name: String, progress: Int): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification =
            NotificationCompat.Builder(applicationContext, OfflineTranslatorApp.CHANNEL_MODEL_DOWNLOAD)
                .setContentTitle(applicationContext.getString(R.string.models_dl_notification_title))
                .setContentText(name)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setProgress(100, progress, progress == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        // Android 14+ requires explicit foregroundServiceType for short/long-running workers.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val NOTIFICATION_ID = 1001
        const val UNIQUE_NAME_PREFIX = "model_dl_"
    }
}
