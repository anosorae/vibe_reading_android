package com.vibereading.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vibereading.app.domain.model.ReadingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 阅读设置与夜间模式（原 `SettingsRepository` 的阅读域）。
 * 由 [SettingsRepository.reading] 提供，与其他偏好域共享同一个 DataStore。
 */
class ReadingSettingsStore(private val store: DataStore<Preferences>) {

    private object Keys {
        val FONT_SIZE = intPreferencesKey("font_size")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val BG_COLOR_INDEX = intPreferencesKey("bg_color_index")
        val LINE_SPACING = intPreferencesKey("line_spacing")
        val PARAGRAPH_SPACING = intPreferencesKey("paragraph_spacing")
        val PAGE_FLIP_MODE = stringPreferencesKey("page_flip_mode")
        val PADDING_H = intPreferencesKey("padding_h")
        val PADDING_V = intPreferencesKey("padding_v")
        val OVERLAY_CONTENT_GAP = intPreferencesKey("overlay_content_gap")  // 旧版兼容
        val HEADER_CONTENT_GAP = intPreferencesKey("header_content_gap")
        val FOOTER_CONTENT_GAP = intPreferencesKey("footer_content_gap")
        val LETTER_SPACING = floatPreferencesKey("letter_spacing")
        val JUSTIFY = booleanPreferencesKey("justify")
        val INDENT_EM = floatPreferencesKey("indent_em")
        val TITLE_MODE = intPreferencesKey("title_mode")
        val BOTTOM_JUSTIFY = booleanPreferencesKey("bottom_justify")
        val ONE_HAND_MODE = booleanPreferencesKey("one_hand_mode")
        val CUSTOM_FONT_URI = stringPreferencesKey("custom_font_uri")
        val EN_CUSTOM_FONT_URI = stringPreferencesKey("en_custom_font_uri")
        val FONT_ID = stringPreferencesKey("font_id")
        val EN_FONT_ID = stringPreferencesKey("en_font_id")
        val HIDE_STATUS_BAR = booleanPreferencesKey("hide_status_bar")
        val HIDE_NAVIGATION_BAR = booleanPreferencesKey("hide_navigation_bar")
        val NIGHT_MODE = booleanPreferencesKey("night_mode")
    }

    val settings: Flow<ReadingSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            ReadingSettings(
                fontSize = prefs[Keys.FONT_SIZE] ?: 17,
                // 旧版默认 serif 为遗留值，UI 已无该选项，读取时规范为系统字体
                fontFamily = prefs[Keys.FONT_FAMILY]?.takeUnless { it == "serif" } ?: "default",
                bgColorIndex = prefs[Keys.BG_COLOR_INDEX] ?: 0,
                lineSpacing = prefs[Keys.LINE_SPACING] ?: 8,
                paragraphSpacing = prefs[Keys.PARAGRAPH_SPACING] ?: 16,
                pageFlipMode = prefs[Keys.PAGE_FLIP_MODE] ?: ReadingSettings.FLIP_PAGER,
                paddingH = prefs[Keys.PADDING_H] ?: 22,
                paddingV = prefs[Keys.PADDING_V] ?: 20,
                headerContentGap = prefs[Keys.HEADER_CONTENT_GAP] ?: prefs[Keys.OVERLAY_CONTENT_GAP] ?: 20,
                footerContentGap = prefs[Keys.FOOTER_CONTENT_GAP] ?: prefs[Keys.OVERLAY_CONTENT_GAP] ?: 20,
                letterSpacing = prefs[Keys.LETTER_SPACING] ?: 0f,
                justify = prefs[Keys.JUSTIFY] ?: true,
                indentEm = prefs[Keys.INDENT_EM] ?: 2f,
                titleMode = prefs[Keys.TITLE_MODE] ?: 0,
                bottomJustify = prefs[Keys.BOTTOM_JUSTIFY] ?: true,
                oneHandMode = prefs[Keys.ONE_HAND_MODE] ?: false,
                customFontUri = prefs[Keys.CUSTOM_FONT_URI],
                fontId = prefs[Keys.FONT_ID],
                enCustomFontUri = prefs[Keys.EN_CUSTOM_FONT_URI],
                enFontId = prefs[Keys.EN_FONT_ID],
                hideStatusBar = prefs[Keys.HIDE_STATUS_BAR] ?: true,
                hideNavigationBar = prefs[Keys.HIDE_NAVIGATION_BAR] ?: true
            )
        }

    suspend fun saveSettings(settings: ReadingSettings) {
        store.edit { prefs ->
            prefs[Keys.FONT_SIZE] = settings.fontSize
            prefs[Keys.FONT_FAMILY] = settings.fontFamily
            prefs[Keys.BG_COLOR_INDEX] = settings.bgColorIndex
            prefs[Keys.LINE_SPACING] = settings.lineSpacing
            prefs[Keys.PARAGRAPH_SPACING] = settings.paragraphSpacing
            prefs[Keys.PAGE_FLIP_MODE] = settings.pageFlipMode
            prefs[Keys.PADDING_H] = settings.paddingH
            prefs[Keys.PADDING_V] = settings.paddingV
            prefs[Keys.HEADER_CONTENT_GAP] = settings.headerContentGap
            prefs[Keys.FOOTER_CONTENT_GAP] = settings.footerContentGap
            prefs[Keys.LETTER_SPACING] = settings.letterSpacing
            prefs[Keys.JUSTIFY] = settings.justify
            prefs[Keys.INDENT_EM] = settings.indentEm
            prefs[Keys.TITLE_MODE] = settings.titleMode
            prefs[Keys.BOTTOM_JUSTIFY] = settings.bottomJustify
            prefs[Keys.ONE_HAND_MODE] = settings.oneHandMode
            prefs[Keys.HIDE_STATUS_BAR] = settings.hideStatusBar
            prefs[Keys.HIDE_NAVIGATION_BAR] = settings.hideNavigationBar
            writeOptional(prefs, settings.customFontUri, Keys.CUSTOM_FONT_URI)
            writeOptional(prefs, settings.enCustomFontUri, Keys.EN_CUSTOM_FONT_URI)
            writeOptional(prefs, settings.fontId, Keys.FONT_ID)
            writeOptional(prefs, settings.enFontId, Keys.EN_FONT_ID)
        }
    }

    /** 可空字符串键：非空写入、为空删除（`null` 表示「未设置」，不能留空串）。 */
    private fun writeOptional(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        value: String?,
        key: Preferences.Key<String>
    ) {
        if (value != null) prefs[key] = value else prefs.remove(key)
    }

    val nightMode: Flow<Boolean> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[Keys.NIGHT_MODE] ?: false }

    suspend fun saveNightMode(enabled: Boolean) {
        store.edit { prefs -> prefs[Keys.NIGHT_MODE] = enabled }
    }
}
