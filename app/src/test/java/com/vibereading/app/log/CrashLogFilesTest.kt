package com.vibereading.app.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CrashLogFiles 单测：崩溃文件列表（仅 .log、按文件名 newest-first）、
 * 读取（缺失文件回退错误文案）、单条删除与清空。
 */
@RunWith(RobolectricTestRunner::class)
class CrashLogFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var folder: File

    @Before
    fun setUp() {
        folder = File(context.externalCacheDir, "crash").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
    }

    @After
    fun tearDown() {
        CrashLogFiles.clear(context)
    }

    @Test
    fun `list returns only log files sorted by name descending`() {
        File(folder, "crash-2026-01-01-00-00-00-1000.log").writeText("A")
        File(folder, "crash-2026-01-02-00-00-00-2000.log").writeText("B")
        File(folder, "note.txt").writeText("C")
        File(folder, "crash-2026-01-02-00-00-00-2000.log.lck").writeText("D")

        val names = CrashLogFiles.list(context).map { it.name }
        assertEquals(
            listOf("crash-2026-01-02-00-00-00-2000.log", "crash-2026-01-01-00-00-00-1000.log"),
            names
        )
    }

    @Test
    fun `list on missing folder is empty`() {
        CrashLogFiles.clear(context)
        folder.delete()
        assertTrue(CrashLogFiles.list(context).isEmpty())
    }

    @Test
    fun `read returns content and falls back on missing file`() {
        val file = File(folder, "crash-x.log").apply { writeText("崩溃详情") }
        assertEquals("崩溃详情", CrashLogFiles.read(file))
        assertTrue(CrashLogFiles.read(File(folder, "不存在的.log")).startsWith("读取失败"))
    }

    @Test
    fun `delete removes single file and clear removes all`() {
        val a = File(folder, "crash-a.log").apply { writeText("A") }
        val b = File(folder, "crash-b.log").apply { writeText("B") }

        assertTrue(CrashLogFiles.delete(a))
        assertFalse(a.exists())
        assertNull(CrashLogFiles.list(context).firstOrNull { it == a })

        CrashLogFiles.clear(context)
        assertTrue(CrashLogFiles.list(context).isEmpty())
        assertFalse(b.exists())
    }
}
