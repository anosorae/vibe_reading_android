package com.vibereading.app.ui.reader

import android.os.SystemClock
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 阅读时长计时器（阅读器内，每本书一个实例，随 ReaderViewModel 存亡）。
 *
 * 口径（grilling 定案，2026-09）：
 * - 阅读器前台即计时；任何触摸交互（翻页/滚动/选词/点按钮/打开面板）重置空闲计时器，
 *   连续 [IDLE_TIMEOUT_SECONDS] 无交互则暂停，直到下一次交互恢复——规则只有一条，无特判；
 * - 心跳式落库：累计满 [HEARTBEAT_SECONDS] 秒即 UPSERT 一次增量，崩溃/强杀最多丢一个心跳；
 * - 只统计 App 内阅读，Web 伴读不计入（伴读端没有可靠的上报通道）；
 * - 跨午夜的心跳按提交时刻的当天归属，无需会话区间表。
 *
 * 全部状态只在主线程触碰（ticker、交互回调、flush 均来自 UI 线程协程）；
 * 落库经 [sink] 异步/挂起进行，UPSERT 只做加法、与顺序无关。
 */
class ReadingTimeTracker(
    private val bookId: Long,
    private val scope: CoroutineScope,
    private val sink: suspend (bookId: Long, epochDay: Long, deltaSeconds: Long) -> Unit,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val currentEpochDay: () -> Long = { LocalDate.now().toEpochDay() }
) {
    companion object {
        /** 空闲暂停阈值（秒）：超过此时长无任何交互则停止计时。 */
        const val IDLE_TIMEOUT_SECONDS = 120L

        /** 心跳周期（秒）：累计满该秒数落库一次。 */
        const val HEARTBEAT_SECONDS = 60L
    }

    private var ticker: Job? = null
    private var started = false
    private var foreground = true
    private var lastInteractionMs = 0L
    private var pendingSeconds = 0L

    /** 首屏内容就绪后启动；书籍加载失败则永不启动（不计时）。 */
    fun start() {
        if (started) return
        started = true
        // 打开即视为一次交互：开始阅读的头两分钟不空等第一次触摸
        lastInteractionMs = elapsedRealtimeMs()
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    /** 任何触摸/翻页/跳转交互：重置空闲计时器（UI 线程高频调用，仅一次长整型写）。 */
    fun onInteraction() {
        lastInteractionMs = elapsedRealtimeMs()
    }

    /** ON_START：恢复计时。 */
    fun onForeground() {
        foreground = true
    }

    /** ON_STOP：同步暂停计时；余量由调用方随后 flush，不等待写库再改变前后台状态。 */
    fun onBackground() {
        foreground = false
    }

    /** 退出阅读器前：落盘余量（挂起直至写入完成，调用方在返回导航前 await）。 */
    suspend fun flush() {
        if (!started || pendingSeconds <= 0L) return
        val delta = pendingSeconds
        pendingSeconds = 0L
        sink(bookId, currentEpochDay(), delta)
    }

    /** 一秒一跳：已启动、前台且未空闲才累计；满一个心跳即落库。单测直接驱动本函数推进时间。 */
    internal fun tick() {
        if (!started || !foreground) return
        val now = elapsedRealtimeMs()
        if (now - lastInteractionMs > IDLE_TIMEOUT_SECONDS * 1000) return
        pendingSeconds++
        if (pendingSeconds >= HEARTBEAT_SECONDS) {
            val delta = pendingSeconds
            pendingSeconds = 0L
            scope.launch { sink(bookId, currentEpochDay(), delta) }
        }
    }
}
