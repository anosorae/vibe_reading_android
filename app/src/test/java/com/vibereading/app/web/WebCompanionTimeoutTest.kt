package com.vibereading.app.web

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android 15 起 `dataSync` 类前台服务有「每 24 小时累计约 6 小时」上限，到点系统回调
 * `Service.onTimeout()`。应用必须在回调内停掉服务，否则系统抛异常（进程崩溃）。
 *
 * 这条路径真机上要等 6 小时才会触发，只能在单测里直接调回调钉住行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebCompanionTimeoutTest {

    private fun service(): WebCompanionService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WebCompanionService.createNotificationChannel(context)
        return Robolectric.buildService(WebCompanionService::class.java).create().get()
    }

    private fun posted(context: Context): List<Notification> =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Test
    fun `达到时长上限时停止自身并给出可读说明`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = service()

        service.onTimeout(1)

        val all = posted(context)
        assertEquals("应当只留一条说明通知", 1, all.size)
        val n = all.first()
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        assertTrue("标题应说明伴读已停止，实为：$title", title.contains("停止"))
        assertTrue("正文应说明原因是系统时长上限，实为：$text", text.contains("时长"))
        assertTrue("正文应告诉用户如何恢复，实为：$text", text.contains("重新开启"))

        // 前台通知是常驻的；这条要能被划掉，否则会一直挂在通知栏
        assertEquals("说明通知不应是常驻", 0, n.flags and Notification.FLAG_ONGOING_EVENT)

        // 关键：必须在回调内自行停止，否则系统会抛异常
        assertTrue("应当调用 stopSelf 自行停止", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `两个 onTimeout 重载都覆盖且只处理一次`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = service()

        service.onTimeout(1)
        service.onTimeout(1, 1)   // 不同系统版本调用的重载不同

        assertEquals("重复回调不应重复发通知", 1, posted(context).size)
    }
}
