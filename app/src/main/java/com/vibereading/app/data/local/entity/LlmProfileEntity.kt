package com.vibereading.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vibereading.app.domain.model.LlmDefaults

/**
 * LLM 配置档案实体：支持多组 API/模型配置，用户可切换活跃配置。
 */
@Entity(tableName = "llm_profiles")
data class LlmProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val apiKey: String = "",
    val apiBase: String = LlmDefaults.API_BASE,
    val model: String = LlmDefaults.MODEL,
    val chapterMaxChars: Int = LlmDefaults.CHAPTER_MAX_CHARS,
    val maxOutputTokens: Int = LlmDefaults.MAX_OUTPUT_TOKENS,
    val enableThinking: Boolean = LlmDefaults.ENABLE_THINKING,
    val enableExplainThinking: Boolean = LlmDefaults.ENABLE_EXPLAIN_THINKING,
    val autoTranslateNext: Boolean = LlmDefaults.AUTO_TRANSLATE_NEXT,
    val temperature: Float = LlmDefaults.TEMPERATURE,
    val topP: Float = LlmDefaults.TOP_P,
    val isActive: Boolean = false
)
