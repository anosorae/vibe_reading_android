package com.vibereading.app.log

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 进程级应用上下文与后台执行器，供日志各组件共享，避免每个文件各自持有 Context。 */
internal object LogContext {
    @Volatile
    private var app: Context? = null

    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun get(): Context =
        app ?: error("LogContext 未初始化，请在 Application.onCreate 中调用")
}

/** 单线程后台执行器：文件日志写入与过期文件清理，调用线程不阻塞在 I/O。 */
internal val logExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "vibe-log").apply { isDaemon = true }
}
