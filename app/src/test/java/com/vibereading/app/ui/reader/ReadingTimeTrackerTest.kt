package com.vibereading.app.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读时长计时器：直接驱动 [ReadingTimeTracker.tick] 推进虚拟时钟，
 * 断言空闲暂停、心跳落库与 flush 余量的结构化行为（不依赖真实 delay）。
 */
class ReadingTimeTrackerTest {

    private var nowMs = 0L
    private var day = 20686L

    private val writes = mutableListOf<Triple<Long, Long, Long>>() // (bookId, epochDay, delta)

    private fun newTracker(): ReadingTimeTracker =
        ReadingTimeTracker(
            bookId = 7L,
            scope = CoroutineScope(Dispatchers.Unconfined),
            sink = { bookId, epochDay, delta -> writes += Triple(bookId, epochDay, delta) },
            elapsedRealtimeMs = { nowMs },
            currentEpochDay = { day }
        )

    /** 推进虚拟时钟：每秒一跳（tick 内部取当下时刻做空闲判定）。 */
    private fun advanceSeconds(seconds: Int, tracker: ReadingTimeTracker) {
        repeat(seconds) {
            nowMs += 1_000
            tracker.tick()
        }
    }

    @Test
    fun `持续交互时逐秒累计满一个心跳即落库`() = runBlocking {
        val tracker = newTracker()
        tracker.start()
        advanceSeconds(30, tracker)
        tracker.onInteraction()
        advanceSeconds(30, tracker)
        // 第 60 秒累计满心跳，立即落库
        assertEquals(listOf(Triple(7L, day, 60L)), writes)
        advanceSeconds(60, tracker)
        assertEquals(2, writes.size)
        assertEquals(60L, writes.last().third)
    }

    @Test
    fun `空闲两分钟后暂停直到下一次交互恢复`() = runBlocking {
        val tracker = newTracker()
        tracker.start()
        // 1..120 秒：与 start 的交互间隔 1..120，恰好都不算「超过 2 分钟」，全部累计；
        // 第 60 秒满一次心跳落库，第 120 秒 pending 再次满 60 落库
        advanceSeconds(120, tracker)
        assertEquals(2, writes.size)
        // 121 秒起超过阈值：暂停，不再累计也不落库
        advanceSeconds(300, tracker)
        assertEquals(2, writes.size)
        // 恢复交互后从 0 重新累计，再满 60 秒才落库
        tracker.onInteraction()
        advanceSeconds(59, tracker)
        assertEquals(2, writes.size)
        advanceSeconds(1, tracker)
        assertEquals(3, writes.size)
        assertEquals(60L, writes.last().third)
    }

    @Test
    fun `onBackground 落盘余量并暂停后台累计`() = runBlocking {
        val tracker = newTracker()
        tracker.start()
        advanceSeconds(45, tracker)
        tracker.onBackground()
        assertEquals(listOf(Triple(7L, day, 45L)), writes)
        // 后台期间（无交互也不计数）：不产生任何写入
        advanceSeconds(120, tracker)
        assertEquals(1, writes.size)
        // 回前台并交互：从零开始累计，余量已落盘不重复
        tracker.onForeground()
        tracker.onInteraction()
        advanceSeconds(59, tracker)
        assertEquals(1, writes.size) // 59 < 60 未满心跳
    }

    @Test
    fun `跨午夜心跳按提交时刻当天归属`() = runBlocking {
        val tracker = newTracker()
        tracker.start()
        advanceSeconds(59, tracker)
        day = 20687L // 午夜已过
        advanceSeconds(1, tracker)
        assertEquals(listOf(Triple(7L, 20687L, 60L)), writes)
    }

    @Test
    fun `未 start 时 tick 与 flush 均为 no-op`() = runBlocking {
        val tracker = newTracker()
        advanceSeconds(120, tracker)
        tracker.onBackground()
        assertTrue(writes.isEmpty())
    }
}
