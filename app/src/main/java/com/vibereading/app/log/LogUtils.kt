package com.vibereading.app.log

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import com.vibereading.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * 文件日志：基于 `java.util.logging`，单文件 [AsyncFileHandler] 异步写入。
 *
 * 落盘位置 `<externalCacheDir>/logs/appLog-<timestamp>.txt`，每次进程启动一个新文件；
 * 初始化时后台清理 7 天前的旧日志和 `.lck` 锁文件。供 [AppLog] 镜像写入。
 * 参照 legado `LogUtils`，去掉 recordLog 开关，默认全量写入。
 */
@Suppress("unused")
object LogUtils {

    const val TIME_PATTERN = "yy-MM-dd HH:mm:ss.SSS"
    val logTimeFormat: SimpleDateFormat by lazy { SimpleDateFormat(TIME_PATTERN) }

    private const val LOG_FOLDER = "logs"
    private const val EXPIRE_DAYS = 7L

    val logger: Logger by lazy { Logger.getLogger("VibeReading") }

    private var fileHandler: FileHandler? = null

    fun init(context: Context) {
        fileHandler = createFileHandler(context)?.also { logger.addHandler(it) }
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        logger.log(Level.INFO, "$tag $msg")
    }

    /** 惰性求值，文件日志未启用时跳过字符串拼接开销。 */
    inline fun d(tag: String, lazyMsg: () -> String) {
        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "$tag ${lazyMsg()}")
        }
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        logger.log(Level.WARNING, "$tag $msg")
    }

    private fun createFileHandler(context: Context): FileHandler? {
        return try {
            val root = context.externalCacheDir ?: return null
            val logFolder = File(root, LOG_FOLDER).apply { mkdirs() }
            logExecutor.execute { cleanupExpired(logFolder) }
            val date = getCurrentDateStr(TIME_PATTERN).replace(" ", "_").replace(":", "-")
            val logPath = File(logFolder, "appLog-$date.txt").absolutePath
            AsyncFileHandler(logPath).apply {
                formatter = object : java.util.logging.Formatter() {
                    override fun format(record: LogRecord): String =
                        getCurrentDateStr(TIME_PATTERN) + ": " + record.message + "\n"
                }
                level = Level.INFO
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.putNotSave("创建 fileHandler 出错\n${e}", e)
            null
        }
    }

    private fun cleanupExpired(logFolder: File) {
        val expiredTime = System.currentTimeMillis() - EXPIRE_DAYS * 24 * 60 * 60 * 1000L
        logFolder.listFiles()?.forEach {
            if (it.lastModified() < expiredTime || it.name.endsWith(".lck")) it.delete()
        }
    }

    /** 写入设备/应用信息，作为每次启动的首条日志，便于排查环境相关 bug。 */
    fun logDeviceInfo() {
        d("DeviceInfo") {
            buildString {
                runCatching {
                    append("MANUFACTURER=").append(Build.MANUFACTURER).append("\n")
                    append("BRAND=").append(Build.BRAND).append("\n")
                    append("MODEL=").append(Build.MODEL).append("\n")
                    append("SDK_INT=").append(Build.VERSION.SDK_INT).append("\n")
                    append("RELEASE=").append(Build.VERSION.RELEASE).append("\n")
                    val userAgent = try {
                        WebSettings.getDefaultUserAgent(LogContext.get())
                    } catch (e: Throwable) {
                        e.toString()
                    }
                    append("WebViewUserAgent=").append(userAgent).append("\n")
                    append("packageName=").append(LogContext.get().packageName).append("\n")
                    append("heapSize=").append(Runtime.getRuntime().maxMemory()).append("\n")
                    append("versionName=").append(BuildConfig.VERSION_NAME).append("\n")
                    append("versionCode=").append(BuildConfig.VERSION_CODE).append("\n")
                }
            }
        }
    }

    @Synchronized
    fun getCurrentDateStr(pattern: String): String =
        SimpleDateFormat(pattern).format(Date())
}
