package com.vibereading.app

import android.app.Application
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.log.AppLog
import com.vibereading.app.log.CrashHandler
import com.vibereading.app.log.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VibeReadingApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /**
     * 应用级协程作用域：翻译等合法后台任务在此运行，生命周期独立于 Activity/ViewModel，
     * 按 Home 挂起或退出阅读器后仍可继续流式翻译直到完成。
     * 单个任务失败不会影响其他任务（SupervisorJob）。
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        // 崩溃处理器最先安装，确保后续初始化阶段的异常也能被捕获落盘
        CrashHandler(this)
        AppLog.init(this)
        LogUtils.init(this)
        LogUtils.logDeviceInfo()
        super.onCreate()
    }
}

