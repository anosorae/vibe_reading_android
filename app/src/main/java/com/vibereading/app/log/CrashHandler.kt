package com.vibereading.app.log

import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.core.content.edit
import com.vibereading.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 全局未捕获异常处理器：把崩溃写入 `<externalCacheDir>/crash/crash-<time>.log`，
 * 设置 [CrashMark] 标志，让下次启动的 [com.vibereading.app.MainActivity] 提示用户查看。
 *
 * 与 legado `CrashHandler` 的差异：
 * - 去掉 SAF 备份目录（本应用无备份路径概念）与堆转储；
 * - 去掉 TTS/朗读停止、Toast、3 秒延迟等非通用逻辑，仅落盘后委托默认处理器结束进程。
 * 安装顺序在 `Application.onCreate` 最先，确保后续初始化阶段也能捕获。
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        if (shouldAbsorb(ex)) {
            // 已知的无害异常：记录后让 Looper 继续运行，避免进程被默认处理器杀掉
            AppLog.put("发生未捕获的异常\n${ex.localizedMessage}", ex)
            Looper.loop()
        } else {
            handleException(ex)
            defaultHandler?.uncaughtException(thread, ex)
        }
    }

    private fun shouldAbsorb(e: Throwable): Boolean =
        when {
            e::class.simpleName == "CannotDeliverBroadcastException" -> true
            e is SecurityException && e.message?.contains(
                "OBSERVE_GRANT_REVOKE_PERMISSIONS",
                ignoreCase = true,
            ) == true -> true

            else -> false
        }

    private fun handleException(ex: Throwable) {
        CrashMark.setCrashed(context, true)
        saveCrashInfoToFile(context, ex)
    }

    companion object {
        private const val CRASH_FOLDER = "crash"
        private const val EXPIRE_DAYS = 7L

        @Synchronized
        private fun saveCrashInfoToFile(context: Context, ex: Throwable) {
            val sb = StringBuilder()
            appendDeviceInfo(context, sb)
            val writer = StringWriter()
            val printWriter = PrintWriter(writer)
            ex.printStackTrace(printWriter)
            var cause: Throwable? = ex.cause
            while (cause != null) {
                cause.printStackTrace(printWriter)
                cause = cause.cause
            }
            printWriter.close()
            sb.append(writer.toString())

            val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")
            val time = format.format(Date())
            val timestamp = System.currentTimeMillis()
            val fileName = "crash-$time-$timestamp.log"
            runCatching {
                val root = context.externalCacheDir ?: return@runCatching
                val crashFolder = File(root, CRASH_FOLDER).apply { mkdirs() }
                val expireTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(EXPIRE_DAYS)
                crashFolder.listFiles()?.forEach {
                    if (it.lastModified() < expireTime) it.delete()
                }
                File(crashFolder, fileName).writeText(sb.toString())
            }
        }

        private fun appendDeviceInfo(context: Context, sb: StringBuilder) {
            runCatching {
                sb.append("MANUFACTURER=").append(Build.MANUFACTURER).append("\n")
                sb.append("BRAND=").append(Build.BRAND).append("\n")
                sb.append("MODEL=").append(Build.MODEL).append("\n")
                sb.append("SDK_INT=").append(Build.VERSION.SDK_INT).append("\n")
                sb.append("RELEASE=").append(Build.VERSION.RELEASE).append("\n")
                sb.append("packageName=").append(context.packageName).append("\n")
                sb.append("heapSize=").append(Runtime.getRuntime().maxMemory()).append("\n")
                sb.append("versionName=").append(BuildConfig.VERSION_NAME).append("\n")
                sb.append("versionCode=").append(BuildConfig.VERSION_CODE).append("\n")
            }
        }
    }
}

/** 崩溃标志位，存于独立 SharedPreferences，由 [CrashHandler] 写入、[com.vibereading.app.MainActivity] 读取后清除。 */
object CrashMark {
    private const val PREF_NAME = "vibe_crash"
    private const val KEY_APP_CRASH = "appCrash"

    fun setCrashed(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_APP_CRASH, value) }
    }

    fun consumeCrashed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val crashed = prefs.getBoolean(KEY_APP_CRASH, false)
        if (crashed) prefs.edit { putBoolean(KEY_APP_CRASH, false) }
        return crashed
    }
}
