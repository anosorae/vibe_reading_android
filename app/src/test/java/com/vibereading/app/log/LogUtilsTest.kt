package com.vibereading.app.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * LogUtils / LogContext 单测：文件日志异步落盘（排空 logExecutor 后可见）、
 * LogContext 首次初始化生效、后续 init 不替换。
 */
@RunWith(RobolectricTestRunner::class)
class LogUtilsTest {

    /** LogContext 是进程级单例，反射复位后才能确定性地验证初始化语义。 */
    @Test
    fun `LogContext errors before init and keeps the first context afterwards`() {
        val field = LogContext::class.java.getDeclaredField("app")
        field.isAccessible = true
        field.set(null, null)

        try {
            LogContext.get()
            fail("未初始化时 get() 应抛 IllegalStateException")
        } catch (_: IllegalStateException) {
        }

        val context: Context = ApplicationProvider.getApplicationContext()
        LogContext.init(context)
        assertSame(context, LogContext.get())

        // 第二次 init 不替换已持有的上下文
        LogContext.init(ApplicationProvider.getApplicationContext())
        assertSame(context, LogContext.get())
    }

    @Test
    fun `init creates log file and async writes land in it`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        LogUtils.init(context)
        LogUtils.d("TestTag", "hello-file-log")

        // AsyncFileHandler 把磁盘写入转移到单线程执行器：排空队列并 flush 后再断言
        logExecutor.submit {}.get()
        LogUtils.logger.handlers.forEach { it.flush() }

        val logFolder = File(context.externalCacheDir, "logs")
        val text = logFolder.listFiles { f -> f.name.endsWith(".txt") }
            ?.joinToString("") { it.readText() } ?: ""
        assertTrue("日志文件应包含写入的消息，实际内容:\n$text", text.contains("TestTag hello-file-log"))
    }

    @Test
    fun `device info text covers common fields without user agent`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val text = LogUtils.deviceInfoText(context, includeUserAgent = false)
        assertTrue(text.contains("MANUFACTURER="))
        assertTrue(text.contains("MODEL="))
        assertTrue(text.contains("SDK_INT="))
        assertTrue(text.contains("versionName="))
        assertTrue(!text.contains("WebViewUserAgent="))
    }
}
