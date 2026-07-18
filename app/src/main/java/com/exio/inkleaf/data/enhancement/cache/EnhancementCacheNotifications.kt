package com.exio.inkleaf.data.enhancement.cache

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.exio.inkleaf.MainActivity
import com.exio.inkleaf.R
import com.exio.inkleaf.data.db.EnhancementCacheTaskEntity
import java.util.concurrent.atomic.AtomicBoolean

object EnhancementCacheNotifications {
    const val ACTION_PAUSE = "com.exio.inkleaf.action.PAUSE_ENHANCEMENT_CACHE"
    const val ACTION_RESUME = "com.exio.inkleaf.action.RESUME_ENHANCEMENT_CACHE"
    const val ACTION_CANCEL = "com.exio.inkleaf.action.CANCEL_ENHANCEMENT_CACHE"
    const val EXTRA_TASK_ID = "task_id"

    private const val CHANNEL_ID = "enhancement_cache"
    private const val CHANNEL_NAME = "AI 增强缓存"

    fun foreground(
        context: Context,
        task: EnhancementCacheTaskEntity,
        comicTitle: String,
    ): ForegroundInfo {
        check(createChannel(context)) { "Unable to create enhancement cache channel" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("正在缓存《$comicTitle》")
            .setContentText(progressText(task))
            .setContentIntent(openAppIntent(context, task.id))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(task.totalPages, task.completedPages, false)
            .addAction(
                android.R.drawable.ic_media_pause,
                "暂停",
                actionIntent(context, task.id, ACTION_PAUSE),
            )
            .addAction(
                android.R.drawable.ic_delete,
                "取消",
                actionIntent(context, task.id, ACTION_CANCEL),
            )
            .build()
        return ForegroundInfo(
            notificationId(task.id),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    fun showPaused(context: Context, task: EnhancementCacheTaskEntity) {
        if (!createChannel(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("AI 增强缓存已暂停")
            .setContentText(progressText(task))
            .setContentIntent(openAppIntent(context, task.id))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(task.totalPages, task.completedPages, false)
            .addAction(
                android.R.drawable.ic_media_play,
                "继续",
                actionIntent(context, task.id, ACTION_RESUME),
            )
            .addAction(
                android.R.drawable.ic_delete,
                "取消",
                actionIntent(context, task.id, ACTION_CANCEL),
            )
            .build()
        postNotification(context, pausedNotificationId(task.id), notification)
    }

    fun showCompleted(
        context: Context,
        task: EnhancementCacheTaskEntity,
        comicTitle: String,
    ) {
        if (!createChannel(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("《$comicTitle》缓存完成")
            .setContentText("已缓存 ${task.totalPages} 页 AI 增强结果")
            .setContentIntent(openAppIntent(context, task.id))
            .setAutoCancel(true)
            .build()
        cancelPaused(context, task.id)
        postNotification(context, notificationId(task.id), notification)
    }

    fun showFailed(
        context: Context,
        task: EnhancementCacheTaskEntity,
        comicTitle: String,
        message: String,
    ) {
        if (!createChannel(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("《$comicTitle》缓存失败")
            .setContentText(message)
            .setContentIntent(openAppIntent(context, task.id))
            .setAutoCancel(true)
            .build()
        cancelPaused(context, task.id)
        postNotification(context, notificationId(task.id), notification)
    }

    fun cancel(context: Context, taskId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(taskId))
        cancelPaused(context, taskId)
    }

    private fun progressText(task: EnhancementCacheTaskEntity): String =
        "${task.completedPages} / ${task.totalPages} 页"

    private fun postNotification(
        context: Context,
        id: Int,
        notification: Notification,
    ): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        return try {
            manager.notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun createChannel(context: Context): Boolean {
        if (channelCreated.get()) return true
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            synchronized(channelCreated) {
                if (!channelCreated.get()) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_LOW,
                        )
                    )
                    channelCreated.set(true)
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun actionIntent(
        context: Context,
        taskId: String,
        action: String,
    ): PendingIntent {
        val intent = Intent(context, EnhancementCacheActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            notificationId("$taskId:$action"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            notificationId("$taskId:open"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(value: String): Int =
        20_000 + (value.hashCode() and 0x3fff)

    private fun pausedNotificationId(taskId: String): Int =
        40_000 + (taskId.hashCode() and 0x3fff)

    private fun cancelPaused(context: Context, taskId: String) {
        NotificationManagerCompat.from(context).cancel(pausedNotificationId(taskId))
    }

    private val channelCreated = AtomicBoolean(false)
}
