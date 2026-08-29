package com.vibereading.app.data.repository

import com.vibereading.app.domain.model.ReadingSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阅读设置合并写持久化器回归测试（滑杆拖动「不跟手/跳变」修复）：
 * 旧实现每 tick 一次全量 DataStore 写，写库回声（store.data 回流）延迟覆盖较新的
 * UI 状态导致滑杆回跳；新实现 UI 状态为唯一事实源 + 最新值合并写。断言合并行为
 * 与退出时最后一批待存值不丢失。
 *
 * 统一用 runCurrent 驱动（本工程测试环境下 advanceUntilIdle 不执行排队任务，
 * runCurrent 语义对本测试恰好确定：每步只执行当前虚拟时刻已排队的任务）。
 */
class ReadingSettingsSaverTest {

    private fun settings(paddingH: Int) = ReadingSettings(paddingH = paddingH)

    @Test
    fun conflatesSubmissionsWhileSaveInFlight() = runTest {
        val written = mutableListOf<ReadingSettings>()
        val firstSaveGate = CompletableDeferred<Unit>()
        val saver = ReadingSettingsSaver(backgroundScope) { s ->
            written += s
            if (written.size == 1) firstSaveGate.await() // 模拟 DataStore 写库耗时
        }

        // 模拟一次拖动：写库进行中又来两个 tick，中间值 11 应被合并掉
        saver.submit(settings(paddingH = 10))
        runCurrent() // 写循环启动，进入第一笔写并挂起
        saver.submit(settings(paddingH = 11))
        saver.submit(settings(paddingH = 12))
        firstSaveGate.complete(Unit)
        runCurrent() // 恢复写循环，取走最新待存值 12

        assertEquals(
            "拖动期高频提交应合并为「进行中首笔 + 最新值」",
            listOf(10, 12),
            written.map { it.paddingH }
        )
    }

    @Test
    fun newSubmissionAfterDrainStartsNewSave() = runTest {
        val written = mutableListOf<ReadingSettings>()
        val saver = ReadingSettingsSaver(backgroundScope) { written += it }

        saver.submit(settings(paddingH = 10))
        runCurrent()
        saver.submit(settings(paddingH = 20))
        runCurrent()

        assertEquals(listOf(10, 20), written.map { it.paddingH })
    }

    @Test
    fun drainsPendingAfterScopeCancellation() = runTest {
        val written = mutableListOf<ReadingSettings>()
        val firstSaveGate = CompletableDeferred<Unit>()
        val parentJob = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parentJob)
        val saver = ReadingSettingsSaver(scope) { s ->
            written += s
            if (written.size == 1) firstSaveGate.await()
        }

        saver.submit(settings(paddingH = 10))
        runCurrent() // 第一笔写挂起中
        saver.submit(settings(paddingH = 20)) // 尚未落盘的待存值
        // 用户退出阅读器：scope 取消时最后一笔待存值仍应写完（NonCancellable）
        parentJob.cancel()
        firstSaveGate.complete(Unit)
        runCurrent()

        assertEquals("退出时待存的最后设置不得丢失", listOf(10, 20), written.map { it.paddingH })
    }
}
