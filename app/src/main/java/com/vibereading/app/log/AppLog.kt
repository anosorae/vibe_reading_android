package com.vibereading.app.log

import android.widget.Toast

/**
 * 内存运行日志：最新在前、上限 100 条的环形缓冲。
 *
 * 每条记录同步镜像到 [LogUtils] 文件日志；[putNotSave] 跳过文件写入，
 * 供 [LogUtils] 自身出错时调用以避免递归。崩溃捕获见 [CrashHandler]。
 *
 * 参照 legado `AppLog` 设计，去掉 splitties 与 recordLog 开关，默认全量记录。
 */
object AppLog {

    private const val MAX_SIZE = 100

    private val mLogs = arrayListOf<Triple<Long, String, Throwable?>>()

    /** 只读快照，newest-first。 */
    val logs: List<Triple<Long, String, Throwable?>> get() = mLogs.toList()

    fun init(context: android.content.Context) {
        LogContext.init(context)
    }

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) toastOnUi(message)
        if (mLogs.size > MAX_SIZE) mLogs.removeLastOrNull()
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (com.vibereading.app.BuildConfig.DEBUG) {
            val caller = Thread.currentThread().stackTrace.elementAtOrNull(3)?.className ?: "AppLog"
            android.util.Log.e(caller, message, throwable)
        }
    }

    /** 不写入文件日志，仅记录到内存，避免 [LogUtils] 出错时递归。 */
    @Synchronized
    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) toastOnUi(message)
        if (mLogs.size > MAX_SIZE) mLogs.removeLastOrNull()
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (com.vibereading.app.BuildConfig.DEBUG) {
            val caller = Thread.currentThread().stackTrace.elementAtOrNull(3)?.className ?: "AppLog"
            android.util.Log.e(caller, message, throwable)
        }
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
    }

    private fun toastOnUi(message: String) {
        runCatching { Toast.makeText(LogContext.get(), message, Toast.LENGTH_LONG).show() }
    }
}
