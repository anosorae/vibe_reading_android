package com.vibereading.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * LLM 配置档案实体：支持多组 API/模型配置，用户可切换活跃配置。
 */
@Entity(tableName = "llm_profiles")
data class LlmProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val apiKey: String = "",
    val apiBase: String = "https://api.deepseek.com",
    val model: String = "deepseek-v4-flash",
    val chapterMaxChars: Int = 60000,
    val maxOutputTokens: Int = 32768,
    val enableThinking: Boolean = false,
    val enableExplainThinking: Boolean = false,
    val autoTranslateNext: Boolean = false, // 英文阅读时预译下一章
    val temperature: Float = 0.6f,
    val topP: Float = 1f,
    val isActive: Boolean = false
)
