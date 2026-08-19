package com.vibereading.app.log

import android.content.Context
import java.io.File

/** 崩溃日志文件访问；列表 newest-first，支持删除单条和清空。 */
object CrashLogFiles {

    private const val CRASH_FOLDER = "crash"

    fun list(context: Context): List<File> {
        val folder = File(context.externalCacheDir, CRASH_FOLDER)
        return (folder.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && it.name.endsWith(".log") }
            .sortedByDescending { it.name }
    }

    fun read(file: File): String =
        runCatching { file.readText() }.getOrElse { "读取失败: ${it.message}" }

    fun delete(file: File): Boolean = file.delete()

    fun clear(context: Context) {
        File(context.externalCacheDir, CRASH_FOLDER).listFiles()?.forEach { it.delete() }
    }
}
