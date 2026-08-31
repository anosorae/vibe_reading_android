package com.vibereading.app.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.vibereading.app.VibeReadingApp
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.log.AppLog
import com.vibereading.app.log.TranslationForegroundService
import com.vibereading.app.ui.reader.TranslationCoordinatorProvider
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Web 伴读前台服务（ADR-005）：托管内嵌 HTTP 服务器，让同一 WiFi 下的电脑浏览器
 * 阅读手机库中的书籍。参照 legado `WebService` 与本项目 `TranslationForegroundService`
 * 的 dataSync 前台服务模式：通知栏展示含 Token 的服务地址，持 WakeLock/WifiLock，
 * App 退后台/息屏后服务存活。
 *
 * Token 每次服务开启重新生成（进程重启后地址变化属预期，通知与设置页始终展示当前地址）。
 */
class WebCompanionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var server: CompanionServer? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Token 由 start() 经 Intent 传入（设置页在 start() 返回后即可展示地址）；
        // START_STICKY 重启等 intent=null 场景回退到内存值或重新生成。
        val token = intent?.getStringExtra(EXTRA_TOKEN) ?: currentToken ?: newToken()
        currentToken = token
        // START_STICKY 系统重启（intent=null）也会走到这里，保持 isRunning 与真实状态一致
        isRunning = true
        val started = startServer(token)
        startForegroundCompat(started)
        if (started) {
            acquireWakeLock()
            acquireWifiLock()
            registerNetworkCallback()
            publishUrl()
        } else {
            // 端口被占等情况：服务无法提供功能，自行结束避免留下空前台通知
            stopSelf()
        }
        return START_STICKY
    }

    private fun startServer(token: String): Boolean {
        if (server != null) return true
        val app = applicationContext as VibeReadingApp
        val api = CompanionApi(
            bookRepo = BookRepository(app.database.bookDao()),
            chapterRepo = ChapterRepository(app.database.chapterDao()),
            llmProfileRepo = LlmProfileRepository(app.database.llmProfileDao(), SettingsRepository(app)),
            coordinatorProvider = { TranslationCoordinatorProvider.get(app) }
        )
        return try {
            val s = CompanionServer(DEFAULT_PORT, token, api, assets)
            s.start(SERVER_START_TIMEOUT_MS)
            server = s
            currentToken = token
            AppLog.put("Web 伴读服务已启动，端口 $DEFAULT_PORT")
            true
        } catch (e: Exception) {
            AppLog.put("Web 伴读服务启动失败", e)
            false
        }
    }

    private fun startForegroundCompat(running: Boolean) {
        val notification = buildNotification(running)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                androidx.core.app.ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AppLog.put("伴读前台通知创建失败", e)
            stopSelf()
        }
    }

    private fun buildNotification(running: Boolean): Notification {
        val text = if (running) {
            currentUrl() ?: "http://<本机IP>:$DEFAULT_PORT"
        } else {
            "启动失败，请查看日志"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Web 伴读服务")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * legado 式网络切换跟随：不轮询，注册默认网络回调，WiFi/数据网络切换后
     * 本机 IP 变化时刷新内存地址并更新通知栏。回调默认在主线程执行，
     * 网卡枚举开销极小且仅在网络事件时触发。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshAddress()
            override fun onLost(network: Network) = refreshAddress()
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { AppLog.put("伴读服务注册网络回调失败", it) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
            .onFailure { AppLog.put("伴读服务注销网络回调失败", it) }
    }

    /** 地址变化时更新共享 StateFlow 与通知栏；不变则不动通知（setOnlyAlertOnce 已兜底）。 */
    private fun refreshAddress() {
        val newUrl = currentUrl()
        if (newUrl == urlFlow.value) return
        _urlFlow.value = newUrl
        if (newUrl != null) {
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(running = true))
            }.onFailure { AppLog.put("伴读通知地址刷新失败", it) }
        }
    }

    /** 把当前地址发布到共享 StateFlow（服务启动后调用一次；此后由网络回调驱动）。 */
    private fun publishUrl() {
        _urlFlow.value = currentUrl()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibereading:companion").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** WiFi 锁防止锁屏后系统关闭 WiFi；伴读场景手机息屏后 PC 仍需访问。 */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vibereading:companion").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        runCatching { server?.stop() }.onFailure { AppLog.put("伴读服务器停止异常", it) }
        server = null
        unregisterNetworkCallback()
        currentToken = null
        isRunning = false
        _urlFlow.value = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        AppLog.put("Web 伴读服务已停止")
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_PORT = 9700
        private const val CHANNEL_ID = "web_companion"
        private const val NOTIFICATION_ID = 1002
        private const val SERVER_START_TIMEOUT_MS = 5000
        private const val EXTRA_TOKEN = "extra_token"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 当前服务 Token（进程内存态，服务停止即失效）。 */
        @Volatile
        var currentToken: String? = null
            private set

        /**
         * 含 Token 的服务地址共享流；null = 服务未运行或尚未拿到局域网地址。
         * 由服务自身在网络切换时刷新（legado 式事件驱动），订阅方无需轮询。
         */
        private val _urlFlow = MutableStateFlow<String?>(null)
        val urlFlow: StateFlow<String?> = _urlFlow.asStateFlow()

        /** 含 Token 的服务地址；多网卡（如热点+WiFi）取首个站点本地地址。 */
        fun currentUrl(): String? {
            val token = currentToken ?: return null
            val ip = localIpAddresses().firstOrNull() ?: return null
            return "http://$ip:$DEFAULT_PORT/?token=$token"
        }

        /** 开启伴读服务（幂等；已运行时不重复启动，Token 不变）。 */
        fun start(context: Context) {
            if (isRunning) return
            isRunning = true
            // 先定 Token 再拉起服务：调用方（设置页）在 start() 返回后即可展示完整地址
            val token = currentToken ?: newToken()
            currentToken = token
            _urlFlow.value = currentUrl()
            val intent = Intent(context, WebCompanionService::class.java).putExtra(EXTRA_TOKEN, token)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                isRunning = false
                currentToken = null
                _urlFlow.value = null
                AppLog.put("伴读服务 startForegroundService 失败", e)
            }
        }

        /** 停止伴读服务（幂等）。 */
        fun stop(context: Context) {
            isRunning = false
            _urlFlow.value = null
            runCatching { context.stopService(Intent(context, WebCompanionService::class.java)) }
                .onFailure { AppLog.put("伴读服务 stopService 失败", it) }
        }

        /** 注册通知渠道，在 Application.onCreate 调用一次。 */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Web 伴读服务",
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

        /** 128-bit 随机 Token，URL 安全字符集。 */
        private fun newToken(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val random = SecureRandom()
            return buildString { repeat(24) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }

        /**
         * 请求系统「忽略电池优化」（ADR-005 局域网网页阅读）：手机锁屏并静置后 Android 进入
         * Doze，Doze 会忽略前后台服务持有的 wake lock 并冻结进程网络，导致内嵌服务器无法
         * 响应来自电脑的章节请求（表现为「锁屏后切换章节卡住」）。参照 legado `BaseService`
         * 在开启伴读服务时请求电池优化豁免。已在豁免名单内则静默跳过；请仅在设置页用户
         * 主动开启伴读时调用（前台场景），避免 App 后台自启时弹系统对话框。
         */
        fun requestBatteryOptimizationExemption(context: Context) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLog.put("请求忽略电池优化失败", e)
            }
        }

        /**
         * 枚举网卡上的站点本地 IPv4 地址（对齐 legado NetworkUtils 的做法）。
         * WiFi（wlan / swlan 前缀）网卡的地址排最前：WiFi+蜂窝同时在线时系统枚举顺序
         * 不保证 wlan0 在 rmnet 之前，而蜂窝运营商内网地址（10.x 段）同样是
         * site-local，直接取首个会展示无法局域网访问的蜂窝 IP。
         */
        fun localIpAddresses(): List<String> = runCatching {
            val result = mutableListOf<Pair<String, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList<String>()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addrs = ni.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is InetAddress && addr.hostAddress?.contains(':') == false &&
                        addr.isSiteLocalAddress
                    ) {
                        result.add(ni.name to addr.hostAddress!!)
                    }
                }
            }
            result.sortedBy { wifiInterfacePriority(it.first) }.map { it.second }
        }.onFailure { e -> AppLog.put("枚举局域网地址失败", e) }.getOrDefault(emptyList())

        /** 接口优先级：WiFi（wlan*，含部分机型热点 swlan*）为 0，其余（蜂窝 rmnet、VPN 等）为 1。 */
        internal fun wifiInterfacePriority(name: String): Int =
            if (name.startsWith("wlan") || name.startsWith("swlan")) 0 else 1
    }
}
