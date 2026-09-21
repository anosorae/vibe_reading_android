package com.vibereading.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 阅读时长按天 × 按书聚合行：主键 (bookId, epochDay)，心跳增量走 UPSERT 累加秒数。
 * epochDay 取提交时刻的本地时区日期，跨午夜的会话自然拆分到各自当天。
 * 删书时随外键 CASCADE 清掉该书时长——与进度、译文同命运，统计只反映现存书库。
 */
@Entity(
    tableName = "reading_time_daily",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")],
    primaryKeys = ["bookId", "epochDay"]
)
data class ReadingTimeEntity(
    val bookId: Long,
    val epochDay: Long,
    val seconds: Long
)
