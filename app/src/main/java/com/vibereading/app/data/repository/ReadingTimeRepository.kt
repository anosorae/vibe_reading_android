package com.vibereading.app.data.repository

import com.vibereading.app.data.local.dao.BookTimeTotal
import com.vibereading.app.data.local.dao.DailyTimeTotal
import com.vibereading.app.data.local.dao.ReadingTimeDao
import com.vibereading.app.log.AppLog
import kotlinx.coroutines.flow.Flow

/**
 * 阅读时长仓库：心跳增量写入 + 统计页聚合查询。
 * 写失败只落日志不抛出——丢一个心跳（≤60 秒）远比打断阅读严重。
 */
class ReadingTimeRepository(private val dao: ReadingTimeDao) {

    /** 累加一段阅读秒数到 (bookId, epochDay)；非正增量直接忽略。 */
    suspend fun addSeconds(bookId: Long, epochDay: Long, deltaSeconds: Long) {
        if (deltaSeconds <= 0L) return
        try {
            dao.addSeconds(bookId, epochDay, deltaSeconds)
        } catch (t: Throwable) {
            AppLog.put("阅读时长写入失败 bookId=$bookId day=$epochDay delta=${deltaSeconds}s", t)
        }
    }

    /** 按天聚合流（今日/本周/累计与柱状图序列）。 */
    fun observeDailyTotals(): Flow<List<DailyTimeTotal>> = dao.observeDailyTotals()

    /** 按书聚合流（最近阅读卡的「累读」）。 */
    fun observeBookTotals(): Flow<List<BookTimeTotal>> = dao.observeBookTotals()
}
