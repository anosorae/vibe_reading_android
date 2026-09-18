package com.vibereading.app.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CrashHandler 单测：真实落盘路径——崩溃文件内容（设备信息 + 堆栈）、
 * CrashMark 置位/消费语义、向默认处理器的委托、过期崩溃文件清理。
 * absorb 分支（无害异常 Looper.loop 续命）依赖运行时 Looper 状态，不在 JVM 单测覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class CrashHandlerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var handler: CrashHandler
    private var originalDefault: Thread.UncaughtExceptionHandler? = null
    private val delegated = mutableListOf<Throwable>()

    @Before
    fun setUp() {
        CrashLogFiles.clear(context)
        CrashMark.setCrashed(context, false)
        // 先装桩默认处理器，CrashHandler 构造时捕获它作为委托目标，
        // 避免 Robolectric 环境下真默认处理器向 stderr 喷堆栈
        originalDefault = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> delegated.add(e) }
        handler = CrashHandler(context)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalDefault)
        CrashLogFiles.clear(context)
        CrashMark.setCrashed(context, false)
    }

    @Test
    fun `uncaught exception writes crash file with device info and stack, marks and delegates`() {
        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        val files = CrashLogFiles.list(context)
        assertEquals(1, files.size)
        val text = CrashLogFiles.read(files.first())
        assertTrue(text.contains("MANUFACTURER="))
        assertTrue(text.contains("versionName="))
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("boom"))

        assertTrue(CrashMark.consumeCrashed(context))
        assertEquals(1, delegated.size)
    }

    @Test
    fun `delegation carries the original exception exactly once`() {
        val ex = IllegalArgumentException("参数非法")
        handler.uncaughtException(Thread.currentThread(), ex)
        assertEquals(1, delegated.size)
        assertEquals(ex, delegated.single())
    }

    @Test
    fun `crash mark is write-once-consume-once`() {
        assertFalse(CrashMark.consumeCrashed(context))

        CrashMark.setCrashed(context, true)
        assertTrue(CrashMark.consumeCrashed(context))
        // 消费即清除：第二次读取不再报告崩溃
        assertFalse(CrashMark.consumeCrashed(context))
    }

    @Test
    fun `expired crash files are pruned on next crash`() {
        val folder = File(context.externalCacheDir, "crash").apply { mkdirs() }
        val stale = File(folder, "crash-stale.log").apply {
            writeText("旧崩溃")
            setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        }

        handler.uncaughtException(Thread.currentThread(), RuntimeException("新的崩溃"))

        assertFalse(stale.exists())
        val names = CrashLogFiles.list(context).map { it.name }
        assertEquals(1, names.size)
        assertTrue(names.single().startsWith("crash-"))
    }
}
