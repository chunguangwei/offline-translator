package com.offlinetranslator.app.feature.learn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.offlinetranslator.app.MainActivity
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.db.ReviewCardDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 每日复习提醒 Worker（纯本地、不联网）。
 *
 * 到点查询今日到期卡片数；若 > 0 则弹一条本地通知，提醒用户复习。
 * 与「译人」离线/隐私承诺一致，不涉及任何网络请求。
 */
@HiltWorker
class ReviewReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reviewCardDao: ReviewCardDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val due = reviewCardDao.countOverdue(System.currentTimeMillis())
        if (due > 0) {
            notifyDue(applicationContext, due)
        }
        return Result.success()
    }

    private fun notifyDue(ctx: Context, due: Int) {
        // POST_NOTIFICATIONS 在 API 33+ 是运行时权限。未授予时直接返回——
        // 系统本就会拦截，不应崩溃。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(ctx)

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.srs_reminder_body, due))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.srs_reminder_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = ctx.getString(R.string.srs_reminder_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "review_reminder"
        const val NOTIFICATION_ID = 2001
        const val UNIQUE_WORK = "review_reminder"
    }
}

fun scheduleDaily(ctx: Context, hour: Int, minute: Int) {
    val now = java.util.Calendar.getInstance()
    val next = (now.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        if (before(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
    }
    val delay = next.timeInMillis - now.timeInMillis
    val req = androidx.work.PeriodicWorkRequestBuilder<ReviewReminderWorker>(24, java.util.concurrent.TimeUnit.HOURS)
        .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    androidx.work.WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        "review_reminder", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req)
}

fun cancelDaily(ctx: Context) =
    androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork("review_reminder")
