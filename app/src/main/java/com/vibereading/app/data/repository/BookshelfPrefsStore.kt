package com.vibereading.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 书架偏好（布局 / 排序字段 / 排序方向）；由 [SettingsRepository.bookshelf] 提供。 */
class BookshelfPrefsStore(private val store: DataStore<Preferences>) {

    private object Keys {
        val LAYOUT = stringPreferencesKey("bookshelf_layout")   // "list" | "grid"
        val SORT = stringPreferencesKey("bookshelf_sort")       // "recent" | "title" | "created"
        val SORT_ORDER = stringPreferencesKey("bookshelf_sort_order") // "asc" | "desc"
    }

    val layout: Flow<String> = store.safeData("读取书架布局失败，回退默认值")
        .map { prefs -> prefs[Keys.LAYOUT] ?: "grid" }

    suspend fun saveLayout(layout: String) {
        store.edit { prefs -> prefs[Keys.LAYOUT] = layout }
    }

    val sort: Flow<String> = store.safeData("读取书架排序失败，回退默认值")
        .map { prefs -> prefs[Keys.SORT] ?: "recent" }

    suspend fun saveSort(sort: String) {
        store.edit { prefs -> prefs[Keys.SORT] = sort }
    }

    val sortOrder: Flow<String> = store.safeData("读取书架排序方向失败，回退默认值")
        .map { prefs -> prefs[Keys.SORT_ORDER] ?: "desc" }

    suspend fun saveSortOrder(order: String) {
        store.edit { prefs -> prefs[Keys.SORT_ORDER] = order }
    }
}
