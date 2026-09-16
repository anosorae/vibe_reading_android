package com.vibereading.app.ui.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationKeepAliveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `service follows active task set instead of individual task completion`() {
        var companionRunning = false
        var starts = 0
        var stops = 0
        val keepAlive = TranslationKeepAlive(
            appContext = context,
            companionRunning = { companionRunning },
            startService = { starts++ },
            stopService = { stops++ }
        )
        keepAlive.taskStarted(10L)
        keepAlive.taskStarted(11L)
        assertEquals(1, starts)
        assertEquals(0, stops)

        keepAlive.taskFinished(10L)
        assertEquals("另一个任务仍运行时不能停止服务", 0, stops)

        keepAlive.taskFinished(11L)
        assertEquals(1, stops)
    }

    @Test
    fun `old run finishing cannot release replacement run`() {
        var starts = 0
        var stops = 0
        val keepAlive = TranslationKeepAlive(
            appContext = context,
            companionRunning = { false },
            startService = { starts++ },
            stopService = { stops++ }
        )

        keepAlive.taskStarted(30L)
        keepAlive.taskStarted(31L)
        keepAlive.taskFinished(30L)

        assertEquals(1, starts)
        assertEquals("替换 run 仍活动时不能释放保活", 0, stops)
        keepAlive.taskFinished(31L)
        assertEquals(1, stops)
    }

    @Test
    fun `companion service takes over and releases keep alive ownership`() {
        var companionRunning = false
        var starts = 0
        var stops = 0
        val keepAlive = TranslationKeepAlive(
            appContext = context,
            companionRunning = { companionRunning },
            startService = { starts++ },
            stopService = { stops++ }
        )
        keepAlive.taskStarted(20L)
        assertEquals(1, starts)

        companionRunning = true
        keepAlive.companionStateChanged()
        assertEquals("伴读启动后接管保活", 1, stops)

        companionRunning = false
        keepAlive.companionStateChanged()
        assertEquals("伴读停止而任务仍运行时翻译服务重新接管", 2, starts)

        keepAlive.taskFinished(20L)
        assertEquals(2, stops)
    }
}
