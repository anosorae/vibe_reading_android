package com.vibereading.app.domain.model

/**
 * 全局主题设置。
 *
 * - [themeMode]：跟随系统 / 浅色 / 深色（对齐 Legado `themeMode`）。
 * - [accent]：主题色系 —— 原木(VIBE) / 青简(WEREAD)。
 * 阅读器的夜间快捷翻转独立于此（见 CONTEXT.md）。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppAccent { VIBE, WEREAD }

data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AppAccent = AppAccent.VIBE
)
