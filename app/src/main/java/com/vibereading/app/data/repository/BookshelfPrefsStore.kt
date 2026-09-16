package com.vibereading.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** 书架偏好（布局 / 排序字段 / 排序方向）；由 [SettingsRepository.bookshelf] 提供。 */
class BookshelfPrefsStore(private val store: DataStore<Preferences>) {

    private object Keys {
        val LAYOUT = stringPreferencesKey("bookshelf_layout")   // "list" | "grid"
        val SORT = stringPreferencesKey("bookshelf_sort")       // "recent" | "title" | "created"
        val SORT_ORDER = stringPreferencesKey("bookshelf_sort_order") // "asc" | "desc"
    }

    val layout: Flow<String> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[Keys.LAYOUT] ?: "list" }

    suspend fun saveLayout(layout: String) {
        store.edit { prefs -> prefs[Keys.LAYOUT] = layout }
    }

    val sort: Flow<String> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[Keys.SORT] ?: "recent" }

    suspend fun saveSort(sort: String) {
        store.edit { prefs -> prefs[Keys.SORT] = sort }
    }

    val sortOrder: Flow<String> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[Keys.SORT_ORDER] ?: "desc" }

    suspend fun saveSortOrder(order: String) {
        store.edit { prefs -> prefs[Keys.SORT_ORDER] = order }
    }
}
