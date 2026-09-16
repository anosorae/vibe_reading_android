package com.vibereading.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vibereading.app.BuildConfig
import com.vibereading.app.domain.model.LlmDefaults
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * DataStore 旧 LLM 键 → Room `llm_profiles` 表的一次性迁移（由 [SettingsRepository.llmLegacy] 提供）。
 *
 * LLM 配置已迁到 Room；本类只服务首次启动：读旧键建默认档案，之后清除旧键并落迁移标记。
 * 迁移完成后的正常读写一律走 `LlmProfileRepository`。
 */
class LlmLegacyKeysStore(private val store: DataStore<Preferences>) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE = stringPreferencesKey("api_base")
        val MODEL = stringPreferencesKey("model")
        val CHAPTER_MAX_CHARS = intPreferencesKey("chapter_max_chars")
        val ENABLE_THINKING = booleanPreferencesKey("enable_thinking")
        val MIGRATED = booleanPreferencesKey("llm_migrated_to_room")
    }

    private val defaultApiBase: String
        get() = BuildConfig.DEBUG_LLM_API_BASE.trim().trimEnd('/')
            .ifEmpty { LlmDefaults.API_BASE }

    /**
     * 读取 DataStore 中的旧 LLM 键，返回 [LlmSettings] 用于创建默认 profile。
     * 已迁移过（MIGRATED 标记存在）或无任何旧键时返回 null。
     */
    suspend fun migrateToProfile(): LlmSettings? {
        val prefs = try {
            store.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("读取旧 LLM 配置失败", e)
            throw e
        }
        if (prefs[Keys.MIGRATED] == true) return null
        val hasAnyKey = prefs.contains(Keys.API_KEY) ||
            prefs.contains(Keys.API_BASE) ||
            prefs.contains(Keys.MODEL)
        if (!hasAnyKey) return null
        return LlmSettings(
            apiKey = prefs[Keys.API_KEY]?.trim() ?: BuildConfig.DEBUG_LLM_API_KEY.ifEmpty { "" },
            apiBase = prefs[Keys.API_BASE]?.trim()?.trimEnd('/')?.ifEmpty { defaultApiBase } ?: defaultApiBase,
            model = prefs[Keys.MODEL]?.trim() ?: BuildConfig.DEBUG_LLM_MODEL.ifEmpty { LlmDefaults.MODEL },
            chapterMaxChars = prefs[Keys.CHAPTER_MAX_CHARS] ?: LlmDefaults.CHAPTER_MAX_CHARS,
            enableThinking = prefs[Keys.ENABLE_THINKING] ?: LlmDefaults.ENABLE_THINKING
        )
    }

    /** 标记迁移完成并清除旧 DataStore LLM 键。 */
    suspend fun clearMigratedKeys() {
        store.edit { prefs ->
            prefs.remove(Keys.API_KEY)
            prefs.remove(Keys.API_BASE)
            prefs.remove(Keys.MODEL)
            prefs.remove(Keys.CHAPTER_MAX_CHARS)
            prefs.remove(Keys.ENABLE_THINKING)
            prefs[Keys.MIGRATED] = true
        }
    }
}
