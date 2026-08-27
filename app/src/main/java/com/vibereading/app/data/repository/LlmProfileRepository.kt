package com.vibereading.app.data.repository

import com.vibereading.app.BuildConfig
import com.vibereading.app.data.local.dao.LlmProfileDao
import com.vibereading.app.data.local.entity.LlmProfileEntity
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.toLlmProfile
import com.vibereading.app.domain.model.toLlmSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
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
        // 保持 isActive 状态不变：以库中现有记录为准（激活/取消激活走 setActive）
        val isActive = dao.getById(profile.id)?.isActive ?: false
        dao.update(profile.toEntity(isActive = isActive))
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
     * 首次启动初始化：llm_profiles 表为空时，从 DataStore 旧 LLM 键创建默认配置；
     * 表非空但活跃档案仍是「空 key 占位」时，用 local.properties 的调试配置补齐
     * （DEBUG_LLM_* 是开发者本地联调配置，见 build.gradle.kts；不覆盖用户手填的 key）。
     * 调用后 DataStore 旧键会被清除。
     */
    suspend fun ensureDefaultProfile() {
        if (dao.count() > 0) {
            applyDebugConfigIfPlaceholder()
            return
        }
        val migrated = settingsRepo.migrateLlmKeysToProfile()
        val default = (migrated ?: debugLlmSettings() ?: LlmSettings()).toLlmProfile(name = "默认配置")
        val id = dao.insert(default.toEntity(isActive = true))
        if (id > 0 && !default.apiKey.isBlank()) {
            // 有效迁移，清除旧 DataStore 键
            settingsRepo.clearMigratedLlmKeys()
        }
    }

    /** 活跃档案 apiKey 为空（自动创建的占位）且 local.properties 已配置调试 LLM 时补齐。 */
    private suspend fun applyDebugConfigIfPlaceholder() {
        val debugBase = BuildConfig.DEBUG_LLM_API_BASE.trim().trimEnd('/')
        val debugKey = BuildConfig.DEBUG_LLM_API_KEY.trim()
        val debugModel = BuildConfig.DEBUG_LLM_MODEL.trim()
        if (debugBase.isEmpty() && debugKey.isEmpty() && debugModel.isEmpty()) return
        val active = dao.getActive().firstOrNull()
        if (active == null || active.apiKey.isNotBlank()) return // 已有手填 key 不覆盖
        dao.update(
            active.copy(
                apiBase = debugBase.ifEmpty { active.apiBase },
                apiKey = debugKey,
                model = debugModel.ifEmpty { active.model }
            )
        )
    }

    /** local.properties 调试 LLM 配置 → LlmSettings（未配置时返回 null）。 */
    private fun debugLlmSettings(): LlmSettings? {
        val base = BuildConfig.DEBUG_LLM_API_BASE.trim().trimEnd('/')
        val key = BuildConfig.DEBUG_LLM_API_KEY.trim()
        val model = BuildConfig.DEBUG_LLM_MODEL.trim()
        if (base.isEmpty() && key.isEmpty() && model.isEmpty()) return null
        return LlmSettings(
            apiBase = if (base.isNotEmpty()) base else "https://api.deepseek.com",
            apiKey = key,
            model = if (model.isNotEmpty()) model else "deepseek-v4-flash"
        )
    }

    private fun LlmProfileEntity.toDomain(): LlmProfile = LlmProfile(
        id = id,
        name = name,
        apiKey = apiKey,
        apiBase = apiBase,
        model = model,
        chapterMaxChars = chapterMaxChars,
        maxOutputTokens = maxOutputTokens,
        enableThinking = enableThinking,
        enableExplainThinking = enableExplainThinking,
        autoTranslateNext = autoTranslateNext,
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
        maxOutputTokens = maxOutputTokens,
        enableThinking = enableThinking,
        enableExplainThinking = enableExplainThinking,
        autoTranslateNext = autoTranslateNext,
        temperature = temperature.coerceIn(0f, 2f),
        topP = topP.coerceIn(0f, 1f),
        isActive = isActive
    )
}
