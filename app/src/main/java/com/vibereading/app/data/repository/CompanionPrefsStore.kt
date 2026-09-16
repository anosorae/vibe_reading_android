package com.vibereading.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Web 伴读服务开关（ADR-005）；由 [SettingsRepository.companion] 提供。 */
class CompanionPrefsStore(private val store: DataStore<Preferences>) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("web_companion_enabled")
    }

    /**
     * 伴读服务期望开启状态。
     * 注意：**不随 App 启动自动拉起**——每次启动都被 `AppNavigation` 归为关闭，
     * 由用户在设置页手动开启（前台服务不该在用户没要求时自出现）。
     */
    val enabled: Flow<Boolean> = store.safeData("读取伴读开关失败，回退默认值")
        .map { prefs -> prefs[Keys.ENABLED] ?: false }

    suspend fun saveEnabled(enabled: Boolean) {
        store.edit { prefs -> prefs[Keys.ENABLED] = enabled }
    }
}
