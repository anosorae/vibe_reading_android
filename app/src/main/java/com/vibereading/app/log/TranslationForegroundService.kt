package com.vibereading.app.log

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 翻译前台服务：翻译进行期间保持进程前台状态，避免按 Home 挂起时
 * Android 销毁 TCP socket（日志确认 `InetDiagMessage: Destroyed live tcp sockets`）。
 *
 * 服务本身不运行翻译逻辑（翻译在 `TranslationCoordinator` 的 appScope 中进行），
 * 只持有前台通知 + partial wake lock，让网络流式连接在后台继续存活。
 * 参照 legado `BaseService` / `DownloadService` 的 dataSync 前台服务模式。
 */
class TranslationForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        acquireWakeLock()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 必须指定 foregroundServiceType
                androidx.core.app.ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // 前台通知创建失败必须 stopSelf，否则会 ANR/崩溃
            AppLog.put("前台服务通知创建失败", e)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("译读")
            .setContentText("正在翻译…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return builder.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibereading:translation").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "translation"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 分钟兜底，防止泄漏

        /** 注册通知渠道，在 Application.onCreate 调用一次。 */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "翻译",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        /** 翻译开始时启动前台服务。 */
        fun start(context: Context) {
            val intent = Intent(context, TranslationForegroundService::class.java)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // 后台启动受限时退化为普通 startService；最坏情况服务无法前台化，
                // 但 appScope 仍在运行，前台返回后可继续。
                AppLog.put("startForegroundService 受限，退化为 startService", e)
                runCatching { context.startService(intent) }
            }
        }

        /** 翻译结束/失败/取消时停止前台服务。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TranslationForegroundService::class.java)) }
        }
    }
}
