package com.vibereading.app.domain.model

/**
 * LLM 配置档案：用户可保存多组配置（不同 API/模型/参数），切换活跃配置即时生效。
 * 持久化于 Room llm_profiles 表；[toLlmSettings] 转换为翻译管线使用的传输对象。
 */
data class LlmProfile(
    val id: Long = 0,
    val name: String = "",
    val apiKey: String = "",
    val apiBase: String = "https://api.deepseek.com",
    val model: String = "deepseek-v4-flash",
    val chapterMaxChars: Int = 30000,
    val enableContextBoost: Boolean = false,
    val contextChapters: Int = 1,
    val contextMaxChars: Int = 50000,
    val enableThinking: Boolean = false,
    val enableExplainThinking: Boolean = false,
    val temperature: Float = 0.6f,
    val topP: Float = 1f
)

/** 配置档案 → 翻译管线传输对象 */
fun LlmProfile.toLlmSettings(): LlmSettings = LlmSettings(
    apiKey = apiKey,
    apiBase = apiBase,
    model = model,
    chapterMaxChars = chapterMaxChars,
    enableContextBoost = enableContextBoost,
    contextChapters = contextChapters.coerceIn(1, 3),
    contextMaxChars = contextMaxChars,
    enableThinking = enableThinking,
    enableExplainThinking = enableExplainThinking,
    temperature = temperature.coerceIn(0f, 2f),
    topP = topP.coerceIn(0f, 1f)
)

/** 翻译管线传输对象 → 配置档案（用于 DataStore 旧数据迁移） */
fun LlmSettings.toLlmProfile(name: String, id: Long = 0): LlmProfile = LlmProfile(
    id = id,
    name = name,
    apiKey = apiKey,
    apiBase = apiBase,
    model = model,
    chapterMaxChars = chapterMaxChars,
    enableContextBoost = enableContextBoost,
    contextChapters = contextChapters,
    contextMaxChars = contextMaxChars,
    enableThinking = enableThinking,
    enableExplainThinking = enableExplainThinking,
    temperature = temperature,
    topP = topP
)
