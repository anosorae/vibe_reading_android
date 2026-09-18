package com.vibereading.app.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AppLog 单测：内存环形缓冲语义——newest-first、null 忽略、上限 100 条、clear。
 * 文件镜像路径（LogUtils）由 LogUtilsTest 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class AppLogTest {

    @Before
    fun setUp() {
        AppLog.clear()
    }

    @After
    fun tearDown() {
        AppLog.clear()
    }

    @Test
    fun `put stores newest first with timestamp and throwable`() {
        val throwable = IllegalStateException("炸了")
        AppLog.put("第一条")
        AppLog.put("第二条", throwable)

        val logs = AppLog.logs
        assertEquals(2, logs.size)
        assertEquals("第二条", logs[0].second)
        assertEquals(throwable, logs[0].third)
        assertEquals("第一条", logs[1].second)
        assertNull(logs[1].third)
        assertTrue(logs[0].first >= logs[1].first)
    }

    @Test
    fun `null message is ignored`() {
        AppLog.put(null)
        assertTrue(AppLog.logs.isEmpty())
    }

    @Test
    fun `ring buffer caps at 100 keeping newest`() {
        repeat(105) { AppLog.putNotSave("msg$it") }

        val logs = AppLog.logs
        assertEquals(100, logs.size)
        assertEquals("msg104", logs.first().second)
        assertEquals("msg5", logs.last().second)
    }

    @Test
    fun `clear empties the buffer`() {
        AppLog.put("留下来的")
        AppLog.clear()
        assertTrue(AppLog.logs.isEmpty())
    }
}
