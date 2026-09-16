package com.vibereading.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App 偏好的组合根：把五个互不相关的偏好域装配在同一个 DataStore 上。
 *
 * 此前本类自身承担了全部五个域的读写（约 270 行、16 个公开成员），
 * 改一处设置得在一个大类里找；现在各域各有自己的类，本类只负责装配，
 * 调用方按域访问（`settingsRepo.reading.settings`、`settingsRepo.bookshelf.sort`…）。
 *
 * 构造签名保持不变：`AppNavigation` / 各 ViewModel / 测试的接线无需改动。
 */
class SettingsRepository(
    context: Context,
    store: DataStore<Preferences> = context.dataStore
) {
    /** 阅读设置（字号/边距/翻页/排版）与夜间模式。 */
    val reading = ReadingSettingsStore(store)

    /** 全局主题（亮暗模式 + 强调色）。 */
    val theme = ThemeSettingsStore(store)

    /** 书架偏好（布局/排序/顺序）。 */
    val bookshelf = BookshelfPrefsStore(store)

    /** Web 伴读服务开关（ADR-005）。 */
    val companion = CompanionPrefsStore(store)

    /** DataStore 旧 LLM 键 → Room 的一次性迁移。 */
    val llmLegacy = LlmLegacyKeysStore(store)
}
