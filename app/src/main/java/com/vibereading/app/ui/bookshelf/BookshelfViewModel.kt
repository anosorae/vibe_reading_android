package com.vibereading.app.ui.bookshelf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.Book
import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.domain.model.ThemeSettings
import com.vibereading.app.domain.parser.TxtParser
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** 书架排序方式（对齐 Legado bookshelfSort）。 */
object ShelfSort {
    const val RECENT = "recent"   // 最近阅读
    const val TITLE = "title"     // 书名
    const val CREATED = "created" // 上传时间
}

data class BookshelfUiState(
    val items: List<BookShelfItem> = emptyList(),
    val filteredItems: List<BookShelfItem> = emptyList(),
    val isLoading: Boolean = false,
    val uploadMessage: String? = null,
    val accent: AppAccent = AppAccent.VIBE,
    val layout: String = "list",     // "list" | "grid"
    val sort: String = ShelfSort.RECENT
)

class BookshelfViewModel(
    private val bookRepo: BookRepository,
    private val chapterRepo: ChapterRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(
                bookRepo.getShelfItems(),
                settingsRepo.themeSettings,
                settingsRepo.bookshelfLayout,
                settingsRepo.bookshelfSort,
                searchQuery
            ) { items, theme, layout, sort, query ->
                val sorted = sortItems(items, sort)
                val filtered = if (query.isBlank()) {
                    sorted
                } else {
                    sorted.filter { it.book.title.contains(query.trim(), ignoreCase = true) }
                }
                _uiState.value = BookshelfUiState(
                    items = sorted,
                    filteredItems = filtered,
                    accent = theme.accent,
                    layout = layout,
                    sort = sort
                )
            }.collect {}
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun switchLayout(layout: String) {
        viewModelScope.launch { settingsRepo.saveBookshelfLayout(layout) }
    }

    fun switchSort(sort: String) {
        viewModelScope.launch { settingsRepo.saveBookshelfSort(sort) }
    }

    /** 书架排序：最近阅读 / 书名 / 上传时间（对齐 Legado bookshelfSort）。 */
    private fun sortItems(items: List<BookShelfItem>, sort: String): List<BookShelfItem> = when (sort) {
        ShelfSort.TITLE -> items.sortedBy { it.book.title }
        ShelfSort.CREATED -> items.sortedByDescending { it.book.createdAt }
        else -> items.sortedByDescending { it.book.lastReadAt }
    }

    fun uploadBook(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("无法读取文件")

                val text = TxtParser.decodeBytes(bytes)
                val chapterDicts = TxtParser.parseText(text)
                if (chapterDicts.isEmpty()) throw Exception("未识别到任何章节")

                // Extract title from URI — use ContentResolver for reliable name
                val title = queryFileName(context, uri)
                    .removeSuffix(".txt").removeSuffix(".TXT")
                    .ifBlank { "未知书名" }

                val bookId = bookRepo.insert(Book(title = title, totalChapters = chapterDicts.size))
                val chapters = TxtParser.toChapters(bookId, chapterDicts)
                chapterRepo.insertAll(chapters)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        uploadMessage = "「$title」上传成功，共 ${chapterDicts.size} 章"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, uploadMessage = "上传失败: ${e.message}")
                }
            }
        }
    }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            bookRepo.delete(bookId)
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(uploadMessage = null) }
    }

    class Factory(
        private val bookRepo: BookRepository,
        private val chapterRepo: ChapterRepository,
        private val settingsRepo: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookshelfViewModel(bookRepo, chapterRepo, settingsRepo) as T
        }
    }
}

/** Query the display name from a content URI via ContentResolver. */
private fun queryFileName(context: Context, uri: Uri): String {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else ""
    } ?: uri.lastPathSegment?.substringAfterLast("/") ?: ""
}
