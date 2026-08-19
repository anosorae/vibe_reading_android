package com.vibereading.app

import android.app.Application
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.log.AppLog
import com.vibereading.app.log.CrashHandler
import com.vibereading.app.log.LogUtils

class VibeReadingApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        // 崩溃处理器最先安装，确保后续初始化阶段的异常也能被捕获落盘
        CrashHandler(this)
        AppLog.init(this)
        LogUtils.init(this)
        LogUtils.logDeviceInfo()
        super.onCreate()
    }
}
