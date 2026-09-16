package com.vibereading.app.log

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 前台服务公共样板：本工程两个 dataSync 前台服务
 * （[TranslationForegroundService] 与 `WebCompanionService`）共用的
 * 「提升为前台 + 注册通知渠道 + 启动服务」三处机械代码的唯一实现。
 *
 * 服务自身的通知内容、保活策略与失败恢复策略仍各自持有，不在此抽象。
 */

/**
 * 把服务提升为前台。Android 14+ 必须显式声明 `foregroundServiceType`。
 *
 * 返回 false 表示前台通知创建失败，调用方**必须** `stopSelf()`，否则
 * 服务停留在无通知状态会触发 ANR 或被系统判为崩溃。
 */
fun Service.startForegroundDataSync(
    notificationId: Int,
    notification: Notification,
    logMessage: String
): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceCompat.startForeground(
            this, notificationId, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    } else {
        startForeground(notificationId, notification)
    }
    true
} catch (e: Exception) {
    AppLog.put(logMessage, e)
    false
}

/**
 * 启动前台服务。后台启动受限（如 Android 12+ 的后台限制）时返回 false，
 * 由调用方决定降级策略——翻译服务退化为普通 startService，伴读服务回滚已置的运行标志。
 */
fun startForegroundServiceLogged(context: Context, intent: Intent, logMessage: String): Boolean =
    try {
        ContextCompat.startForegroundService(context, intent)
        true
    } catch (e: Exception) {
        AppLog.put(logMessage, e)
        false
    }

/** 注册低打扰通知渠道（无声、无振动、无呼吸灯、锁屏隐藏内容）。已在 Application.onCreate 调用一次。 */
fun createQuietNotificationChannel(context: Context, channelId: String, channelName: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
        enableLights(false)
        enableVibration(false)
        setSound(null, null)
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
}
