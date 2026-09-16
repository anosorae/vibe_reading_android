package com.vibereading.app.domain.model

/**
 * 全局主题设置。
 *
 * - [themeMode]：跟随系统 / 浅色 / 深色（对齐 Legado `themeMode`）。
 * - [accent]：主题色系。阅读器的夜间快捷翻转独立于此（见 CONTEXT.md）。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppAccent(val key: String) {
    /** 兼容旧版本的暖色主题。 */
    VIBE("vibe"),
    /** 兼容旧版本的青简主题。 */
    WEREAD("weread"),
    /** 参考图使用的冰蓝主题。 */
    INDIGO("indigo"),
    MOSS("moss"),
    LOTUS("lotus"),
    INK("ink");

    companion object {
        fun fromKey(key: String?): AppAccent = entries.firstOrNull { it.key == key } ?: INDIGO
    }
}

data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AppAccent = AppAccent.INDIGO
)
