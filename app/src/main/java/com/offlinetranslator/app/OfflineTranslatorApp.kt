package com.offlinetranslator.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.offlinetranslator.app.core.i18n.LocaleManager
import com.offlinetranslator.app.feature.learn.SrsBackfill
import com.offlinetranslator.app.feature.models.download.ModelDownloadWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application class that initializes Hilt, WorkManager, and locale.
 */
@HiltAndroidApp
class OfflineTranslatorApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var srsBackfill: SrsBackfill

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LocaleManager.init(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { srsBackfill.runIfNeeded() }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.models_dl_notification_channel)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_MODEL_DOWNLOAD, name, importance).apply {
                description = getString(R.string.models_dl_notification_title)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_MODEL_DOWNLOAD = "model_download"
    }
}
