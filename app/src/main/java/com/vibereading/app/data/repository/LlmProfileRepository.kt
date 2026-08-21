package com.vibereading.app.data.repository

import com.vibereading.app.data.local.dao.LlmProfileDao
import com.vibereading.app.data.local.entity.LlmProfileEntity
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.toLlmProfile
import com.vibereading.app.domain.model.toLlmSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LlmProfileRepository(
    private val dao: LlmProfileDao,
    private val settingsRepo: SettingsRepository
) {

    /** 全部配置档案列表 */
    val profiles: Flow<List<LlmProfile>> = dao.getAll().map { list ->
        list.map { it.toDomain() }
    }

    /** 当前活跃配置 */
    val activeProfile: Flow<LlmProfile?> = dao.getActive().map { it?.toDomain() }

    /** 当前活跃配置映射为翻译管线传输对象（替代原 SettingsRepository.llmSettings） */
    val activeLlmSettings: Flow<LlmSettings> = dao.getActive().map { entity ->
        entity?.toDomain()?.toLlmSettings() ?: LlmSettings()
    }

    suspend fun addProfile(profile: LlmProfile): Long {
        return dao.insert(profile.toEntity(isActive = false))
    }

    suspend fun updateProfile(profile: LlmProfile) {
        val isActive = dao.getActive() // 不用 Flow，这里用一次性的即可
        // 保持 isActive 状态不变
        dao.update(profile.toEntity(isActive = false)) // 稍后通过 setActiveIfNeeded 处理
    }

    /** 更新配置档案，保留其当前 isActive 状态 */
    suspend fun updateProfileWithActiveState(profile: LlmProfile, isActive: Boolean) {
        dao.update(profile.toEntity(isActive = isActive))
    }

    suspend fun deleteProfile(id: Long) {
        dao.deleteById(id)
    }

    /** 切换活跃配置（事务） */
    suspend fun setActive(id: Long) {
        dao.setActive(id)
    }

    /**
     * 首次启动迁移：如果 llm_profiles 表为空，从 DataStore 旧 LLM 键创建默认配置。
     * 调用后 DataStore 旧键会被清除。
     */
    suspend fun ensureDefaultProfile() {
        if (dao.count() > 0) return
        val migrated = settingsRepo.migrateLlmKeysToProfile()
        val default = (migrated ?: LlmSettings()).toLlmProfile(name = "默认配置")
        val id = dao.insert(default.toEntity(isActive = true))
        if (id > 0 && !default.apiKey.isBlank()) {
            // 有效迁移，清除旧 DataStore 键
            settingsRepo.clearMigratedLlmKeys()
        }
    }

    private fun LlmProfileEntity.toDomain(): LlmProfile = LlmProfile(
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

    private fun LlmProfile.toEntity(isActive: Boolean): LlmProfileEntity = LlmProfileEntity(
        id = id,
        name = name,
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
        topP = topP.coerceIn(0f, 1f),
        isActive = isActive
    )
}
