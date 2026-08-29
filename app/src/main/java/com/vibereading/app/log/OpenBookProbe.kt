package com.vibereading.app.log

import android.os.SystemClock

/**
 * 打开书籍链路耗时探针：书架点击 → ViewModel 加载 → 字体/样式解析 → 中心章排版 → 首帧内容就绪。
 *
 * 各探针点输出「距上一步的间隔 + 距开始的总耗时」，经 [AppLog] 落文件日志
 * （debug 构建同时镜像到 logcat）。finish 后静默：排版窗口在阅读过程中的后续重建
 * （译文更新、切模式）不再产生探针日志；再次从书架打开书籍时 begin 重新计时。
 */
object OpenBookProbe {

    private var beginAt = 0L
    private var lastAt = 0L

    /** 书架点击打开书籍时开始计时（同一时刻只有一条打开链路，重复 begin 重置）。 */
    fun begin() {
        beginAt = SystemClock.elapsedRealtime()
        lastAt = beginAt
        AppLog.put("[打开书籍] 开始计时")
    }

    /** 记录一个探针点；链路未开始或已结束时静默。 */
    fun step(message: String) {
        if (beginAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastAt
        lastAt = now
        AppLog.put("[打开书籍] $message +${delta}ms（累计 ${now - beginAt}ms）")
    }

    /** 链路结束（首页内容就绪）：输出总耗时并复位，后续 step 不再落日志。 */
    fun finish() {
        if (beginAt == 0L) return
        AppLog.put("[打开书籍] 完成，总耗时 ${SystemClock.elapsedRealtime() - beginAt}ms")
        beginAt = 0L
        lastAt = 0L
    }
}
