package com.vibereading.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 按天聚合行（今日/本周/累计与柱状图序列）。 */
data class DailyTimeTotal(
    val epochDay: Long,
    val seconds: Long
)

/** 按书聚合行（最近阅读卡的「累读」）。 */
data class BookTimeTotal(
    val bookId: Long,
    val seconds: Long
)

@Dao
interface ReadingTimeDao {

    /**
     * 心跳增量累加：(bookId, epochDay) 行不存在则先建行，再原子加增量。
     * 不用 `ON CONFLICT ... DO UPDATE` 一句 upsert：Robolectric 内置 SQLite 版本过旧
     * 不支持该语法；两步各自原子，交错执行也只做加法，结果一致。
     */
    @Transaction
    suspend fun addSeconds(bookId: Long, epochDay: Long, deltaSeconds: Long) {
        ensureRow(bookId, epochDay)
        incrementSeconds(bookId, epochDay, deltaSeconds)
    }

    @Query("INSERT OR IGNORE INTO reading_time_daily (bookId, epochDay, seconds) VALUES (:bookId, :epochDay, 0)")
    suspend fun ensureRow(bookId: Long, epochDay: Long)

    @Query(
        "UPDATE reading_time_daily SET seconds = seconds + :deltaSeconds " +
            "WHERE bookId = :bookId AND epochDay = :epochDay"
    )
    suspend fun incrementSeconds(bookId: Long, epochDay: Long, deltaSeconds: Long)

    @Query("SELECT epochDay, SUM(seconds) AS seconds FROM reading_time_daily GROUP BY epochDay")
    fun observeDailyTotals(): Flow<List<DailyTimeTotal>>

    @Query("SELECT bookId, SUM(seconds) AS seconds FROM reading_time_daily GROUP BY bookId")
    fun observeBookTotals(): Flow<List<BookTimeTotal>>
}
