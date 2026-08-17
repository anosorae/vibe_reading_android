package com.vibereading.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.vibereading.app.BuildConfig
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(
    private val context: Context,
    private val store: DataStore<Preferences> = context.dataStore
) {

    // ── LLM Settings ──

    private object LlmKeys {
        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE = stringPreferencesKey("api_base")
        val MODEL = stringPreferencesKey("model")
        val CHAPTER_MAX_CHARS = intPreferencesKey("chapter_max_chars")
        val ENABLE_CONTEXT_BOOST = booleanPreferencesKey("enable_context_boost")
        val CONTEXT_CHAPTERS = intPreferencesKey("context_chapters")
        val CONTEXT_MAX_CHARS = intPreferencesKey("context_max_chars")
        val ENABLE_THINKING = booleanPreferencesKey("enable_thinking")
    }

    private val defaultApiBase: String
        get() = BuildConfig.DEBUG_LLM_API_BASE.trim().trimEnd('/').ifEmpty { "https://api.deepseek.com" }

    val llmSettings: Flow<LlmSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            LlmSettings(
                apiKey = prefs[LlmKeys.API_KEY]?.trim() ?: BuildConfig.DEBUG_LLM_API_KEY.ifEmpty { "" },
                apiBase = prefs[LlmKeys.API_BASE]?.trim()?.trimEnd('/')?.ifEmpty { defaultApiBase } ?: defaultApiBase,
                model = prefs[LlmKeys.MODEL]?.trim() ?: BuildConfig.DEBUG_LLM_MODEL.ifEmpty { "deepseek-v4-flash" },
                chapterMaxChars = prefs[LlmKeys.CHAPTER_MAX_CHARS] ?: 20000,
                enableContextBoost = prefs[LlmKeys.ENABLE_CONTEXT_BOOST] ?: false,
                contextChapters = prefs[LlmKeys.CONTEXT_CHAPTERS]?.coerceIn(1, 3) ?: 1,
                contextMaxChars = prefs[LlmKeys.CONTEXT_MAX_CHARS] ?: 30000,
                enableThinking = prefs[LlmKeys.ENABLE_THINKING] ?: false
            )
        }

    suspend fun saveLlmSettings(settings: LlmSettings) {
        val apiBase = settings.apiBase.trim().trimEnd('/').ifEmpty { defaultApiBase }
        store.edit { prefs ->
            prefs[LlmKeys.API_KEY] = settings.apiKey.trim()
            prefs[LlmKeys.API_BASE] = apiBase
            prefs[LlmKeys.MODEL] = settings.model.trim()
            prefs[LlmKeys.CHAPTER_MAX_CHARS] = settings.chapterMaxChars
            prefs[LlmKeys.ENABLE_CONTEXT_BOOST] = settings.enableContextBoost
            prefs[LlmKeys.CONTEXT_CHAPTERS] = settings.contextChapters.coerceIn(1, 3)
            prefs[LlmKeys.CONTEXT_MAX_CHARS] = settings.contextMaxChars
            prefs[LlmKeys.ENABLE_THINKING] = settings.enableThinking
        }
    }

    // ── Reading Settings ──

    private object ReadingKeys {
        val FONT_SIZE = intPreferencesKey("font_size")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val BG_COLOR_INDEX = intPreferencesKey("bg_color_index")
        val LINE_SPACING = intPreferencesKey("line_spacing")
        val PARAGRAPH_SPACING = intPreferencesKey("paragraph_spacing")
        val PAGE_FLIP_MODE = stringPreferencesKey("page_flip_mode")
        val PADDING_H = intPreferencesKey("padding_h")
        val PADDING_V = intPreferencesKey("padding_v")
        val OVERLAY_CONTENT_GAP = intPreferencesKey("overlay_content_gap")
        val LETTER_SPACING = floatPreferencesKey("letter_spacing")
        val JUSTIFY = booleanPreferencesKey("justify")
        val INDENT_EM = floatPreferencesKey("indent_em")
        val TITLE_MODE = intPreferencesKey("title_mode")
        val BOTTOM_JUSTIFY = booleanPreferencesKey("bottom_justify")
        val ONE_HAND_MODE = booleanPreferencesKey("one_hand_mode")
        val CUSTOM_FONT_URI = stringPreferencesKey("custom_font_uri")
        val HIDE_STATUS_BAR = booleanPreferencesKey("hide_status_bar")
        val HIDE_NAVIGATION_BAR = booleanPreferencesKey("hide_navigation_bar")
        val NIGHT_MODE = booleanPreferencesKey("night_mode")
    }

    val readingSettings: Flow<ReadingSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            ReadingSettings(
                fontSize = prefs[ReadingKeys.FONT_SIZE] ?: 17,
                fontFamily = prefs[ReadingKeys.FONT_FAMILY] ?: "serif",
                bgColorIndex = prefs[ReadingKeys.BG_COLOR_INDEX] ?: 0,
                lineSpacing = prefs[ReadingKeys.LINE_SPACING] ?: 8,
                paragraphSpacing = prefs[ReadingKeys.PARAGRAPH_SPACING] ?: 16,
                pageFlipMode = prefs[ReadingKeys.PAGE_FLIP_MODE] ?: ReadingSettings.FLIP_PAGER,
                paddingH = prefs[ReadingKeys.PADDING_H] ?: 22,
                paddingV = prefs[ReadingKeys.PADDING_V] ?: 20,
                overlayContentGap = prefs[ReadingKeys.OVERLAY_CONTENT_GAP] ?: 20,
                letterSpacing = prefs[ReadingKeys.LETTER_SPACING] ?: 0f,
                justify = prefs[ReadingKeys.JUSTIFY] ?: true,
                indentEm = prefs[ReadingKeys.INDENT_EM] ?: 2f,
                titleMode = prefs[ReadingKeys.TITLE_MODE] ?: 0,
                bottomJustify = prefs[ReadingKeys.BOTTOM_JUSTIFY] ?: true,
                oneHandMode = prefs[ReadingKeys.ONE_HAND_MODE] ?: false,
                customFontUri = prefs[ReadingKeys.CUSTOM_FONT_URI],
                hideStatusBar = prefs[ReadingKeys.HIDE_STATUS_BAR] ?: true,
                hideNavigationBar = prefs[ReadingKeys.HIDE_NAVIGATION_BAR] ?: true
            )
        }

    suspend fun saveReadingSettings(settings: ReadingSettings) {
        store.edit { prefs ->
            prefs[ReadingKeys.FONT_SIZE] = settings.fontSize
            prefs[ReadingKeys.FONT_FAMILY] = settings.fontFamily
            prefs[ReadingKeys.BG_COLOR_INDEX] = settings.bgColorIndex
            prefs[ReadingKeys.LINE_SPACING] = settings.lineSpacing
            prefs[ReadingKeys.PARAGRAPH_SPACING] = settings.paragraphSpacing
            prefs[ReadingKeys.PAGE_FLIP_MODE] = settings.pageFlipMode
            prefs[ReadingKeys.PADDING_H] = settings.paddingH
            prefs[ReadingKeys.PADDING_V] = settings.paddingV
            prefs[ReadingKeys.OVERLAY_CONTENT_GAP] = settings.overlayContentGap
            prefs[ReadingKeys.LETTER_SPACING] = settings.letterSpacing
            prefs[ReadingKeys.JUSTIFY] = settings.justify
            prefs[ReadingKeys.INDENT_EM] = settings.indentEm
            prefs[ReadingKeys.TITLE_MODE] = settings.titleMode
            prefs[ReadingKeys.BOTTOM_JUSTIFY] = settings.bottomJustify
            prefs[ReadingKeys.ONE_HAND_MODE] = settings.oneHandMode
            prefs[ReadingKeys.HIDE_STATUS_BAR] = settings.hideStatusBar
            prefs[ReadingKeys.HIDE_NAVIGATION_BAR] = settings.hideNavigationBar
            if (settings.customFontUri != null) {
                prefs[ReadingKeys.CUSTOM_FONT_URI] = settings.customFontUri
            } else {
                prefs.remove(ReadingKeys.CUSTOM_FONT_URI)
            }
        }
    }

    val nightMode: Flow<Boolean> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[ReadingKeys.NIGHT_MODE] ?: false }

    suspend fun saveNightMode(enabled: Boolean) {
        store.edit { prefs ->
            prefs[ReadingKeys.NIGHT_MODE] = enabled
        }
    }

    // ── Theme Settings ──
    // 旧版只有 accent（"theme" 键存 "vibe"/"weread"）；新版拆为 themeMode + accent，
    // 读取旧键自动迁移为对应 accent，themeMode 默认 SYSTEM。

    private object ThemeKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val LEGACY_THEME = stringPreferencesKey("theme")
    }

    val themeSettings: Flow<ThemeSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val themeMode = when (prefs[ThemeKeys.THEME_MODE]) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            val accent = when (prefs[ThemeKeys.ACCENT] ?: prefs[ThemeKeys.LEGACY_THEME]) {
                "weread" -> AppAccent.WEREAD
                else -> AppAccent.VIBE
            }
            ThemeSettings(themeMode = themeMode, accent = accent)
        }

    suspend fun saveThemeSettings(settings: ThemeSettings) {
        store.edit { prefs ->
            prefs[ThemeKeys.THEME_MODE] = when (settings.themeMode) {
                ThemeMode.SYSTEM -> "system"
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
            }
            prefs[ThemeKeys.ACCENT] = when (settings.accent) {
                AppAccent.VIBE -> "vibe"
                AppAccent.WEREAD -> "weread"
            }
            prefs.remove(ThemeKeys.LEGACY_THEME)
        }
    }

    // ── Bookshelf prefs ──

    private object ShelfKeys {
        val LAYOUT = stringPreferencesKey("bookshelf_layout")   // "list" | "grid"
        val SORT = stringPreferencesKey("bookshelf_sort")       // "recent" | "title" | "created"
    }

    val bookshelfLayout: Flow<String> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[ShelfKeys.LAYOUT] ?: "list" }

    suspend fun saveBookshelfLayout(layout: String) {
        store.edit { prefs -> prefs[ShelfKeys.LAYOUT] = layout }
    }

    val bookshelfSort: Flow<String> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[ShelfKeys.SORT] ?: "recent" }

    suspend fun saveBookshelfSort(sort: String) {
        store.edit { prefs -> prefs[ShelfKeys.SORT] = sort }
    }

    // ── Reading Mode ──

    private object ModeKeys {
        val MODE = stringPreferencesKey("reading_mode")
    }

    val readingMode: Flow<String> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[ModeKeys.MODE] ?: "zh" }

    suspend fun saveReadingMode(mode: String) {
        store.edit { prefs ->
            prefs[ModeKeys.MODE] = mode
        }
    }
}
