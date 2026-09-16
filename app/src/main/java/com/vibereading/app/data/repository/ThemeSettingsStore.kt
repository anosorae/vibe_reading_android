package com.vibereading.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 全局主题（亮暗模式 + 强调色）；由 [SettingsRepository.theme] 提供。
 *
 * 旧版只有 accent（"theme" 键存 "vibe"/"weread"）；新版拆为 themeMode + accent，
 * 读取旧键自动迁移为对应 accent，themeMode 默认 SYSTEM。
 */
class ThemeSettingsStore(private val store: DataStore<Preferences>) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val LEGACY_THEME = stringPreferencesKey("theme")
    }

    val settings: Flow<ThemeSettings> = store.safeData("读取主题设置失败，回退默认值")
        .map { prefs ->
            val themeMode = when (prefs[Keys.THEME_MODE]) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            val accent = when (prefs[Keys.ACCENT] ?: prefs[Keys.LEGACY_THEME]) {
                "weread" -> AppAccent.WEREAD
                else -> AppAccent.VIBE
            }
            ThemeSettings(themeMode = themeMode, accent = accent)
        }

    suspend fun saveSettings(settings: ThemeSettings) {
        store.edit { prefs ->
            prefs[Keys.THEME_MODE] = when (settings.themeMode) {
                ThemeMode.SYSTEM -> "system"
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
            }
            prefs[Keys.ACCENT] = when (settings.accent) {
                AppAccent.VIBE -> "vibe"
                AppAccent.WEREAD -> "weread"
            }
            prefs.remove(Keys.LEGACY_THEME)
        }
    }
}
